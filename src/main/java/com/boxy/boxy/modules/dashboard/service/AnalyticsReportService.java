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
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The analytics reports — trends, branch comparison, period-over-period and rankings — computed
 * from the real invoices (voided ones left out), purchase orders and stock levels, for the period and
 * branches the screen asks for.
 */
@Service
public class AnalyticsReportService {

    /** Invoices are stored in UTC; the company's days are Bolivia time (UTC-4, no DST). */
    private static final String LOCAL_OFFSET = "-04:00";
    private static final int RANK_SIZE = 5;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"));
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

    // ---------------------------------------------------------------- trends

    /** Sales (Bs), units sold and number of sales, over the same time axis. */
    @Transactional(readOnly = true)
    public Map<String, Object> trends(ReportFilter filter) {
        String day = "FUNCTION('DATE', FUNCTION('CONVERT_TZ', i.createdAt, '+00:00', '" + LOCAL_OFFSET + "'))";
        List<Object[]> sales = scoped(
                "SELECT " + day + ", SUM(i.totalAmount), COUNT(i) FROM Invoice i WHERE " + INVOICE_SCOPE + " GROUP BY " + day,
                Object[].class, filter).getResultList();
        List<Object[]> units = scoped(
                "SELECT " + day + ", SUM(it.quantity) FROM InvoiceItem it JOIN it.invoice i WHERE " + INVOICE_SCOPE + " GROUP BY " + day,
                Object[].class, filter).getResultList();

        TreeMap<LocalDate, double[]> byBucket = new TreeMap<>(); // [sales, units, count]
        for (LocalDate d = filter.bucketStart(filter.from()); !d.isAfter(filter.to()); d = nextBucket(filter, d)) {
            byBucket.put(d, new double[3]);
        }
        for (Object[] r : sales) {
            double[] acc = byBucket.get(filter.bucketStart(((Date) r[0]).toLocalDate()));
            acc[0] += ((BigDecimal) r[1]).doubleValue();
            acc[2] += ((Long) r[2]).doubleValue();
        }
        for (Object[] r : units) {
            byBucket.get(filter.bucketStart(((Date) r[0]).toLocalDate()))[1] += ((BigDecimal) r[1]).doubleValue();
        }

        List<Map<String, Object>> revenue = new ArrayList<>();
        List<Map<String, Object>> quantity = new ArrayList<>();
        List<Map<String, Object>> count = new ArrayList<>();
        byBucket.forEach((bucket, v) -> {
            String label = bucketLabel(filter, bucket);
            revenue.add(Map.of("label", label, "value", v[0]));
            quantity.add(Map.of("label", label, "value", v[1]));
            count.add(Map.of("label", label, "value", v[2]));
        });
        return Map.of("series", List.of(
                Map.of("id", "rev", "name", "Ventas (Bs)", "points", revenue),
                Map.of("id", "units", "name", "Unidades vendidas", "points", quantity),
                Map.of("id", "ops", "name", "Número de ventas", "points", count)));
    }

    // ---------------------------------------------------------------- branch comparison

