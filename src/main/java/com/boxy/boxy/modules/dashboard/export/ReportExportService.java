package com.boxy.boxy.modules.dashboard.export;

import com.boxy.boxy.core.exception.BusinessException;
import com.boxy.boxy.core.pdf.PdfDocumentService;
import com.boxy.boxy.modules.dashboard.dto.ReportExportRequest;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormat;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Lays a report table out as an .xlsx workbook or a PDF. */
@Service
@RequiredArgsConstructor
public class ReportExportService {

    private static final int MAX_SHEET_NAME = 31;
    private final PdfDocumentService pdfDocumentService;

    public byte[] toExcel(ReportExportRequest request) {
        validate(request);
        try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = workbook.createSheet(sheetName(request.getTitle()));

            Font bold = workbook.createFont();
            bold.setBold(true);
            CellStyle headerStyle = workbook.createCellStyle();
            headerStyle.setFont(bold);
            headerStyle.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);

            int rowIndex = 0;
            Cell title = sheet.createRow(rowIndex++).createCell(0);
            title.setCellValue(request.getTitle());
            title.setCellStyle(headerStyle);
            if (request.getSubtitle() != null && !request.getSubtitle().isBlank()) {
                sheet.createRow(rowIndex++).createCell(0).setCellValue(request.getSubtitle());
            }
            sheet.createRow(rowIndex++).createCell(0).setCellValue("Generado: " + now());
            rowIndex++;

            DataFormat dataFormat = workbook.createDataFormat();
            List<CellStyle> columnStyles = new ArrayList<>();
            for (ReportExportRequest.Column column : request.getColumns()) {
                CellStyle style = workbook.createCellStyle();
                switch (kind(column)) {
                    case "currency" -> style.setDataFormat(dataFormat.getFormat("\"Bs\" #,##0.00"));
                    case "percent" -> style.setDataFormat(dataFormat.getFormat("0.0%"));
                    case "number" -> style.setDataFormat(dataFormat.getFormat("#,##0.##"));
                    default -> { }
                }
                columnStyles.add(style);
            }

            Row header = sheet.createRow(rowIndex++);
            for (int c = 0; c < request.getColumns().size(); c++) {
                Cell cell = header.createCell(c);
                cell.setCellValue(request.getColumns().get(c).getHeader());
                cell.setCellStyle(headerStyle);
            }
            for (List<Object> row : request.getRows()) {
                writeRow(sheet.createRow(rowIndex++), row, columnStyles);
            }
            if (request.getFooter() != null) {
                writeRow(sheet.createRow(rowIndex), request.getFooter(), columnStyles);
            }
            for (int c = 0; c < request.getColumns().size(); c++) {
                sheet.autoSizeColumn(c);
            }
            sheet.createFreezePane(0, header.getRowNum() + 1);

            workbook.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("No se pudo generar el archivo de Excel", e);
        }
    }

    public byte[] toPdf(ReportExportRequest request, String companyName) {
        validate(request);
        List<String> kinds = request.getColumns().stream().map(this::kind).toList();
        List<List<String>> rows = request.getRows().stream().map(r -> formatRow(r, kinds)).toList();
        Map<String, Object> model = new HashMap<>();
        model.put("company", companyName == null ? "" : companyName);
        model.put("title", request.getTitle());
        model.put("subtitle", request.getSubtitle());
        model.put("generatedAt", now());
        model.put("headers", request.getColumns().stream().map(ReportExportRequest.Column::getHeader).toList());
        model.put("numeric", kinds.stream().map(k -> !"text".equals(k)).toList());
        model.put("rows", rows);
        model.put("footer", request.getFooter() == null ? null : formatRow(request.getFooter(), kinds));
        model.put("landscape", request.getColumns().size() > 6);
        return pdfDocumentService.render("report-table", model);
    }

    private void validate(ReportExportRequest request) {
        int columns = request.getColumns().size();
        for (List<Object> row : request.getRows()) {
            if (row.size() != columns) {
                throw new BusinessException("INVALID_EXPORT", "Cada fila debe tener una celda por columna.");
            }
        }
        if (request.getFooter() != null && request.getFooter().size() != columns) {
            throw new BusinessException("INVALID_EXPORT", "La fila de totales debe tener una celda por columna.");
        }
    }

    private void writeRow(Row row, List<Object> values, List<CellStyle> styles) {
        for (int c = 0; c < values.size(); c++) {
            Cell cell = row.createCell(c);
            Object value = values.get(c);
            if (value instanceof Number number) {
                cell.setCellValue(number.doubleValue());
                cell.setCellStyle(styles.get(c));
            } else if (value != null) {
                // setCellValue(String) stores text, never a formula — a product named "=1+1" stays a name.
                cell.setCellValue(String.valueOf(value));
            }
        }
    }

    private List<String> formatRow(List<Object> row, List<String> kinds) {
        List<String> out = new ArrayList<>();
        for (int c = 0; c < row.size(); c++) {
            out.add(format(row.get(c), kinds.get(c)));
        }
        return out;
    }

    private String format(Object value, String kind) {
        if (value == null) {
            return "";
        }
        if (!(value instanceof Number number)) {
            return String.valueOf(value);
        }
        DecimalFormatSymbols symbols = DecimalFormatSymbols.getInstance(Locale.forLanguageTag("es-BO"));
        return switch (kind) {
            case "currency" -> "Bs " + new DecimalFormat("#,##0.00", symbols).format(number.doubleValue());
            case "percent" -> new DecimalFormat("0.0", symbols).format(number.doubleValue() * 100) + " %";
            default -> new DecimalFormat("#,##0.##", symbols).format(number.doubleValue());
        };
    }

    private String kind(ReportExportRequest.Column column) {
        String kind = column.getKind() == null ? "text" : column.getKind();
        return switch (kind) {
            case "number", "currency", "percent" -> kind;
            default -> "text";
        };
    }

    private String sheetName(String title) {
        String cleaned = title.replaceAll("[\\\\/?*\\[\\]:]", " ").trim();
        return cleaned.length() > MAX_SHEET_NAME ? cleaned.substring(0, MAX_SHEET_NAME) : cleaned;
    }

    private String now() {
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
    }
}
