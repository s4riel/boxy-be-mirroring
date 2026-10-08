package com.boxy.boxy.modules.catalog.service;

import com.boxy.boxy.core.realtime.RealtimeEventPublisher;
import com.boxy.boxy.core.realtime.events.StockChange;
import com.boxy.boxy.core.exception.BusinessException;
import com.boxy.boxy.core.exception.ResourceNotFoundException;
import com.boxy.boxy.core.security.SecurityUtils;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.Tax;
import com.boxy.boxy.modules.administration.entity.User;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.administration.repository.CompanyRepository;
import com.boxy.boxy.modules.administration.repository.TaxRepository;
import com.boxy.boxy.modules.administration.repository.UserRepository;
import com.boxy.boxy.modules.administration.repository.WarehouseRepository;
import com.boxy.boxy.modules.administration.service.AuditLogService;
import com.boxy.boxy.modules.catalog.dto.*;
import com.boxy.boxy.modules.catalog.entity.Brand;
import com.boxy.boxy.modules.catalog.entity.Category;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.ProductCostHistory;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.catalog.repository.BrandRepository;
import com.boxy.boxy.modules.catalog.repository.CategoryRepository;
import com.boxy.boxy.modules.catalog.repository.ProductCostHistoryRepository;
import com.boxy.boxy.modules.catalog.repository.ProductRepository;
import com.boxy.boxy.modules.catalog.repository.UnitOfMeasureRepository;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import com.boxy.boxy.modules.inventory.entity.StockMovement;
import com.boxy.boxy.modules.inventory.repository.StockMovementRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final BrandRepository brandRepository;
    private final UnitOfMeasureRepository unitOfMeasureRepository;
    private final TaxRepository taxRepository;
    private final CompanyRepository companyRepository;
    private final com.boxy.boxy.modules.inventory.repository.StockLevelRepository stockLevelRepository;
    private final StockMovementRepository stockMovementRepository;
    private final WarehouseRepository warehouseRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;
    private final RealtimeEventPublisher realtimeEvents;
    private final ProductCostHistoryRepository productCostHistoryRepository;

    @Transactional(readOnly = true)
    public Page<ProductDto> getProducts(
            String search,
            List<Long> categoryIds,
            List<Long> brandIds,
            Boolean isActive,
            List<Long> warehouseIds,
            List<String> stockStatuses,
            Pageable pageable) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();

        boolean stockStatusFilterActive = stockStatuses != null && !stockStatuses.isEmpty();
        boolean matchInStock = stockStatusFilterActive && stockStatuses.contains("in-stock");
        boolean matchLowStock = stockStatusFilterActive && stockStatuses.contains("low-stock");
        boolean matchOutOfStock = stockStatusFilterActive && stockStatuses.contains("out-of-stock");
        boolean matchInTransit = stockStatusFilterActive && stockStatuses.contains("in-transit");

        return productRepository.findAllFiltered(
                companyId, search,
                (categoryIds == null || categoryIds.isEmpty()) ? null : categoryIds,
                (brandIds == null || brandIds.isEmpty()) ? null : brandIds,
                isActive,
                (warehouseIds == null || warehouseIds.isEmpty()) ? null : warehouseIds,
                stockStatusFilterActive, matchInStock, matchLowStock, matchOutOfStock, matchInTransit,
                pageable)
                .map(this::toDto);
    }

    @Transactional(readOnly = true)
    public ProductDto getProductById(Long id) {
        Product product = findOwnedProduct(id);
        return toDto(product);
    }

    @Transactional(readOnly = true)
    public ProductMetricsDto getMetrics(Long id) {
        Product product = findOwnedProduct(id);

        BigDecimal availableStock = stockLevelRepository.getTotalAvailableStockByProductId(id);
        BigDecimal inTransitStock = stockLevelRepository.getTotalInTransitStockByProductId(id);
        BigDecimal averageCost = product.getCostPrice() != null ? product.getCostPrice() : BigDecimal.ZERO;
        BigDecimal sellingPrice = product.getSellingPrice() != null ? product.getSellingPrice() : BigDecimal.ZERO;

        BigDecimal marginPercent = null;
        if (sellingPrice.compareTo(BigDecimal.ZERO) > 0) {
            marginPercent = sellingPrice.subtract(averageCost)
                    .divide(sellingPrice, 4, java.math.RoundingMode.HALF_UP)
                    .multiply(BigDecimal.valueOf(100));
        }

        return ProductMetricsDto.builder()
                .availableStock(availableStock)
                .inTransitStock(inTransitStock)
                .inventoryValue(availableStock.multiply(averageCost))
                .minStock(product.getMinStockAlert() != null ? product.getMinStockAlert() : BigDecimal.ZERO)
                .averageCost(averageCost)
                .marginPercent(marginPercent)
                .build();
    }

    /** One row per goods receipt that recalculated this product's cost — see
     *  {@code PurchasingService#receiveGoods}, which is what writes these. */
    @Transactional(readOnly = true)
    public Page<ProductCostHistoryDto> getCostHistory(Long id, Pageable pageable) {
        findOwnedProduct(id);
        return productCostHistoryRepository.findByProductIdOrderByCreatedAtDesc(id, pageable)
                .map(this::toCostHistoryDto);
    }

    private ProductCostHistoryDto toCostHistoryDto(ProductCostHistory h) {
        return ProductCostHistoryDto.builder()
                .id(h.getId())
                .previousCost(h.getPreviousCost())
                .newCost(h.getNewCost())
                .unitCost(h.getUnitCost())
                .quantityReceived(h.getQuantityReceived())
                .goodsReceiptNumber(h.getGoodsReceiptNumber())
                .createdByName(h.getCreatedBy() != null ? h.getCreatedBy().getFullName() : "SYSTEM")
                .createdAt(h.getCreatedAt())
                .build();
    }

    @Transactional
    public ProductDto createProduct(CreateProductRequest request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        if (productRepository.findByCompanyIdAndSkuAndDeletedAtIsNull(companyId, request.getSku()).isPresent()) {
            throw new BusinessException("SKU_EXISTS", "Ya existe un producto con el código '" + request.getSku() + "'.");
        }

        UnitOfMeasure unit = findOwnedUnit(request.getUnitId());

        Category category = request.getCategoryId() != null ? findOwnedCategory(request.getCategoryId()) : null;
        Brand brand = request.getBrandId() != null ? findOwnedBrand(request.getBrandId()) : null;

        Tax tax = request.getTaxId() != null
                ? taxRepository.findByIdAndCompanyIdAndDeletedAtIsNull(request.getTaxId(), companyId)
                        .orElseThrow(() -> new ResourceNotFoundException("Tax", request.getTaxId()))
                : null;

        Product product = Product.builder()
                .company(company)
                .category(category)
                .brand(brand)
                .unit(unit)
                .tax(tax)
                .sku(request.getSku().toUpperCase().trim())
                .barcode(request.getBarcode())
                .name(request.getName().trim())
                .description(request.getDescription())
                .costPrice(request.getCostPrice())
                .sellingPrice(request.getSellingPrice())
                .minStockAlert(request.getMinStockAlert() != null ? request.getMinStockAlert() : BigDecimal.ZERO)
                .imageUrl(request.getImageUrl())
                .hasVariants(false)
                .isActive(true)
                .createdBy(SecurityUtils.requireCurrentUserId())
                .build();

        Product saved = productRepository.save(product);
        seedInitialStock(saved, request, companyId);
        auditLogService.record("Producto creado", "Producto", String.valueOf(saved.getId()),
                saved.getName(), null, null);
        return toDto(saved);
    }

    /** Opening stock is create-only: a {@link StockLevel} plus its INITIAL_STOCK Kardex entry. */
    private void seedInitialStock(Product product, CreateProductRequest request, Long companyId) {
        if (request.getInitialStock() == null || request.getInitialStock().compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }

        Warehouse warehouse = resolveInitialWarehouse(request.getInitialWarehouseId(), companyId);
        if (warehouse == null) {
            throw new BusinessException("NO_WAREHOUSE_AVAILABLE",
                    "No active warehouse is available to receive the initial stock.");
        }

        StockLevel stockLevel = StockLevel.builder()
                .warehouse(warehouse)
                .product(product)
                .quantityAvailable(request.getInitialStock())
                .build();
        stockLevelRepository.save(stockLevel);
        realtimeEvents.stockChanged(warehouse, java.util.List.of(new StockChange(
                product.getId(), product.getMinStockAlert(), BigDecimal.ZERO, request.getInitialStock())));

        User currentUser = userRepository.findByIdAndDeletedAtIsNull(SecurityUtils.requireCurrentUserId()).orElse(null);

        StockMovement movement = StockMovement.builder()
                .warehouse(warehouse)
                .product(product)
                .movementType("INITIAL_STOCK")
                .quantity(request.getInitialStock())
                .unitCost(product.getCostPrice())
                .balanceAfter(request.getInitialStock())
                .referenceType("PRODUCT_CREATE")
                .referenceId(String.valueOf(product.getId()))
                .notes("Stock inicial al crear el producto")
                .createdBy(currentUser)
                .build();
        stockMovementRepository.save(movement);
    }

    private Warehouse resolveInitialWarehouse(Long requestedWarehouseId, Long companyId) {
        if (requestedWarehouseId != null) {
            return warehouseRepository.findByIdAndDeletedAtIsNull(requestedWarehouseId)
                    .filter(w -> w.getBranch().getCompany().getId().equals(companyId))
                    .orElseThrow(() -> new ResourceNotFoundException("Warehouse", requestedWarehouseId));
        }

        Long branchId = SecurityUtils.requireCurrentBranchId();
        return warehouseRepository.findByBranchIdAndIsDefaultTrueAndDeletedAtIsNull(branchId)
                .or(() -> warehouseRepository.findByBranchIdAndDeletedAtIsNull(branchId).stream().findFirst())
                .or(() -> warehouseRepository.findByBranchCompanyIdAndDeletedAtIsNull(companyId).stream().findFirst())
                .orElse(null);
    }

    @Transactional
    public ProductDto updateProduct(Long id, CreateProductRequest request) {
        Product p = findOwnedProduct(id);

        if (request.getCategoryId() != null) {
            p.setCategory(findOwnedCategory(request.getCategoryId()));
        }
        if (request.getBrandId() != null) {
            p.setBrand(findOwnedBrand(request.getBrandId()));
        }
        if (request.getUnitOfMeasureId() != null) {
            p.setUnitOfMeasure(findOwnedUnit(request.getUnitOfMeasureId()));
        }

        if (request.getSku() != null) p.setSku(request.getSku().trim().toUpperCase());
        if (request.getBarcode() != null) p.setBarcode(request.getBarcode().trim());
        if (request.getName() != null) p.setName(request.getName().trim());
        if (request.getDescription() != null) p.setDescription(request.getDescription());
        if (request.getCostPrice() != null) p.setCostPrice(request.getCostPrice());
        if (request.getSellingPrice() != null) p.setSellingPrice(request.getSellingPrice());
        if (request.getMinStockAlert() != null) p.setMinStockAlert(request.getMinStockAlert());
        if (request.getImageUrl() != null) p.setImageUrl(request.getImageUrl());

        Product saved = productRepository.save(p);
        auditLogService.record("Producto actualizado", "Producto", String.valueOf(saved.getId()),
                saved.getName(), null, null);
        return toDto(saved);
    }

    @Transactional
    public ProductDto archiveProduct(Long id) {
        Product p = findOwnedProduct(id);
        p.setIsActive(!Boolean.TRUE.equals(p.getIsActive()));
        Product saved = productRepository.save(p);
        auditLogService.record(Boolean.TRUE.equals(saved.getIsActive()) ? "Producto reactivado" : "Producto archivado",
                "Producto", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toDto(saved);
    }

    /** 404s (not 403) on a product belonging to another company — same treatment as "doesn't exist". */
    private Product findOwnedProduct(Long id) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return productRepository.findByIdAndCompanyIdAndDeletedAtIsNull(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Product", id));
    }

    @Transactional(readOnly = true)
    public boolean isUnique(String field, String value, Long excludeId) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        if ("sku".equalsIgnoreCase(field)) {
            return productRepository.findByCompanyIdAndSkuIgnoreCaseAndDeletedAtIsNull(companyId, value)
                    .map(p -> p.getId().equals(excludeId))
                    .orElse(true);
        } else if ("barcode".equalsIgnoreCase(field)) {
            return productRepository.findByCompanyIdAndBarcodeAndDeletedAtIsNull(companyId, value)
                    .map(p -> p.getId().equals(excludeId))
                    .orElse(true);
        }
        return true;
    }

    @Transactional(readOnly = true)
    public List<CategoryDto> getCategories() {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return categoryRepository.findByCompanyIdAndDeletedAtIsNull(companyId).stream()
                .map(this::toCategoryDto)
                .toList();
    }

    @Transactional
    public CategoryDto createCategory(CategoryDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        String name = request.getName().trim();
        categoryRepository.findByCompanyIdAndNameIgnoreCaseAndDeletedAtIsNull(companyId, name)
                .ifPresent(existing -> {
                    throw new BusinessException("CATEGORY_NAME_EXISTS", "A category named '" + name + "' already exists.");
                });

        Category category = Category.builder()
                .company(company)
                .code(generateUniqueCode(name, code -> categoryRepository.findByCompanyIdAndCodeIgnoreCaseAndDeletedAtIsNull(companyId, code).isPresent()))
                .name(name)
                .description(request.getDescription())
                .isActive(true)
                .build();

        Category saved = categoryRepository.save(category);
        auditLogService.record("Categoría creada", "Categoría", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toCategoryDto(saved);
    }

    @Transactional
    public CategoryDto updateCategory(Long id, CategoryDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Category category = findOwnedCategory(id);

        if (request.getName() != null && !request.getName().isBlank()
                && !request.getName().trim().equalsIgnoreCase(category.getName())) {
            String name = request.getName().trim();
            categoryRepository.findByCompanyIdAndNameIgnoreCaseAndDeletedAtIsNull(companyId, name)
                    .ifPresent(existing -> {
                        throw new BusinessException("CATEGORY_NAME_EXISTS", "A category named '" + name + "' already exists.");
                    });
            category.setName(name);
        }
        if (request.getDescription() != null) {
            category.setDescription(request.getDescription());
        }

        Category saved = categoryRepository.save(category);
        auditLogService.record("Categoría actualizada", "Categoría", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toCategoryDto(saved);
    }

    @Transactional
    public void deleteCategory(Long id) {
        Category category = findOwnedCategory(id);
        long productCount = productRepository.countByCategoryIdAndDeletedAtIsNull(id);
        if (productCount > 0) {
            throw new BusinessException("CATEGORY_IN_USE",
                    "This category is used by " + productCount + " product(s) and cannot be deleted.");
        }
        category.setDeletedAt(java.time.Instant.now());
        categoryRepository.save(category);
        auditLogService.record("Categoría eliminada", "Categoría", String.valueOf(id), category.getName(), null, null);
    }

    @Transactional(readOnly = true)
    public List<BrandDto> getBrands() {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return brandRepository.findByCompanyIdAndDeletedAtIsNull(companyId).stream()
                .map(this::toBrandDto)
                .toList();
    }

    @Transactional
    public BrandDto createBrand(BrandDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        String name = request.getName().trim();
        brandRepository.findByCompanyIdAndNameIgnoreCaseAndDeletedAtIsNull(companyId, name)
                .ifPresent(existing -> {
                    throw new BusinessException("BRAND_NAME_EXISTS", "A brand named '" + name + "' already exists.");
                });

        Brand brand = Brand.builder()
                .company(company)
                .name(name)
                .description(request.getDescription())
                .isActive(true)
                .build();

        Brand saved = brandRepository.save(brand);
        auditLogService.record("Marca creada", "Marca", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toBrandDto(saved);
    }

    @Transactional
    public BrandDto updateBrand(Long id, BrandDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Brand brand = findOwnedBrand(id);

        if (request.getName() != null && !request.getName().isBlank()
                && !request.getName().trim().equalsIgnoreCase(brand.getName())) {
            String name = request.getName().trim();
            brandRepository.findByCompanyIdAndNameIgnoreCaseAndDeletedAtIsNull(companyId, name)
                    .ifPresent(existing -> {
                        throw new BusinessException("BRAND_NAME_EXISTS", "A brand named '" + name + "' already exists.");
                    });
            brand.setName(name);
        }
        if (request.getDescription() != null) {
            brand.setDescription(request.getDescription());
        }

        Brand saved = brandRepository.save(brand);
        auditLogService.record("Marca actualizada", "Marca", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toBrandDto(saved);
    }

    @Transactional
    public void deleteBrand(Long id) {
        Brand brand = findOwnedBrand(id);
        long productCount = productRepository.countByBrandIdAndDeletedAtIsNull(id);
        if (productCount > 0) {
            throw new BusinessException("BRAND_IN_USE",
                    "This brand is used by " + productCount + " product(s) and cannot be deleted.");
        }
        brand.setDeletedAt(java.time.Instant.now());
        brandRepository.save(brand);
        auditLogService.record("Marca eliminada", "Marca", String.valueOf(id), brand.getName(), null, null);
    }

    @Transactional(readOnly = true)
    public List<UnitDto> getUnits() {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return unitOfMeasureRepository.findByCompanyIdAndDeletedAtIsNull(companyId).stream()
                .map(this::toUnitDto)
                .toList();
    }

    @Transactional
    public UnitDto createUnit(UnitDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Company company = companyRepository.findById(companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Company", companyId));

        String code = request.getCode().trim().toUpperCase();
        unitOfMeasureRepository.findByCompanyIdAndCodeIgnoreCaseAndDeletedAtIsNull(companyId, code)
                .ifPresent(existing -> {
                    throw new BusinessException("UNIT_CODE_EXISTS", "A unit with code '" + code + "' already exists.");
                });

        UnitOfMeasure unit = UnitOfMeasure.builder()
                .company(company)
                .code(code)
                .name(request.getName().trim())
                .symbol(code.length() > 10 ? code.substring(0, 10) : code)
                .isActive(true)
                .build();

        UnitOfMeasure saved = unitOfMeasureRepository.save(unit);
        auditLogService.record("Unidad creada", "Unidad", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toUnitDto(saved);
    }

    @Transactional
    public UnitDto updateUnit(Long id, UnitDto request) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        UnitOfMeasure unit = findOwnedUnit(id);

        if (request.getCode() != null && !request.getCode().isBlank()) {
            String code = request.getCode().trim().toUpperCase();
            if (!code.equalsIgnoreCase(unit.getCode())) {
                unitOfMeasureRepository.findByCompanyIdAndCodeIgnoreCaseAndDeletedAtIsNull(companyId, code)
                        .ifPresent(existing -> {
                            throw new BusinessException("UNIT_CODE_EXISTS", "A unit with code '" + code + "' already exists.");
                        });
                unit.setCode(code);
            }
        }
        if (request.getName() != null && !request.getName().isBlank()) {
            unit.setName(request.getName().trim());
        }

        UnitOfMeasure saved = unitOfMeasureRepository.save(unit);
        auditLogService.record("Unidad actualizada", "Unidad", String.valueOf(saved.getId()), saved.getName(), null, null);
        return toUnitDto(saved);
    }

    @Transactional
    public void deleteUnit(Long id) {
        UnitOfMeasure unit = findOwnedUnit(id);
        long productCount = productRepository.countByUnitIdAndDeletedAtIsNull(id);
        if (productCount > 0) {
            throw new BusinessException("UNIT_IN_USE",
                    "This unit is used by " + productCount + " product(s) and cannot be deleted.");
        }
        unit.setDeletedAt(java.time.Instant.now());
        unitOfMeasureRepository.save(unit);
        auditLogService.record("Unidad eliminada", "Unidad", String.valueOf(id), unit.getName(), null, null);
    }

    /** 404s (not 403) on a category belonging to another company — same treatment as "doesn't exist". */
    private Category findOwnedCategory(Long id) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return categoryRepository.findByIdAndCompanyIdAndDeletedAtIsNull(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Category", id));
    }

    /** 404s (not 403) on a brand belonging to another company — same treatment as "doesn't exist". */
    private Brand findOwnedBrand(Long id) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return brandRepository.findByIdAndCompanyIdAndDeletedAtIsNull(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("Brand", id));
    }

    /** 404s (not 403) on a unit belonging to another company — same treatment as "doesn't exist". */
    private UnitOfMeasure findOwnedUnit(Long id) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        return unitOfMeasureRepository.findByIdAndCompanyIdAndDeletedAtIsNull(id, companyId)
                .orElseThrow(() -> new ResourceNotFoundException("UnitOfMeasure", id));
    }

    /** Derives a short, unique, DB-safe code from a display name — e.g. "Bebidas Frías" → "BEBIDAS-FRIAS",
     *  with a numeric suffix appended if that collides. The category form only ever collects a name;
     *  `code` exists purely for the DB's uniqueness/lookup convenience. */
    private String generateUniqueCode(String name, java.util.function.Predicate<String> codeExists) {
        String base = name.trim().toUpperCase()
                .replaceAll("[^A-Z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
        if (base.isBlank()) {
            base = "CAT";
        }
        if (base.length() > 40) {
            base = base.substring(0, 40);
        }
        String candidate = base;
        int suffix = 2;
        while (codeExists.test(candidate)) {
            candidate = base + "-" + suffix++;
        }
        return candidate;
    }

    private CategoryDto toCategoryDto(Category c) {
        return CategoryDto.builder()
                .id(c.getId())
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .code(c.getCode())
                .name(c.getName())
                .description(c.getDescription())
                .isActive(Boolean.TRUE.equals(c.getIsActive()))
                .productCount(productRepository.countByCategoryIdAndDeletedAtIsNull(c.getId()))
                .build();
    }

    private BrandDto toBrandDto(Brand b) {
        return BrandDto.builder()
                .id(b.getId())
                .name(b.getName())
                .description(b.getDescription())
                .isActive(Boolean.TRUE.equals(b.getIsActive()))
                .productCount(productRepository.countByBrandIdAndDeletedAtIsNull(b.getId()))
                .build();
    }

    private UnitDto toUnitDto(UnitOfMeasure u) {
        return UnitDto.builder()
                .id(u.getId())
                .code(u.getCode())
                .name(u.getName())
                .symbol(u.getSymbol())
                .isActive(Boolean.TRUE.equals(u.getIsActive()))
                .productCount(productRepository.countByUnitIdAndDeletedAtIsNull(u.getId()))
                .build();
    }

    public ProductDto toDto(Product p) {
        BigDecimal totalStock = stockLevelRepository != null ? stockLevelRepository.getTotalAvailableStockByProductId(p.getId()) : BigDecimal.ZERO;
        if (totalStock == null) totalStock = BigDecimal.ZERO;
        boolean active = Boolean.TRUE.equals(p.getIsActive());
        String status = active ? "active" : "archived";
        BigDecimal minStockAlert = p.getMinStockAlert() != null ? p.getMinStockAlert() : BigDecimal.ZERO;
        boolean isLowStock = totalStock.compareTo(BigDecimal.ZERO) > 0 && totalStock.compareTo(minStockAlert) <= 0;
        String stockStatus = totalStock.compareTo(BigDecimal.ZERO) <= 0
                ? "out-of-stock"
                : (isLowStock ? "low-stock" : "in-stock");

        java.util.List<ProductDto.BranchStockDto> stockByBranch = stockLevelRepository != null
                ? stockLevelRepository.getBranchStockByProductId(p.getId()).stream()
                        .map(row -> ProductDto.BranchStockDto.builder()
                                .branchId(row[0] != null ? ((Number) row[0]).longValue() : null)
                                .branchCode((String) row[1])
                                .branchName((String) row[2])
                                .quantity(row[3] != null ? new BigDecimal(row[3].toString()) : BigDecimal.ZERO)
                                .build())
                        .toList()
                : java.util.List.of();

        return ProductDto.builder()
                .id(p.getId())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .brandId(p.getBrand() != null ? p.getBrand().getId() : null)
                .brandName(p.getBrand() != null ? p.getBrand().getName() : null)
                .unitId(p.getUnit() != null ? p.getUnit().getId() : null)
                .unitCode(p.getUnit() != null ? p.getUnit().getCode() : null)
                .unitName(p.getUnit() != null ? p.getUnit().getName() : null)
                .taxId(p.getTax() != null ? p.getTax().getId() : null)
                .taxRate(p.getTax() != null ? p.getTax().getRate() : BigDecimal.ZERO)
                .sku(p.getSku())
                .barcode(p.getBarcode())
                .name(p.getName())
                .description(p.getDescription())
                .costPrice(p.getCostPrice())
                .purchasePrice(p.getCostPrice())
                .lastPurchaseCost(p.getLastPurchaseCost() != null ? p.getLastPurchaseCost() : p.getCostPrice())
                .sellingPrice(p.getSellingPrice())
                .salePrice(p.getSellingPrice())
                .minStockAlert(p.getMinStockAlert())
                .totalAvailableStock(totalStock)
                .totalStock(totalStock)
                .hasVariants(Boolean.TRUE.equals(p.getHasVariants()))
                .imageUrl(p.getImageUrl())
                .isActive(active)
                .status(status)
                .stockStatus(stockStatus)
                .stockByBranch(stockByBranch)
                .createdAt(p.getCreatedAt())
                .build();
    }
}
