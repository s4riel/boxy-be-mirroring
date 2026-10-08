package com.boxy.boxy.modules.dashboard.controller;

import com.boxy.boxy.core.response.ApiResponse;
import com.boxy.boxy.modules.dashboard.service.PageParams;
import com.boxy.boxy.modules.dashboard.service.ReportFilter;
import com.boxy.boxy.modules.dashboard.service.ReportService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports & Business Intelligence", description = "Endpoints for detailed reporting and analytics across sales, inventory, and finance")
// Every report in this controller needs the same read-only permission — one class-level
// check instead of repeating it on all 23 endpoints (all of them GET, all reporting data).
@PreAuthorize("hasAuthority('reports:view') or hasAuthority('ROLE_SUPER_ADMIN')")
public class ReportController {

    private final ReportService reportService;
    private final com.boxy.boxy.modules.dashboard.service.InventoryReportService inventoryReportService;
    private final com.boxy.boxy.modules.dashboard.service.SalesReportService salesReportService;
    private final com.boxy.boxy.modules.dashboard.service.FinancialReportService financialReportService;
    private final com.boxy.boxy.modules.dashboard.service.AnalyticsReportService analyticsReportService;
    private final com.boxy.boxy.modules.dashboard.export.ReportExportService reportExportService;

    // --- EXPORT ---