    @Transactional(readOnly = true)
    public Map<String, Object> branchComparison(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();

        List<Object[]> branches = em.createQuery(
                        "SELECT b.id, b.name FROM Branch b WHERE b.company.id = :companyId AND b.deletedAt IS NULL " +
                        "AND (:all = true OR b.id IN :branchIds) ORDER BY b.name", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .getResultList();

        Map<Long, Object[]> sales = new LinkedHashMap<>();
        for (Object[] r : scoped(
                "SELECT i.branch.id, COUNT(i), SUM(i.totalAmount), SUM(i.discountAmount), SUM(i.totalAmount - i.taxAmount) " +
                "FROM Invoice i WHERE " + INVOICE_SCOPE + " GROUP BY i.branch.id", Object[].class, filter).getResultList()) {
            sales.put((Long) r[0], r);
        }

        Map<Long, BigDecimal> inventoryValue = new LinkedHashMap<>();
        for (Object[] r : em.createQuery(
                        "SELECT w.branch.id, SUM(s.quantityAvailable * p.costPrice) FROM StockLevel s JOIN s.warehouse w JOIN s.product p " +
                        "WHERE w.branch.company.id = :companyId AND w.deletedAt IS NULL AND p.deletedAt IS NULL GROUP BY w.branch.id",
                        Object[].class).setParameter("companyId", companyId).getResultList()) {
            inventoryValue.put((Long) r[0], (BigDecimal) r[1]);
        }

        Map<Long, Long> critical = new LinkedHashMap<>();
        for (Object[] r : em.createQuery(
                        "SELECT w.branch.id, COUNT(s) FROM StockLevel s JOIN s.warehouse w JOIN s.product p " +
                        "WHERE w.branch.company.id = :companyId AND w.deletedAt IS NULL AND p.deletedAt IS NULL AND p.isActive = true " +
                        "AND s.quantityAvailable <= p.minStockAlert GROUP BY w.branch.id", Object[].class)
                .setParameter("companyId", companyId).getResultList()) {
            critical.put((Long) r[0], (Long) r[1]);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        List<Map<String, Object>> points = new ArrayList<>();
        for (Object[] b : branches) {
            Long id = (Long) b[0];
            Object[] s = sales.get(id);
            double revenue = s == null ? 0 : ((BigDecimal) s[2]).doubleValue();
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("branchId", String.valueOf(id));
            row.put("branchName", b[1]);
            row.put("revenue", revenue);
            row.put("salesCount", s == null ? 0L : s[1]);
            row.put("discountAmount", s == null ? 0.0 : ((BigDecimal) s[3]).doubleValue());
            row.put("netRevenue", s == null ? 0.0 : ((BigDecimal) s[4]).doubleValue());
            row.put("inventoryValue", inventoryValue.getOrDefault(id, BigDecimal.ZERO).doubleValue());
            row.put("criticalStockCount", critical.getOrDefault(id, 0L));
            rows.add(row);
            points.add(Map.of("label", b[1], "value", revenue));
        }
        return Map.of("rows", rows, "chart", Map.of("id", "branch-comparison", "name", "Ventas por sucursal", "points", points));
    }

    // ---------------------------------------------------------------- period comparison

    /** The chosen period against the one of the same length right before it. */
    @Transactional(readOnly = true)
    public Map<String, Object> periodComparison(ReportFilter filter) {
        long days = ChronoUnit.DAYS.between(filter.from(), filter.to()) + 1;
        LocalDate previousTo = filter.from().minusDays(1);
        LocalDate previousFrom = previousTo.minusDays(days - 1);
        ReportFilter previous = new ReportFilter(previousFrom, previousTo, filter.branchIds(), filter.granularity());

        double[] now = totals(filter);
        double[] before = totals(previous);

        List<Map<String, Object>> kpis = List.of(
                compared("comp-rev", "Ventas", now[0], before[0], "currency", true),
                compared("comp-ops", "Número de ventas", now[1], before[1], "number", true),
                compared("comp-profit", "Utilidad bruta", now[2], before[2], "currency", true),
                compared("comp-discounts", "Descuentos otorgados", now[3], before[3], "currency", false));

        return Map.of("kpis", kpis,
                "currentPeriod", Map.of("from", filter.from().toString(), "to", filter.to().toString()),
                "previousPeriod", Map.of("from", previousFrom.toString(), "to", previousTo.toString()));
    }

    /** [sales with tax, number of sales, gross profit, discounts] for a period. */
    private double[] totals(ReportFilter filter) {
        Object[] invoices = scoped(
                "SELECT COALESCE(SUM(i.totalAmount), 0), COUNT(i), COALESCE(SUM(i.discountAmount), 0) FROM Invoice i WHERE " + INVOICE_SCOPE,
                Object[].class, filter).getSingleResult();
        Object[] lines = scoped(
                "SELECT COALESCE(SUM(it.totalAmount - it.taxAmount), 0), COALESCE(SUM(it.quantity * it.unitCost), 0) " +
                "FROM InvoiceItem it JOIN it.invoice i WHERE " + INVOICE_SCOPE, Object[].class, filter).getSingleResult();
        double profit = ((BigDecimal) lines[0]).subtract((BigDecimal) lines[1]).doubleValue();
        return new double[]{((BigDecimal) invoices[0]).doubleValue(), ((Long) invoices[1]).doubleValue(), profit,
                ((BigDecimal) invoices[2]).doubleValue()};
    }

    private static Map<String, Object> compared(String id, String label, double current, double previous, String format, boolean moreIsBetter) {
        Map<String, Object> kpi = new LinkedHashMap<>();
        kpi.put("id", id);
        kpi.put("label", label);
        kpi.put("value", current);
        kpi.put("format", format);
        kpi.put("previousValue", previous);
        // No percentage against a period with nothing to compare to.
        if (previous != 0) {
            double delta = BigDecimal.valueOf((current - previous) / Math.abs(previous) * 100).setScale(1, RoundingMode.HALF_UP).doubleValue();
            boolean up = delta > 0;
            kpi.put("deltaPercent", delta);
            kpi.put("direction", delta == 0 ? "flat" : up ? "up" : "down");
            kpi.put("intent", delta == 0 ? "neutral" : (up == moreIsBetter) ? "positive" : "negative");
        } else {
            kpi.put("direction", "flat");
            kpi.put("intent", "neutral");
        }
        return kpi;
    }

    // ---------------------------------------------------------------- rankings

    @Transactional(readOnly = true)
    public Map<String, Object> rankings(ReportFilter filter) {
        List<Map<String, Object>> products = entries(scoped(
                "SELECT p.id, p.name, SUM(it.quantity) FROM InvoiceItem it JOIN it.invoice i JOIN it.product p WHERE " + INVOICE_SCOPE +
                " GROUP BY p.id, p.name ORDER BY SUM(it.quantity) DESC", Object[].class, filter).getResultList());
        List<Map<String, Object>> categories = entries(scoped(
                "SELECT c.id, c.name, SUM(it.totalAmount) FROM InvoiceItem it JOIN it.invoice i JOIN it.product p LEFT JOIN p.category c WHERE " +
                INVOICE_SCOPE + " GROUP BY c.id, c.name ORDER BY SUM(it.totalAmount) DESC", Object[].class, filter).getResultList());
        List<Map<String, Object>> customers = entries(scoped(
                "SELECT c.id, c.name, SUM(i.totalAmount) FROM Invoice i JOIN i.customer c WHERE " + INVOICE_SCOPE +
                " GROUP BY c.id, c.name ORDER BY SUM(i.totalAmount) DESC", Object[].class, filter).getResultList());
        List<Map<String, Object>> suppliers = entries(em.createQuery(
                        "SELECT s.id, s.name, SUM(po.totalAmount) FROM PurchaseOrder po JOIN po.supplier s WHERE po.company.id = :companyId " +
                        "AND po.status IN " + PURCHASE_STATUSES + " AND (:all = true OR po.branch.id IN :branchIds) " +
                        "AND po.issueDate >= :from AND po.issueDate <= :to GROUP BY s.id, s.name ORDER BY SUM(po.totalAmount) DESC",
                        Object[].class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.from())
                .setParameter("to", filter.to())
                .getResultList());

        return Map.of(
                "products", group("Productos más vendidos (unidades)", "number", products),
                "categories", group("Categorías con mayor venta", "currency", categories),
                "customers", group("Clientes principales", "currency", customers),
                "suppliers", group("Principales proveedores (compras)", "currency", suppliers));
    }

    private static List<Map<String, Object>> entries(List<Object[]> rows) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Object[] r : rows) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", r[0] == null ? "0" : String.valueOf(r[0]));
            entry.put("name", r[1] == null ? "Sin categoría" : r[1]);
            entry.put("value", ((BigDecimal) r[2]).doubleValue());
            out.add(entry);
        }
        return out;
    }

    /** Top 5 plus bottom 5 — the bottom only when there are more entries than the top already covers. */
    private static Map<String, Object> group(String label, String format, List<Map<String, Object>> ranked) {
        List<Map<String, Object>> top = ranked.subList(0, Math.min(RANK_SIZE, ranked.size()));
        List<Map<String, Object>> bottom = new ArrayList<>();
        if (ranked.size() > RANK_SIZE) {
            int from = Math.max(RANK_SIZE, ranked.size() - RANK_SIZE);
            bottom.addAll(ranked.subList(from, ranked.size()));
            java.util.Collections.reverse(bottom); // least first
        }
        return Map.of("label", label, "format", format, "top", new ArrayList<>(top), "bottom", bottom);
    }

    // ---------------------------------------------------------------- helpers

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
}
