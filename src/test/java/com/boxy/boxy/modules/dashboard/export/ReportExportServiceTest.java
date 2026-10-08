package com.boxy.boxy.modules.dashboard.export;

import com.boxy.boxy.core.exception.BusinessException;
import com.boxy.boxy.core.pdf.PdfDocumentService;
import com.boxy.boxy.modules.dashboard.dto.ReportExportRequest;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Builds real .xlsx / PDF bytes from a report table — the layouts only fail when actually rendered. */
class ReportExportServiceTest {

    private final ReportExportService service = new ReportExportService(new PdfDocumentService(templateEngine()));

    private static SpringTemplateEngine templateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding("UTF-8");
        resolver.setCacheable(false);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    private static ReportExportRequest request() {
        return ReportExportRequest.builder()
                .title("Stock crítico")
                .subtitle("Últimos 30 días · Todas las sucursales")
                .columns(List.of(
                        new ReportExportRequest.Column("SKU", "text"),
                        new ReportExportRequest.Column("Producto", "text"),
                        new ReportExportRequest.Column("Existencia", "number"),
                        new ReportExportRequest.Column("Valor", "currency")))
                .rows(List.of(
                        Arrays.<Object>asList("A-1", "=1+1 & <b>Martillo</b>", 4, 120.5),
                        Arrays.<Object>asList("A-2", "Taladro", 0, 0)))
                .footer(Arrays.<Object>asList("Total", "", 4, 120.5))
                .build();
    }

    @Test
    void excelKeepsNumbersNumericAndTextAsText() throws Exception {
        byte[] bytes = service.toExcel(request());

        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            Sheet sheet = workbook.getSheetAt(0);
            // rows: title, subtitle, generated, blank, header, data...
            assertThat(sheet.getRow(4).getCell(1).getStringCellValue()).isEqualTo("Producto");
            assertThat(sheet.getRow(5).getCell(2).getCellType()).isEqualTo(CellType.NUMERIC);
            assertThat(sheet.getRow(5).getCell(3).getNumericCellValue()).isEqualTo(120.5);
            // A leading "=" is stored as text, never evaluated as a formula.
            assertThat(sheet.getRow(5).getCell(1).getCellType()).isEqualTo(CellType.STRING);
            assertThat(sheet.getRow(5).getCell(1).getStringCellValue()).startsWith("=1+1");
            assertThat(sheet.getRow(7).getCell(0).getStringCellValue()).isEqualTo("Total");
        }
    }

    @Test
    void pdfRendersEvenWithMarkupInTheData() {
        byte[] bytes = service.toPdf(request(), "Acme");

        assertThat(new String(bytes, 0, 5)).isEqualTo("%PDF-");
    }

    @Test
    void aRowWithTheWrongNumberOfCellsIsRejected() {
        ReportExportRequest bad = ReportExportRequest.builder()
                .title("X")
                .columns(List.of(new ReportExportRequest.Column("A", "text"), new ReportExportRequest.Column("B", "text")))
                .rows(List.of(Arrays.<Object>asList("only one")))
                .build();

        assertThatThrownBy(() -> service.toExcel(bad)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> service.toPdf(bad, "Acme")).isInstanceOf(BusinessException.class);
    }
}