    @org.springframework.web.bind.annotation.PostMapping("/export")
    @Operation(summary = "Export a report table as an Excel (.xlsx) or PDF file")
    public ResponseEntity<byte[]> export(
            @jakarta.validation.Valid @org.springframework.web.bind.annotation.RequestBody
            com.boxy.boxy.modules.dashboard.dto.ReportExportRequest request,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "excel") String format) {
        boolean pdf = "pdf".equalsIgnoreCase(format);
        byte[] body = pdf
                ? reportExportService.toPdf(request, reportService.currentCompanyName())
                : reportExportService.toExcel(request);
        String filename = request.getTitle().replaceAll("[^\\p{L}\\p{N}]+", "-").toLowerCase() + (pdf ? ".pdf" : ".xlsx");
        return ResponseEntity.ok()
                .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION,
                        org.springframework.http.ContentDisposition.attachment().filename(filename).build().toString())
                .contentType(pdf ? org.springframework.http.MediaType.APPLICATION_PDF
                        : org.springframework.http.MediaType.parseMediaType(
                                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .body(body);
    }

    // --- SALES REPORTS ---

    @GetMapping("/sales/summary")
    @Operation(summary = "Sales summary KPI and trend report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSalesSummary(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(salesReportService.summary(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/sales/by-product")
    @Operation(summary = "Sales breakdown by product")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSalesByProduct(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity,
            PageParams paging) {
        return ResponseEntity.ok(ApiResponse.ok(paging.apply(salesReportService.byProduct(filter(preset, from, to, branch, granularity)))));
    }

    @GetMapping("/sales/by-branch")
    @Operation(summary = "Sales breakdown by branch")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSalesByBranch(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(salesReportService.byBranch(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/sales/by-employee")
    @Operation(summary = "Sales breakdown by employee/cashier")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getSalesByEmployee(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(salesReportService.byEmployee(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/sales/quotation-funnel")
    @Operation(summary = "Quotation conversion funnel report")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getQuotationFunnel(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(salesReportService.quotationFunnel(filter(preset, from, to, branch, granularity))));
    }

    // --- INVENTORY REPORTS ---
    // Real figures from stock levels, movements, adjustments and transfers, limited to the period
    // and branches the screen sends: preset (or custom from/to), branch (comma-separated ids), granularity.

    private static ReportFilter filter(String preset, String from, String to, String branch, String granularity) {
        return ReportFilter.of(preset, from, to, branch, granularity);
    }

    @GetMapping("/inventory/summary")
    @Operation(summary = "Inventory valuation and stock health KPI summary")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getInventorySummary(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryReportService.summary(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/inventory/critical-stock")
    @Operation(summary = "Products with critical or out-of-stock levels")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCriticalStock(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity,
            PageParams paging) {
        return ResponseEntity.ok(ApiResponse.ok(paging.apply(inventoryReportService.criticalStock(filter(preset, from, to, branch, granularity)))));
    }

    @GetMapping("/inventory/stock-movements")
    @Operation(summary = "Aggregated stock movements trend")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStockMovements(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryReportService.stockMovements(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/inventory/adjustment-activity")
    @Operation(summary = "Stock adjustment activity breakdown by reason")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getAdjustmentActivity(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryReportService.adjustmentActivity(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/inventory/transfer-status")
    @Operation(summary = "Warehouse transfer status KPIs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTransferStatus(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(inventoryReportService.transferStatus(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/inventory/stale")
    @Operation(summary = "Stale products with no recent movement")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getStaleProducts(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity,
            PageParams paging) {
        return ResponseEntity.ok(ApiResponse.ok(paging.apply(inventoryReportService.stale(filter(preset, from, to, branch, granularity)))));
    }

    // --- FINANCIAL REPORTS ---

    @GetMapping("/financial/summary")
    @Operation(summary = "Financial overview and gross profit KPIs")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getFinancialSummary(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(financialReportService.summary(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/financial/revenue")
    @Operation(summary = "Revenue trends and category breakdown")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRevenue(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(financialReportService.revenue(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/financial/taxes-discounts")
    @Operation(summary = "Taxes collected and discounts applied")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTaxesDiscounts(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(financialReportService.taxesDiscounts(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/financial/payment-methods")
    @Operation(summary = "Revenue breakdown by payment method")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPaymentMethods(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(salesReportService.paymentMethods(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/financial/credit")
    @Operation(summary = "Customer credit exposure and balances")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCredit(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity,
            PageParams paging) {
        return ResponseEntity.ok(ApiResponse.ok(paging.apply(salesReportService.receivables(filter(preset, from, to, branch, granularity)))));
    }

    @GetMapping("/financial/cash-closings")
    @Operation(summary = "Cash register closings: expected vs counted cash and the difference, per register")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getCashClosings(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity,
            PageParams paging) {
        return ResponseEntity.ok(ApiResponse.ok(paging.apply(financialReportService.cashClosings(filter(preset, from, to, branch, granularity)))));
    }

    @GetMapping("/financial/purchase-spend")
    @Operation(summary = "Purchase spend trend and supplier breakdown")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPurchaseSpend(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(financialReportService.purchaseSpend(filter(preset, from, to, branch, granularity))));
    }

    // --- ANALYTICS REPORTS ---

    @GetMapping("/analytics/trends")
    @Operation(summary = "Multi-metric business trends")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getTrends(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsReportService.trends(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/analytics/branch-comparison")
    @Operation(summary = "Comparative performance metrics across branches")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getBranchComparison(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsReportService.branchComparison(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/analytics/period-comparison")
    @Operation(summary = "Period-over-period comparative analysis")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getPeriodComparison(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsReportService.periodComparison(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/analytics/rankings")
    @Operation(summary = "Top rankings across products, categories, customers, and suppliers")
    public ResponseEntity<ApiResponse<Map<String, Object>>> getRankings(
            @RequestParam(required = false) String preset, @RequestParam(required = false) String from,
            @RequestParam(required = false) String to, @RequestParam(required = false) String branch,
            @RequestParam(required = false) String granularity) {
        return ResponseEntity.ok(ApiResponse.ok(analyticsReportService.rankings(filter(preset, from, to, branch, granularity))));
    }

    @GetMapping("/branches")
    @Operation(summary = "List branches for reporting filter")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> getReportBranches() {
        return ResponseEntity.ok(ApiResponse.ok(reportService.getReportBranches()));
    }
}
