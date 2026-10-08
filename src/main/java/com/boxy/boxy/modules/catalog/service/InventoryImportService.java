package com.boxy.boxy.modules.catalog.service;

import com.boxy.boxy.core.realtime.RealtimeEventPublisher;
import com.boxy.boxy.core.realtime.events.StockChange;
import com.boxy.boxy.core.exception.BusinessException;
import com.boxy.boxy.core.security.SecurityUtils;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.User;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.administration.repository.CompanyRepository;
import com.boxy.boxy.modules.administration.repository.UserRepository;
import com.boxy.boxy.modules.administration.repository.WarehouseRepository;
import com.boxy.boxy.modules.administration.service.AuditLogService;
import com.boxy.boxy.modules.catalog.dto.ImportResultDto;
import com.boxy.boxy.modules.catalog.entity.Brand;
import com.boxy.boxy.modules.catalog.entity.Category;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.catalog.repository.BrandRepository;
import com.boxy.boxy.modules.catalog.repository.CategoryRepository;
import com.boxy.boxy.modules.catalog.repository.ProductRepository;
import com.boxy.boxy.modules.catalog.repository.UnitOfMeasureRepository;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import com.boxy.boxy.modules.inventory.entity.StockMovement;
import com.boxy.boxy.modules.inventory.repository.StockLevelRepository;
import com.boxy.boxy.modules.inventory.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class InventoryImportService {

    private final ProductRepository productRepository;
    private final BrandRepository brandRepository;
    private final CategoryRepository categoryRepository;
    private final UnitOfMeasureRepository unitRepository;
    private final WarehouseRepository warehouseRepository;
    private final StockLevelRepository stockLevelRepository;
    private final StockMovementRepository stockMovementRepository;
    private final CompanyRepository companyRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final RealtimeEventPublisher realtimeEvents;

    /** Independent of `spring.servlet.multipart.max-file-size` (50MB, shared by every multipart
     *  endpoint) — a catalog import specifically has no legitimate reason to be this large. */
    private static final long MAX_IMPORT_FILE_SIZE_BYTES = 10L * 1024 * 1024;
    /** Caps how many data rows a single import processes — an unbounded file ties up the request
     *  (and the DB transaction it all runs in) for however long a caller cares to make it. */
    private static final int MAX_IMPORT_ROWS = 10_000;

    private static final byte[] ZIP_MAGIC = {0x50, 0x4B, 0x03, 0x04}; // .xlsx (a zip container)
    private static final byte[] OLE2_MAGIC = {(byte) 0xD0, (byte) 0xCF, 0x11, (byte) 0xE0}; // legacy .xls

    @Transactional
    public ImportResultDto importFile(MultipartFile file, Long requestedWarehouseId) {
        String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "import";
        log.info("Starting import of file: {}", originalFilename);

        if (file.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "El archivo está vacío");
        }
        if (file.getSize() > MAX_IMPORT_FILE_SIZE_BYTES) {
            throw new BusinessException("FILE_TOO_LARGE", "El archivo excede el tamaño máximo permitido (10MB).");
        }

        boolean isCsv = originalFilename.toLowerCase().endsWith(".csv");
        if (!isCsv) {
            verifyExcelMagicBytes(file);
        }

        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new BusinessException("COMPANY_NOT_FOUND", "Company not found for id: " + companyId));

        Long currentUserId = SecurityUtils.requireCurrentUserId();
        User currentUser = userRepository.findById(currentUserId).orElse(null);

        // Parse rows from file (either Excel or CSV)
        List<List<String>> rawRows;
        try (InputStream is = file.getInputStream()) {
            rawRows = isCsv ? parseCsv(is) : parseExcel(is);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to parse file: {}", originalFilename, e);
            throw new BusinessException("FILE_READ_ERROR", "No se pudo leer el archivo. Verifica que el formato sea válido.");
        }

        if (rawRows.isEmpty()) {
            throw new BusinessException("FILE_EMPTY", "El archivo está vacío");
        }
        if (rawRows.size() > MAX_IMPORT_ROWS) {
            throw new BusinessException("FILE_TOO_MANY_ROWS",
                    "El archivo tiene más de " + MAX_IMPORT_ROWS + " filas. Divídelo en archivos más pequeños.");
        }

        // Find header row and column mapping
        int headerRowIndex = findHeaderRowIndex(rawRows);
        if (headerRowIndex == -1) {
            throw new BusinessException("HEADER_NOT_FOUND", "No se encontró la cabecera del archivo. Debe contener una columna 'CÓDIGO'.");
        }

        List<String> headerRow = rawRows.get(headerRowIndex);
        ColumnMapping mapping = ColumnMapping.fromHeader(headerRow);
        String fileType = detectFileType(mapping);

        log.info("Detected file type: {} with headers: {}", fileType, headerRow);

        // Pre-cache existing brands, categories, units, and warehouses for the company
        Map<String, Brand> brandCache = new HashMap<>();
        brandRepository.findByCompanyIdAndDeletedAtIsNull(companyId)
                .forEach(b -> brandCache.put(b.getName().trim().toUpperCase(), b));

        Map<String, Category> categoryCache = new HashMap<>();
        categoryRepository.findByCompanyIdAndDeletedAtIsNull(companyId)
                .forEach(c -> categoryCache.put(c.getName().trim().toUpperCase(), c));

        Map<String, UnitOfMeasure> unitCache = new HashMap<>();
        unitRepository.findByCompanyIdAndDeletedAtIsNull(companyId)
                .forEach(u -> {
                    unitCache.put(u.getName().trim().toUpperCase(), u);
                    unitCache.put(u.getCode().trim().toUpperCase(), u);
                    unitCache.put(u.getSymbol().trim().toUpperCase(), u);
                });

        Map<String, Warehouse> warehouseCache = new HashMap<>();
        warehouseRepository.findByBranchCompanyIdAndDeletedAtIsNull(companyId)
                .forEach(w -> {
                    warehouseCache.put(w.getName().trim().toUpperCase(), w);
                    warehouseCache.put(w.getCode().trim().toUpperCase(), w);
                });

        Warehouse defaultWarehouse = null;
        if (requestedWarehouseId != null) {
            defaultWarehouse = warehouseRepository.findByIdAndDeletedAtIsNull(requestedWarehouseId)
                    .filter(w -> w.getBranch().getCompany().getId().equals(companyId))
                    .orElse(null);
        }
        if (defaultWarehouse == null) {
            List<Warehouse> warehouses = warehouseRepository.findByBranchCompanyIdAndDeletedAtIsNull(companyId);
            defaultWarehouse = warehouses.stream().filter(Warehouse::getIsDefault).findFirst()
                    .orElse(warehouses.isEmpty() ? null : warehouses.get(0));
        }

        int totalRows = 0;
        int processedRows = 0;
        int createdCount = 0;
        // One stock event per warehouse touched, not one per row — a file can carry hundreds of lines.
        Map<Long, Warehouse> touchedWarehouses = new LinkedHashMap<>();
        Map<Long, List<StockChange>> stockChangesByWarehouse = new HashMap<>();
        int updatedCount = 0;
        int skippedCount = 0;
        List<String> errors = new ArrayList<>();

        for (int i = headerRowIndex + 1; i < rawRows.size(); i++) {
            List<String> row = rawRows.get(i);
            totalRows++;

            String sku = mapping.getValue(row, mapping.skuCol);
            if (sku == null || sku.isBlank()) {
                skippedCount++;
                continue;
            }
            sku = sku.trim();
            if (sku.equalsIgnoreCase("TOTAL") || isTotalRow(row)) {
                skippedCount++;
                continue;
            }

            try {
                // 1. Resolve or Create Brand
                String brandName = mapping.getValue(row, mapping.brandCol);
                Brand brand = null;
                if (brandName != null && !brandName.isBlank()) {
                    brandName = brandName.trim();
                    brand = brandCache.get(brandName.toUpperCase());
                    if (brand == null) {
                        brand = brandRepository.save(Brand.builder()
                                .company(company)
                                .name(brandName)
                                .isActive(true)
                                .build());
                        brandCache.put(brandName.toUpperCase(), brand);
                    }
                }

                // 2. Resolve or Create Category
                String categoryName = mapping.getValue(row, mapping.categoryCol);
                Category category = null;
                if (categoryName != null && !categoryName.isBlank()) {
                    categoryName = categoryName.trim();
                    category = categoryCache.get(categoryName.toUpperCase());
                    if (category == null) {
                        String catCode = generateCode(categoryName, "CAT");
                        category = categoryRepository.save(Category.builder()
                                .company(company)
                                .code(catCode)
                                .name(categoryName)
                                .isActive(true)
                                .build());
                        categoryCache.put(categoryName.toUpperCase(), category);
                    }
                }

                // 3. Resolve or Create Unit of Measure
                String unitName = mapping.getValue(row, mapping.unitCol);
                UnitOfMeasure unit = null;
                if (unitName != null && !unitName.isBlank()) {
                    unitName = unitName.trim();
                    unit = unitCache.get(unitName.toUpperCase());
                    if (unit == null) {
                        String unitCode = generateCode(unitName, "UND");
                        String symbol = unitName.length() > 5 ? unitName.substring(0, 3).toUpperCase() : unitName.toUpperCase();
                        unit = unitRepository.save(UnitOfMeasure.builder()
                                .company(company)
                                .code(unitCode)
                                .name(unitName)
                                .symbol(symbol)
                                .isActive(true)
                                .build());
                        unitCache.put(unitName.toUpperCase(), unit);
                    }
                }
                if (unit == null) {
                    unit = unitCache.get("UNIDAD");
                    if (unit == null && !unitCache.isEmpty()) {
                        unit = unitCache.values().iterator().next();
                    }
                }

                // 4. Resolve Product details
                String barcode = mapping.getValue(row, mapping.barcodeCol);
                if (barcode != null) {
                    barcode = barcode.trim();
                    if (barcode.isBlank() || barcode.equalsIgnoreCase("NULL")) barcode = null;
                    else if (barcode.length() > 100) barcode = barcode.substring(0, 100);
                }

                String description = mapping.getValue(row, mapping.descriptionCol);
                String fullDesc = (description != null && !description.isBlank()) ? cleanQuotes(description.trim()) : sku;
                String productName = fullDesc.length() > 200 ? fullDesc.substring(0, 200) : fullDesc;

                BigDecimal costPrice = parseDecimal(mapping.getValue(row, mapping.costPriceCol));
                BigDecimal sellingPrice = parseDecimal(mapping.getValue(row, mapping.sellingPriceCol));
                BigDecimal invoicePrice = parseDecimal(mapping.getValue(row, mapping.invoicePriceCol));

                if (sellingPrice.compareTo(BigDecimal.ZERO) == 0 && invoicePrice.compareTo(BigDecimal.ZERO) > 0) {
                    sellingPrice = invoicePrice;
                }

                // If Inventory file and costPrice is 0, try to compute from totalCost / quantity
                BigDecimal quantity = parseDecimal(mapping.getValue(row, mapping.quantityCol));
                BigDecimal totalCost = parseDecimal(mapping.getValue(row, mapping.totalCostCol));
                if (costPrice.compareTo(BigDecimal.ZERO) == 0 && totalCost.compareTo(BigDecimal.ZERO) > 0 && quantity.compareTo(BigDecimal.ZERO) > 0) {
                    costPrice = totalCost.divide(quantity, 4, RoundingMode.HALF_UP);
                }

                // 5. Upsert Product
                Optional<Product> existingOpt = productRepository.findByCompanyIdAndSkuAndDeletedAtIsNull(companyId, sku);
                Product product;
                if (existingOpt.isPresent()) {
                    product = existingOpt.get();
                    product.setName(productName);
                    product.setDescription(fullDesc);
                    if (brand != null) product.setBrand(brand);
                    if (category != null) product.setCategory(category);
                    if (unit != null) product.setUnit(unit);
                    if (barcode != null) product.setBarcode(barcode);
                    if (costPrice.compareTo(BigDecimal.ZERO) > 0) product.setCostPrice(costPrice);
                    if (sellingPrice.compareTo(BigDecimal.ZERO) > 0) product.setSellingPrice(sellingPrice);
                    product.setIsActive(true);
                    productRepository.save(product);
                    updatedCount++;
                } else {
                    product = Product.builder()
                            .company(company)
                            .sku(sku)
                            .barcode(barcode)
                            .name(productName)
                            .description(fullDesc)
                            .brand(brand)
                            .category(category)
                            .unit(unit)
                            .costPrice(costPrice)
                            .sellingPrice(sellingPrice)
                            .minStockAlert(BigDecimal.ZERO)
                            .hasVariants(false)
                            .isActive(true)
                            .build();
                    product = productRepository.save(product);
                    createdCount++;
                }

                // 6. If INVENTORY file (has quantity), update StockLevel and create Kardex entry
                if ("INVENTORY".equals(fileType) && quantity.compareTo(BigDecimal.ZERO) > 0) {
                    Warehouse targetWarehouse = defaultWarehouse;
                    String locationName = mapping.getValue(row, mapping.locationCol);
                    if (locationName != null && !locationName.isBlank()) {
                        Warehouse found = warehouseCache.get(locationName.trim().toUpperCase());
                        if (found != null) {
                            targetWarehouse = found;
                        }
                    }

                    if (targetWarehouse != null) {
                        Optional<StockLevel> stockOpt = stockLevelRepository
                                .findByWarehouseIdAndProductIdAndVariantIdIsNull(targetWarehouse.getId(), product.getId());
                        StockLevel stockLevel;
                        BigDecimal availableBefore = stockOpt.map(StockLevel::getQuantityAvailable).orElse(BigDecimal.ZERO);
                        if (stockOpt.isPresent()) {
                            stockLevel = stockOpt.get();
                            stockLevel.setQuantityAvailable(quantity);
                            stockLevelRepository.save(stockLevel);
                        } else {
                            stockLevel = StockLevel.builder()
                                    .warehouse(targetWarehouse)
                                    .product(product)
                                    .quantityAvailable(quantity)
                                    .quantityReserved(BigDecimal.ZERO)
                                    .quantityInTransit(BigDecimal.ZERO)
                                    .build();
                            stockLevelRepository.save(stockLevel);
                        }

                        touchedWarehouses.putIfAbsent(targetWarehouse.getId(), targetWarehouse);
                        stockChangesByWarehouse.computeIfAbsent(targetWarehouse.getId(), k -> new ArrayList<>())
                                .add(new StockChange(product.getId(), product.getMinStockAlert(), availableBefore, quantity));

                        // Create Stock Movement (Kardex)
                        String refId = "IMP-" + sku;
                        if (refId.length() > 36) refId = refId.substring(0, 36);

                        StockMovement movement = StockMovement.builder()
                                .warehouse(targetWarehouse)
                                .product(product)
                                .movementType("INITIAL_STOCK")
                                .quantity(quantity)
                                .unitCost(costPrice)
                                .balanceAfter(quantity)
                                .referenceType("EXCEL_IMPORT")
                                .referenceId(refId)
                                .notes("Carga inicial vía importación Excel (" + originalFilename + ")")
                                .createdBy(currentUser)
                                .build();
                        stockMovementRepository.save(movement);
                    }
                }

                processedRows++;
            } catch (Exception ex) {
                log.warn("Error processing row {}: {}", i + 1, ex.getMessage());
                if (errors.size() < 20) {
                    errors.add("Fila " + (i + 1) + " [" + sku + "]: " + ex.getMessage());
                }
            }
        }

        String summary = String.format("Importación exitosa de %s: %d productos creados, %d actualizados, %d procesados en total.",
                fileType, createdCount, updatedCount, processedRows);

        auditLogService.record("Importación de productos", "Producto", null, originalFilename, null, summary);
        stockChangesByWarehouse.forEach((warehouseId, changes) ->
                realtimeEvents.stockChanged(touchedWarehouses.get(warehouseId), changes));

        return ImportResultDto.builder()
                .filename(originalFilename)
                .fileType(fileType)
                .totalRows(totalRows)
                .processedRows(processedRows)
                .createdCount(createdCount)
                .updatedCount(updatedCount)
                .skippedCount(skippedCount)
                .errors(errors)
                .message(summary)
                .build();
    }

    private String detectFileType(ColumnMapping m) {
        if (m.quantityCol != -1) {
            return "INVENTORY";
        }
        if (m.invoicePriceCol != -1) {
            return "CATALOG";
        }
        return "PRODUCTS";
    }

    private int findHeaderRowIndex(List<List<String>> rows) {
        for (int i = 0; i < Math.min(15, rows.size()); i++) {
            List<String> r = rows.get(i);
            for (String cell : r) {
                if (cell != null) {
                    String norm = cell.trim().toUpperCase();
                    if (norm.equals("CÓDIGO") || norm.equals("CODIGO") || norm.equals("SKU")) {
                        return i;
                    }
                }
            }
        }
        return -1;
    }

    private boolean isTotalRow(List<String> row) {
        for (String cell : row) {
            if (cell != null && cell.trim().equalsIgnoreCase("TOTAL")) {
                return true;
            }
        }
        return false;
    }

    private String cleanQuotes(String text) {
        if (text.startsWith("\"") && text.endsWith("\"") && text.length() >= 2) {
            text = text.substring(1, text.length() - 1);
        }
        return text.replace("\"\"", "\"");
    }

    private String generateCode(String name, String prefix) {
        String clean = name.replaceAll("[^a-zA-Z0-9]", "").toUpperCase();
        if (clean.length() > 6) clean = clean.substring(0, 6);
        return prefix + "-" + (clean.isEmpty() ? UUID.randomUUID().toString().substring(0, 4).toUpperCase() : clean);
    }

    private BigDecimal parseDecimal(String val) {
        if (val == null || val.isBlank()) return BigDecimal.ZERO;
        try {
            String clean = val.replace("$", "").replace("Bs", "").replace(",", ".").trim();
            // In case of multiple dots due to formatting e.g. 1.540.00
            int lastDot = clean.lastIndexOf('.');
            if (lastDot != -1) {
                String before = clean.substring(0, lastDot).replace(".", "");
                String after = clean.substring(lastDot);
                clean = before + after;
            }
            return new BigDecimal(clean);
        } catch (Exception e) {
            return BigDecimal.ZERO;
        }
    }

    /** The filename extension is caller-supplied and trivially spoofable — this confirms an
     *  ".xlsx"/".xls" upload is actually the binary container format {@code WorkbookFactory}
     *  expects, instead of trusting the extension alone before handing it to POI. */
    private void verifyExcelMagicBytes(MultipartFile file) {
        byte[] header = new byte[4];
        try (InputStream is = file.getInputStream()) {
            int read = is.readNBytes(header, 0, 4);
            if (read < 4 || !(startsWith(header, ZIP_MAGIC) || startsWith(header, OLE2_MAGIC))) {
                throw new BusinessException("INVALID_FILE_TYPE",
                        "El archivo no parece ser un Excel válido (.xlsx o .xls).");
            }
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("INVALID_FILE_TYPE", "No se pudo verificar el tipo de archivo.");
        }
    }

    private boolean startsWith(byte[] data, byte[] prefix) {
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private List<List<String>> parseExcel(InputStream is) throws Exception {
        List<List<String>> result = new ArrayList<>();
        try (Workbook workbook = WorkbookFactory.create(is)) {
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            for (Row row : sheet) {
                List<String> rowData = new ArrayList<>();
                int lastCellNum = Math.max(row.getLastCellNum(), 0);
                for (int c = 0; c < lastCellNum; c++) {
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.RETURN_BLANK_AS_NULL);
                    if (cell == null) {
                        rowData.add("");
                    } else {
                        rowData.add(formatter.formatCellValue(cell).trim());
                    }
                }
                result.add(rowData);
            }
        }
        return result;
    }

    private List<List<String>> parseCsv(InputStream is) throws Exception {
        List<List<String>> result = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                char delimiter = line.contains(";") ? ';' : ',';
                result.add(parseCsvLine(line, delimiter));
            }
        }
        return result;
    }

    private List<String> parseCsvLine(String line, char delimiter) {
        List<String> tokens = new ArrayList<>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\"') {
                inQuotes = !inQuotes;
            } else if (ch == delimiter && !inQuotes) {
                tokens.add(sb.toString().trim());
                sb.setLength(0);
            } else {
                sb.append(ch);
            }
        }
        tokens.add(sb.toString().trim());
        return tokens;
    }

    private static class ColumnMapping {
        int skuCol = -1;
        int barcodeCol = -1;
        int brandCol = -1;
        int categoryCol = -1;
        int unitCol = -1;
        int descriptionCol = -1;
        int costPriceCol = -1;
        int sellingPriceCol = -1;
        int invoicePriceCol = -1;
        int quantityCol = -1;
        int locationCol = -1;
        int totalCostCol = -1;

        static ColumnMapping fromHeader(List<String> headers) {
            ColumnMapping m = new ColumnMapping();
            for (int i = 0; i < headers.size(); i++) {
                String h = headers.get(i).trim().toUpperCase();
                if (h.contains("CÓDIGO ÍTEM") || h.contains("CÓDIGO ITEM") || h.contains("CODIGO ITEM") || h.contains("CODIGO_ITEM")) {
                    m.barcodeCol = i;
                } else if (h.contains("CÓDIGO") || h.contains("CODIGO") || h.equals("SKU")) {
                    m.skuCol = i;
                } else if (h.contains("MARCA") || h.contains("LINEA")) {
                    m.brandCol = i;
                } else if (h.contains("CATEGORÍA") || h.contains("CATEGORIA")) {
                    m.categoryCol = i;
                } else if (h.contains("UNIDAD") || h.contains("UDM")) {
                    m.unitCol = i;
                } else if (h.contains("DESCRIPCIÓN") || h.contains("DESCRIPCION") || h.contains("NOMBRE")) {
                    m.descriptionCol = i;
                } else if (h.contains("PRECIO FACTURA") || h.contains("PRECIO_FACTURA")) {
                    m.invoicePriceCol = i;
                } else if (h.contains("PRECIO UNIT") || h.contains("PRECIO UNITARIO") || h.equals("PRECIO")) {
                    m.sellingPriceCol = i;
                } else if (h.contains("COSTO T") || h.contains("COSTO TOTAL")) {
                    m.totalCostCol = i;
                } else if (h.contains("COSTO")) {
                    m.costPriceCol = i;
                } else if (h.contains("CANTIDAD") || h.contains("STOCK") || h.contains("EXISTENCIA")) {
                    m.quantityCol = i;
                } else if (h.contains("UBICACIÓN") || h.contains("UBICACION") || h.contains("ALMACEN")) {
                    m.locationCol = i;
                }
            }
            return m;
        }

        String getValue(List<String> row, int colIndex) {
            if (colIndex >= 0 && colIndex < row.size()) {
                return row.get(colIndex);
            }
            return null;
        }
    }
}
