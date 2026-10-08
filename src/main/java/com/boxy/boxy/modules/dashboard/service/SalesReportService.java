package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.SecurityUtils;
import com.boxy.boxy.modules.sales.entity.Invoice;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * Sales reports computed from the real invoices (voided ones left out), limited to the period and
 * branches the screen asks for. "Revenue" is what was billed (invoice total, tax included);
 * "margin" is measured on the sale net of tax against the unit cost recorded at the moment of sale.
 * Also the two financial reports that read the same data: payment methods and accounts receivable.
 */
@Service
public class SalesReportService {

    /** Invoices are stored in UTC; the company's days are Bolivia time (UTC-4, no DST). */
    private static final String LOCAL_OFFSET = "-04:00";
    private static final int CHART_LIMIT = 10;
    private static final int TABLE_LIMIT = 20000;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"));

    private static final Map<String, String> METHOD_LABELS = Map.of(
            "cash", "Efectivo", "card", "Tarjeta", "transfer", "Transferencia", "credit", "Crédito");

    @PersistenceContext
    private EntityManager em;

    /** The shared WHERE for "billed sales of this company in the period and branches". */
    private static final String INVOICE_SCOPE =
            "i.company.id = :companyId AND i.status <> 'VOIDED' AND (:all = true OR i.branch.id IN :branchIds) " +
            "AND i.createdAt >= :from AND i.createdAt < :to";

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
        Object[] totals = scoped("SELECT COALESCE(SUM(i.totalAmount), 0), COUNT(i) FROM Invoice i WHERE " + INVOICE_SCOPE,
                Object[].class, filter).getSingleResult();
        BigDecimal revenue = (BigDecimal) totals[0];
        long count = (Long) totals[1];
        BigDecimal ticket = count > 0 ? revenue.divide(BigDecimal.valueOf(count), 2, RoundingMode.HALF_UP) : BigDecimal.ZERO;

        Double margin = margin(filter);

        List<Map<String, Object>> kpis = new ArrayList<>();
        kpis.add(kpi("sales-total", "Ventas del periodo", revenue.doubleValue(), "currency"));
        kpis.add(kpi("sales-count", "Número de ventas", count, "number"));
        kpis.add(kpi("sales-margin", "Margen bruto", margin == null ? 0 : margin, "percent"));

        // Revenue per bucket (day / week / month)
        List<Object[]> daily = scoped(
                "SELECT FUNCTION('DATE', FUNCTION('CONVERT_TZ', i.createdAt, '+00:00', '" + LOCAL_OFFSET + "')), SUM(i.totalAmount) " +
                "FROM Invoice i WHERE " + INVOICE_SCOPE +
                " GROUP BY FUNCTION('DATE', FUNCTION('CONVERT_TZ', i.createdAt, '+00:00', '" + LOCAL_OFFSET + "'))",
                Object[].class, filter).getResultList();
        TreeMap<LocalDate, Double> byBucket = new TreeMap<>();
        for (LocalDate d = filter.bucketStart(filter.from()); !d.isAfter(filter.to()); d = nextBucket(filter, d)) {
            byBucket.put(d, 0.0);
        }
        for (Object[] r : daily) {
            byBucket.merge(filter.bucketStart(((Date) r[0]).toLocalDate()), ((BigDecimal) r[1]).doubleValue(), Double::sum);
        }
        List<Map<String, Object>> trend = new ArrayList<>();
        byBucket.forEach((bucket, value) -> trend.add(Map.of("label", bucketLabel(filter, bucket), "value", value)));

        List<Map<String, Object>> top = new ArrayList<>();
        for (Map<String, Object> row : productRows(filter, 5)) {
            top.add(Map.of("label", row.get("name"), "value", row.get("revenue")));
        }

