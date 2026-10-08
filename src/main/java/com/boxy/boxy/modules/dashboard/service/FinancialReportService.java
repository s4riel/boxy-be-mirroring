package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The financial reports, computed from the real invoices (voided ones left out) and purchase orders,
 * for the period and branches the screen asks for. Money is "what was billed": sales include tax,
 * "utilidad bruta" is the sale net of tax minus the cost recorded at the moment of sale.
 * (Payment methods and accounts receivable live in {@link SalesReportService}.)
 */
@Service
public class FinancialReportService {

    /** Invoices are stored in UTC; the company's days are Bolivia time (UTC-4, no DST). */
    private static final String LOCAL_OFFSET = "-04:00";
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"));

    /** Purchase orders that are an actual commitment to buy (not drafts, not cancelled). */
    private static final String PURCHASE_STATUSES = "('ISSUED', 'APPROVED', 'PARTIALLY_RECEIVED', 'COMPLETED')";

    private static final String INVOICE_SCOPE =
            "i.company.id = :companyId AND i.status <> 'VOIDED' AND (:all = true OR i.branch.id IN :branchIds) " +
            "AND i.createdAt >= :from AND i.createdAt < :to";

    @PersistenceContext
    private EntityManager em;

    private <T> jakarta.persistence.TypedQuery<T> scoped(String jpql, Class<T> type, ReportFilter filter) {
        return em.createQuery(jpql, type)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive());
    }

    // ---------------------------------------------------------------- summary

    @Transactional(readOnly = true)
    public Map<String, Object> summary(ReportFilter filter) {
        Object[] invoices = scoped(
                "SELECT COALESCE(SUM(i.totalAmount), 0), COALESCE(SUM(i.taxAmount), 0), COALESCE(SUM(i.discountAmount), 0) " +
                "FROM Invoice i WHERE " + INVOICE_SCOPE, Object[].class, filter).getSingleResult();
        Object[] lines = scoped(
                "SELECT COALESCE(SUM(it.totalAmount - it.taxAmount), 0), COALESCE(SUM(it.quantity * it.unitCost), 0) " +
                "FROM InvoiceItem it JOIN it.invoice i WHERE " + INVOICE_SCOPE, Object[].class, filter).getSingleResult();

        BigDecimal sales = (BigDecimal) invoices[0];
        BigDecimal tax = (BigDecimal) invoices[1];
        BigDecimal discounts = (BigDecimal) invoices[2];
        BigDecimal netSales = (BigDecimal) lines[0];
        BigDecimal cogs = (BigDecimal) lines[1];

        return Map.of("kpis", List.of(
                kpi("fin-rev", "Ventas del periodo (con impuestos)", sales.doubleValue(), "currency", "neutral"),
                kpi("fin-cogs", "Costo de ventas", cogs.doubleValue(), "currency", "neutral"),
                kpi("fin-gross-profit", "Utilidad bruta", netSales.subtract(cogs).doubleValue(), "currency",
                        netSales.subtract(cogs).signum() < 0 ? "negative" : "positive"),
                kpi("fin-tax", "Impuestos incluidos en las ventas", tax.doubleValue(), "currency", "neutral"),
                kpi("fin-discounts", "Descuentos otorgados", discounts.doubleValue(), "currency", "neutral")));
    }

    // ---------------------------------------------------------------- revenue

    @Transactional(readOnly = true)
    public Map<String, Object> revenue(ReportFilter filter) {
        List<Object[]> daily = scoped(
                "SELECT FUNCTION('DATE', FUNCTION('CONVERT_TZ', i.createdAt, '+00:00', '" + LOCAL_OFFSET + "')), SUM(i.totalAmount) " +
                "FROM Invoice i WHERE " + INVOICE_SCOPE +
                " GROUP BY FUNCTION('DATE', FUNCTION('CONVERT_TZ', i.createdAt, '+00:00', '" + LOCAL_OFFSET + "'))",
                Object[].class, filter).getResultList();
        TreeMap<LocalDate, Double> byBucket = emptyBuckets(filter);
        for (Object[] r : daily) {
            byBucket.merge(filter.bucketStart(((Date) r[0]).toLocalDate()), ((BigDecimal) r[1]).doubleValue(), Double::sum);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        byBucket.forEach((bucket, value) -> points.add(Map.of("label", bucketLabel(filter, bucket), "value", value)));

        List<Object[]> categories = scoped(
                "SELECT c.id, c.name, SUM(it.totalAmount) FROM InvoiceItem it JOIN it.invoice i JOIN it.product p " +
                "LEFT JOIN p.category c WHERE " + INVOICE_SCOPE + " GROUP BY c.id, c.name ORDER BY SUM(it.totalAmount) DESC",
                Object[].class, filter).getResultList();
        BigDecimal total = categories.stream().map(r -> (BigDecimal) r[2]).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> byCategory = new ArrayList<>();
        for (Object[] r : categories) {
            BigDecimal revenue = (BigDecimal) r[2];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("categoryId", r[0] == null ? "0" : String.valueOf(r[0]));
            row.put("categoryName", r[1] == null ? "Sin categoría" : r[1]);
            row.put("revenue", revenue.doubleValue());
            row.put("revenueShare", total.signum() > 0 ? revenue.divide(total, 4, RoundingMode.HALF_UP).doubleValue() : 0.0);
            byCategory.add(row);
        }
        return Map.of("trend", Map.of("id", "fin-revenue-trend", "name", "Ventas", "points", points), "byCategory", byCategory);
    }

    // ---------------------------------------------------------------- taxes and discounts

    @Transactional(readOnly = true)
    public Map<String, Object> taxesDiscounts(ReportFilter filter) {
        // The rate is not stored on the line; it follows from the tax inside the price:
        // tax = total − total / (1 + rate)  →  rate = tax / (total − tax).
        List<Object[]> rates = scoped(
                "SELECT FUNCTION('ROUND', it.taxAmount / (it.totalAmount - it.taxAmount), 2), SUM(it.taxAmount) " +
                "FROM InvoiceItem it JOIN it.invoice i WHERE " + INVOICE_SCOPE + " AND it.totalAmount > it.taxAmount " +
                "GROUP BY FUNCTION('ROUND', it.taxAmount / (it.totalAmount - it.taxAmount), 2) " +
                "ORDER BY FUNCTION('ROUND', it.taxAmount / (it.totalAmount - it.taxAmount), 2) DESC",
                Object[].class, filter).getResultList();
        List<Map<String, Object>> byRate = new ArrayList<>();
        for (Object[] r : rates) {
            double rate = ((Number) r[0]).doubleValue();
            String label = rate == 0 ? "Exento (0 %)" : "Impuesto " + new java.text.DecimalFormat("0.##").format(rate * 100) + " %";
            byRate.add(Map.of("rateLabel", label, "rate", rate, "taxCollected", ((BigDecimal) r[1]).doubleValue()));
        }

        List<Object[]> byBranch = scoped(
                "SELECT b.name, SUM(i.discountAmount) FROM Invoice i JOIN i.branch b WHERE " + INVOICE_SCOPE +
                " GROUP BY b.name HAVING SUM(i.discountAmount) > 0 ORDER BY SUM(i.discountAmount) DESC",
                Object[].class, filter).getResultList();
        List<Map<String, Object>> branchPoints = byBranch.stream()
                .map(r -> Map.<String, Object>of("label", r[0], "value", ((BigDecimal) r[1]).doubleValue())).toList();

        List<Object[]> byEmployee = scoped(
                "SELECT u.id, u.firstName, u.lastName, u.username, SUM(i.discountAmount), COUNT(i) " +
                "FROM Invoice i LEFT JOIN i.createdBy u WHERE " + INVOICE_SCOPE + " AND i.discountAmount > 0 " +
                "GROUP BY u.id, u.firstName, u.lastName, u.username ORDER BY SUM(i.discountAmount) DESC",
                Object[].class, filter).getResultList();
        List<Map<String, Object>> employees = new ArrayList<>();
        for (Object[] r : byEmployee) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", r[0] == null ? "0" : String.valueOf(r[0]));
            row.put("employeeName", r[1] == null ? "Sin vendedor" : (r[1] + " " + r[2]).trim());
            row.put("discountTotal", ((BigDecimal) r[4]).doubleValue());
            row.put("saleCount", r[5]);
            employees.add(row);
        }

        return Map.of("byRate", byRate,
                "discountsByBranchChart", Map.of("id", "discounts-by-branch", "name", "Descuentos", "points", branchPoints),
                "discountsByEmployee", employees);
    }

    // ---------------------------------------------------------------- purchase spend

    @Transactional(readOnly = true)
    public Map<String, Object> purchaseSpend(ReportFilter filter) {
        String scope = "po.company.id = :companyId AND po.status IN " + PURCHASE_STATUSES +
                " AND (:all = true OR po.branch.id IN :branchIds) AND po.issueDate >= :from AND po.issueDate <= :to";
        Long companyId = SecurityUtils.requireCurrentCompanyId();

        List<Object[]> orders = em.createQuery(
                        "SELECT po.issueDate, po.totalAmount FROM PurchaseOrder po WHERE " + scope, Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.from())
                .setParameter("to", filter.to())
                .getResultList();
        TreeMap<LocalDate, Double> byBucket = emptyBuckets(filter);
        for (Object[] r : orders) {
            byBucket.merge(filter.bucketStart((LocalDate) r[0]), ((BigDecimal) r[1]).doubleValue(), Double::sum);
        }
        List<Map<String, Object>> points = new ArrayList<>();
        byBucket.forEach((bucket, value) -> points.add(Map.of("label", bucketLabel(filter, bucket), "value", value)));

        List<Object[]> suppliers = em.createQuery(
                        "SELECT s.id, s.name, COUNT(po), SUM(po.totalAmount) FROM PurchaseOrder po JOIN po.supplier s WHERE " + scope +
                        " GROUP BY s.id, s.name ORDER BY SUM(po.totalAmount) DESC", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.from())
                .setParameter("to", filter.to())
                .getResultList();
        List<Map<String, Object>> bySupplier = new ArrayList<>();
        for (Object[] r : suppliers) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("supplierId", String.valueOf(r[0]));
            row.put("supplierName", r[1]);
            row.put("orderCount", r[2]);
            row.put("totalSpend", ((BigDecimal) r[3]).doubleValue());
            bySupplier.add(row);
        }

        return Map.of("trend", Map.of("id", "spend-trend", "name", "Compras", "points", points), "bySupplier", bySupplier);
    }

    // ---------------------------------------------------------------- cash closings

    /**
     * One row per cash register opened in the period (still open ones included): who ran it, what it
     * was opened with, what it sold, what it should hold, what was counted and the difference
     * (counted − expected: negative is a shortage, positive a surplus).
     */
    @Transactional(readOnly = true)
    public Map<String, Object> cashClosings(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        String scope = "b.company.id = :companyId AND (:all = true OR b.id IN :branchIds) " +
                "AND cs.openedAt >= :from AND cs.openedAt < :to";

        List<Object[]> sessions = em.createQuery(
                        "SELECT cs.id, b.name, u.firstName, u.lastName, cs.openedAt, cs.closedAt, cs.status, " +
                        "cs.initialCash, cs.expectedCash, cs.actualCash, cs.difference " +
                        "FROM CashierSession cs JOIN cs.branch b JOIN cs.user u WHERE " + scope +
                        " ORDER BY cs.openedAt DESC", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .getResultList();

        // What each register sold (voided sales left out).
        Map<Long, Object[]> sold = new java.util.HashMap<>();
        for (Object[] r : em.createQuery(
                        "SELECT cs.id, COUNT(i), COALESCE(SUM(i.totalAmount), 0) FROM Invoice i JOIN i.cashierSession cs JOIN cs.branch b " +
                        "WHERE i.status <> 'VOIDED' AND " + scope + " GROUP BY cs.id", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .getResultList()) {
            sold.put((Long) r[0], r);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        long closed = 0;
        long withDifference = 0;
        BigDecimal shortage = BigDecimal.ZERO;
        BigDecimal surplus = BigDecimal.ZERO;
        for (Object[] r : sessions) {
            boolean isClosed = "CLOSED".equalsIgnoreCase((String) r[6]);
            BigDecimal difference = (BigDecimal) r[10];
            String result = !isClosed ? "open"
                    : difference == null || difference.signum() == 0 ? "balanced"
                    : difference.signum() < 0 ? "short" : "over";
            if (isClosed) {
                closed++;
                if (difference != null && difference.signum() != 0) {
                    withDifference++;
                    if (difference.signum() < 0) {
                        shortage = shortage.add(difference.abs());
                    } else {
                        surplus = surplus.add(difference);
                    }
                }
            }
            Object[] s = sold.get((Long) r[0]);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sessionId", String.valueOf(r[0]));
            row.put("branchName", r[1]);
            row.put("cashierName", (r[2] + " " + r[3]).trim());
            row.put("openedAt", ((java.time.Instant) r[4]).toString());
            row.put("closedAt", r[5] == null ? null : ((java.time.Instant) r[5]).toString());
            row.put("status", isClosed ? "closed" : "open");
            row.put("initialCash", ((BigDecimal) r[7]).doubleValue());
            row.put("salesCount", s == null ? 0L : s[1]);
            row.put("salesTotal", s == null ? 0.0 : ((BigDecimal) s[2]).doubleValue());
            row.put("expectedCash", ((BigDecimal) r[8]).doubleValue());
            row.put("actualCash", r[9] == null ? null : ((BigDecimal) r[9]).doubleValue());
            row.put("difference", difference == null ? null : difference.doubleValue());
            row.put("result", result);
            rows.add(row);
        }

        return Map.of("rows", rows, "kpis", List.of(
                kpi("cc-closed", "Cierres realizados", closed, "number", "neutral"),
                kpi("cc-with-difference", "Cierres con diferencia", withDifference, "number", withDifference > 0 ? "negative" : "neutral"),
                kpi("cc-shortage", "Faltante total", shortage.doubleValue(), "currency", shortage.signum() > 0 ? "negative" : "neutral"),
                kpi("cc-surplus", "Sobrante total", surplus.doubleValue(), "currency", "neutral")));
    }

    // ---------------------------------------------------------------- helpers

    private static TreeMap<LocalDate, Double> emptyBuckets(ReportFilter filter) {
        TreeMap<LocalDate, Double> buckets = new TreeMap<>();
        for (LocalDate d = filter.bucketStart(filter.from()); !d.isAfter(filter.to()); d = nextBucket(filter, d)) {
            buckets.put(d, 0.0);
        }
        return buckets;
    }

    private static LocalDate nextBucket(ReportFilter filter, LocalDate bucket) {
        return switch (filter.granularity()) {
            case "week" -> bucket.plusWeeks(1);
            case "month" -> bucket.plusMonths(1);
            default -> bucket.plusDays(1);
        };
    }

    private static String bucketLabel(ReportFilter filter, LocalDate bucket) {
        return "month".equals(filter.granularity()) ? MONTH_LABEL.format(bucket) : DAY_LABEL.format(bucket);
    }

    private static Map<String, Object> kpi(String id, String label, double value, String format, String intent) {
        return Map.of("id", id, "label", label, "value", value, "format", format, "intent", intent);
    }
}