        return Map.of("kpis", kpis,
                "revenueTrend", Map.of("id", "revenue-trend", "name", "Ventas", "points", trend),
                "topProducts", Map.of("id", "top-products", "name", "Más vendidos", "points", top));
    }

    // ---------------------------------------------------------------- by product

    @Transactional(readOnly = true)
    public Map<String, Object> byProduct(ReportFilter filter) {
        List<Map<String, Object>> rows = productRows(filter, TABLE_LIMIT);
        List<Map<String, Object>> points = rows.stream().limit(CHART_LIMIT)
                .map(r -> Map.<String, Object>of("label", r.get("name"), "value", r.get("revenue")))
                .toList();
        // Footer totals cover every product, not just the page on screen.
        double quantity = rows.stream().mapToDouble(r -> (double) r.get("quantity")).sum();
        double revenue = rows.stream().mapToDouble(r -> (double) r.get("revenue")).sum();
        return Map.of("rows", rows, "totals", Map.of("quantity", quantity, "revenue", revenue),
                "chart", Map.of("id", "by-product", "name", "Ventas por producto", "points", points));
    }

    private List<Map<String, Object>> productRows(ReportFilter filter, int limit) {
        List<Object[]> data = scoped(
                "SELECT p.id, p.sku, p.name, c.name, SUM(it.quantity), SUM(it.totalAmount), " +
                "SUM(it.totalAmount - it.taxAmount), SUM(it.quantity * it.unitCost) " +
                "FROM InvoiceItem it JOIN it.invoice i JOIN it.product p LEFT JOIN p.category c WHERE " + INVOICE_SCOPE +
                " GROUP BY p.id, p.sku, p.name, c.name ORDER BY SUM(it.totalAmount) DESC",
                Object[].class, filter).setMaxResults(limit).getResultList();

        BigDecimal total = scoped("SELECT COALESCE(SUM(i.totalAmount), 0) FROM Invoice i WHERE " + INVOICE_SCOPE,
                BigDecimal.class, filter).getSingleResult();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : data) {
            BigDecimal revenue = (BigDecimal) r[5];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", String.valueOf(r[0]));
            row.put("sku", r[1]);
            row.put("name", r[2]);
            row.put("categoryName", r[3] == null ? "Sin categoría" : r[3]);
            row.put("quantity", ((BigDecimal) r[4]).doubleValue());
            row.put("revenue", revenue.doubleValue());
            row.put("revenueShare", fraction(revenue, total));
            row.put("margin", marginOf((BigDecimal) r[6], (BigDecimal) r[7]));
            rows.add(row);
        }
        return rows;
    }

    // ---------------------------------------------------------------- by branch

    @Transactional(readOnly = true)
    public Map<String, Object> byBranch(ReportFilter filter) {
        List<Object[]> data = scoped(
                "SELECT b.id, b.name, COUNT(i), SUM(i.totalAmount) FROM Invoice i JOIN i.branch b WHERE " + INVOICE_SCOPE +
                " GROUP BY b.id, b.name ORDER BY SUM(i.totalAmount) DESC", Object[].class, filter).getResultList();
        Map<Long, Double> margins = new LinkedHashMap<>();
        for (Object[] r : scoped(
                "SELECT i.branch.id, SUM(it.totalAmount - it.taxAmount), SUM(it.quantity * it.unitCost) " +
                "FROM InvoiceItem it JOIN it.invoice i WHERE " + INVOICE_SCOPE + " GROUP BY i.branch.id",
                Object[].class, filter).getResultList()) {
            margins.put((Long) r[0], marginOf((BigDecimal) r[1], (BigDecimal) r[2]));
        }

        BigDecimal total = data.stream().map(r -> (BigDecimal) r[3]).reduce(BigDecimal.ZERO, BigDecimal::add);
        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] r : data) {
            long count = (Long) r[2];
            BigDecimal revenue = (BigDecimal) r[3];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("branchId", String.valueOf(r[0]));
            row.put("branchName", r[1]);
            row.put("salesCount", count);
            row.put("revenue", revenue.doubleValue());
            row.put("revenueShare", fraction(revenue, total));
            row.put("margin", margins.get((Long) r[0]));
            rows.add(row);
            points.add(Map.of("label", r[1], "value", revenue.doubleValue()));
        }
        return Map.of("rows", rows, "chart", Map.of("id", "by-branch", "name", "Ventas por sucursal", "points", points));
    }

    // ---------------------------------------------------------------- by employee

    @Transactional(readOnly = true)
    public Map<String, Object> byEmployee(ReportFilter filter) {
        List<Object[]> data = scoped(
                "SELECT u.id, u.firstName, u.lastName, u.username, COUNT(i), SUM(i.totalAmount) FROM Invoice i LEFT JOIN i.createdBy u WHERE " +
                INVOICE_SCOPE + " GROUP BY u.id, u.firstName, u.lastName, u.username ORDER BY SUM(i.totalAmount) DESC",
                Object[].class, filter).getResultList();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : data) {
            long count = (Long) r[4];
            BigDecimal revenue = (BigDecimal) r[5];
            String name = r[3] == null ? "Sin vendedor" : (r[1] + " " + r[2]).trim();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("employeeId", r[0] == null ? "0" : String.valueOf(r[0]));
            row.put("employeeName", name);
            row.put("salesCount", count);
            row.put("revenue", revenue.doubleValue());
            rows.add(row);
        }
        return Map.of("rows", rows);
    }

    // ---------------------------------------------------------------- quotations

    @Transactional(readOnly = true)
    public Map<String, Object> quotationFunnel(ReportFilter filter) {
        List<Object[]> data = em.createQuery(
                        "SELECT LOWER(q.status), COUNT(q), SUM(CASE WHEN q.validUntil < :now THEN 1 ELSE 0 END) FROM SalesOrder q " +
                        "WHERE q.company.id = :companyId AND q.orderType = 'QUOTATION' " +
                        "AND (:all = true OR q.branch.id IN :branchIds) AND q.createdAt >= :from AND q.createdAt < :to " +
                        "GROUP BY LOWER(q.status)", Object[].class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .setParameter("now", Instant.now())
                .getResultList();

        long issued = 0, accepted = 0, rejected = 0, expired = 0;
        for (Object[] r : data) {
            String status = (String) r[0];
            long count = (Long) r[1];
            if ("draft".equals(status)) {
                continue; // never sent to the customer
            }
            issued += count;
            switch (status) {
                case "converted", "credit", "paid" -> accepted += count;
                case "cancelled", "rejected" -> rejected += count;
                default -> expired += ((Number) r[2]).longValue();
            }
        }

        Map<String, Object> funnel = Map.of("issued", issued, "accepted", accepted, "rejected", rejected,
                "expired", expired, "conversionRate", issued > 0 ? (double) accepted / issued : 0.0);
        Map<String, Object> chart = Map.of("id", "quotation-funnel", "name", "Cotizaciones", "points", List.of(
                Map.of("label", "Emitidas", "value", (double) issued),
                Map.of("label", "Aceptadas", "value", (double) accepted),
                Map.of("label", "Rechazadas", "value", (double) rejected),
                Map.of("label", "Vencidas", "value", (double) expired)));
        return Map.of("funnel", funnel, "chart", chart);
    }

    // ---------------------------------------------------------------- payment methods

    @Transactional(readOnly = true)
    public Map<String, Object> paymentMethods(ReportFilter filter) {
        List<Object[]> data = scoped(
                "SELECT LOWER(i.paymentMethod), COUNT(i), SUM(i.totalAmount) FROM Invoice i WHERE " + INVOICE_SCOPE +
                " GROUP BY LOWER(i.paymentMethod) ORDER BY SUM(i.totalAmount) DESC", Object[].class, filter).getResultList();
        BigDecimal total = data.stream().map(r -> (BigDecimal) r[2]).reduce(BigDecimal.ZERO, BigDecimal::add);

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] r : data) {
            String method = (String) r[0];
            String label = METHOD_LABELS.getOrDefault(method, method);
            BigDecimal revenue = (BigDecimal) r[2];
            rows.add(Map.of("method", method, "methodLabel", label, "count", r[1],
                    "revenue", revenue.doubleValue(), "revenueShare", fraction(revenue, total)));
            points.add(Map.of("label", label, "value", revenue.doubleValue()));
        }
        return Map.of("rows", rows, "chart", Map.of("id", "payment-methods", "name", "Ventas por método de pago", "points", points));
    }

    // ---------------------------------------------------------------- accounts receivable (aging)

    /**
     * What customers still owe on credit sales, as of now, by how late it is: not yet due, 1–30,
     * 31–60 and over 60 days past the due date. Not bounded by the period — a balance is a
     * snapshot — only by branch.
     */
    @Transactional(readOnly = true)
    public Map<String, Object> receivables(ReportFilter filter) {
        List<Invoice> invoices = em.createQuery(
                        "SELECT DISTINCT i FROM Invoice i LEFT JOIN FETCH i.payments LEFT JOIN FETCH i.customer " +
                        "WHERE i.company.id = :companyId AND LOWER(i.paymentMethod) = 'credit' AND i.status <> 'VOIDED' " +
                        "AND (:all = true OR i.branch.id IN :branchIds)", Invoice.class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .getResultList();

        LocalDate today = LocalDate.now(ReportFilter.ZONE);
        Map<String, double[]> byCustomer = new LinkedHashMap<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Invoice invoice : invoices) {
            BigDecimal paid = invoice.getPayments().stream()
                    .map(p -> p.getAmount() == null ? BigDecimal.ZERO : p.getAmount())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal balance = invoice.getTotalAmount().subtract(paid);
            if (balance.signum() <= 0) {
                continue;
            }
            String id = invoice.getCustomer() == null ? "0" : String.valueOf(invoice.getCustomer().getId());
            names.putIfAbsent(id, invoice.getCustomer() == null ? "Cliente general" : invoice.getCustomer().getName());
            Instant due = invoice.getDueDate() != null ? invoice.getDueDate() : invoice.getCreatedAt();
            long daysLate = ChronoUnit.DAYS.between(due.atZone(ReportFilter.ZONE).toLocalDate(), today);

            // [0] count, [1] total, [2] not due, [3] 1-30, [4] 31-60, [5] 60+
            double[] acc = byCustomer.computeIfAbsent(id, k -> new double[6]);
            acc[0]++;
            acc[1] += balance.doubleValue();
            acc[daysLate <= 0 ? 2 : daysLate <= 30 ? 3 : daysLate <= 60 ? 4 : 5] += balance.doubleValue();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        byCustomer.entrySet().stream()
                .sorted((a, b) -> Double.compare(b.getValue()[1], a.getValue()[1]))
                .forEach(e -> {
                    double[] a = e.getValue();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("customerId", e.getKey());
                    row.put("customerName", names.get(e.getKey()));
                    row.put("saleCount", (long) a[0]);
                    row.put("totalExposure", round2(a[1]));
                    row.put("notDue", round2(a[2]));
                    row.put("overdue1to30", round2(a[3]));
                    row.put("overdue31to60", round2(a[4]));
                    row.put("overdueOver60", round2(a[5]));
                    rows.add(row);
                });
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("saleCount", rows.stream().mapToLong(r -> (long) r.get("saleCount")).sum());
        for (String key : List.of("totalExposure", "notDue", "overdue1to30", "overdue31to60", "overdueOver60")) {
            totals.put(key, round2(rows.stream().mapToDouble(r -> (double) r.get(key)).sum()));
        }
        return Map.of("rows", rows, "totals", totals);
    }

    // ---------------------------------------------------------------- helpers

    /** Gross margin as a fraction of the sale net of tax, or null when nothing was sold. */
    private Double margin(ReportFilter filter) {
        Object[] r = scoped(
                "SELECT SUM(it.totalAmount - it.taxAmount), SUM(it.quantity * it.unitCost) FROM InvoiceItem it JOIN it.invoice i WHERE " +
                INVOICE_SCOPE, Object[].class, filter).getSingleResult();
        return marginOf((BigDecimal) r[0], (BigDecimal) r[1]);
    }

    private static Double marginOf(BigDecimal netSales, BigDecimal cost) {
        if (netSales == null || netSales.signum() <= 0) {
            return null;
        }
        return netSales.subtract(cost == null ? BigDecimal.ZERO : cost)
                .divide(netSales, 4, RoundingMode.HALF_UP).doubleValue();
    }

    private static double fraction(BigDecimal part, BigDecimal total) {
        return total.signum() > 0 ? part.divide(total, 4, RoundingMode.HALF_UP).doubleValue() : 0.0;
    }

    private static double round2(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
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


    private static Map<String, Object> kpi(String id, String label, double value, String format) {
        return Map.of("id", id, "label", label, "value", value, "format", format, "intent", "neutral");
    }
}
