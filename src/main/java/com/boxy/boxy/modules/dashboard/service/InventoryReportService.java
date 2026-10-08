package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.SecurityUtils;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * The inventory reports, computed from the real stock levels, movements, adjustments and transfers
 * — limited to the branches and period the screen asks for ({@link ReportFilter}). Response shapes
 * are the frontend's {@code inventory-report.interface.ts}.
 * <p>
 * Stock figures are a snapshot of <em>now</em> (what is on the shelves), the period only bounds the
 * flows: sales, movements, adjustments, completed transfers.
 */
@Service
public class InventoryReportService {

    /** Movements are stored in UTC; the company's calendar days are Bolivia time (UTC-4, no DST). */
    private static final String LOCAL_OFFSET = "-04:00";
    /** Rows computed for a table; paging (PageParams) decides how many travel to the browser. */
    private static final int TABLE_LIMIT = 20000;
    /** The critical-stock list is the one people act on line by line; give it room for a whole catalog. */
    private static final int CRITICAL_LIMIT = 3000;
    private static final int CHART_LIMIT = 10;
    private static final DateTimeFormatter DAY_LABEL = DateTimeFormatter.ofPattern("dd/MM");
    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("MMM yyyy", Locale.forLanguageTag("es"));

    private static final Map<String, String> ADJUSTMENT_REASONS = Map.of(
            "PHYSICAL_COUNT", "Conteo físico",
            "DAMAGE", "Daño o merma",
            "EXPIRY", "Vencimiento",
            "THEFT", "Robo o pérdida",
            "OTHER", "Otro");

    @PersistenceContext
    private EntityManager em;

    // ---------------------------------------------------------------- summary

    @Transactional(readOnly = true)
    public Map<String, Object> summary(ReportFilter filter) {
        List<ProductStock> products = productStock(filter);

        BigDecimal valuation = BigDecimal.ZERO;
        long withStock = 0;
        long outOfStock = 0;
        long lowStock = 0;
        for (ProductStock p : products) {
            valuation = valuation.add(p.quantity.max(BigDecimal.ZERO).multiply(p.cost));
            if (p.quantity.signum() > 0) {
                withStock++;
                if (p.quantity.compareTo(p.minStock) <= 0) {
                    lowStock++;
                }
            } else {
                outOfStock++;
            }
        }

        return Map.of("kpis", List.of(
                kpi("inv-valuation", "Valorización de inventario", valuation.doubleValue(), "currency", "neutral"),
                kpi("inv-skus", "Productos con existencia", withStock, "number", "neutral"),
                kpi("inv-out", "Productos sin existencia", outOfStock, "number", outOfStock > 0 ? "negative" : "neutral"),
                kpi("inv-low-stock", "Alertas de stock mínimo", lowStock, "number", lowStock > 0 ? "negative" : "neutral")));
    }

    // ---------------------------------------------------------------- critical stock

    @Transactional(readOnly = true)
    public Map<String, Object> criticalStock(ReportFilter filter) {
        List<Object[]> levels = em.createQuery(
                        "SELECT p.id, p.sku, p.name, c.name, w.name, s.quantityAvailable, p.minStockAlert " +
                        "FROM StockLevel s JOIN s.product p JOIN s.warehouse w LEFT JOIN p.category c " +
                        "WHERE w.branch.company.id = :companyId AND w.deletedAt IS NULL AND p.deletedAt IS NULL " +
                        "AND p.isActive = true AND (:all = true OR w.branch.id IN :branchIds) " +
                        "AND s.quantityAvailable <= p.minStockAlert " +
                        "ORDER BY s.quantityAvailable ASC, p.name ASC", Object[].class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setMaxResults(CRITICAL_LIMIT)
                .getResultList();

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Object[] r : levels) {
            BigDecimal quantity = (BigDecimal) r[5];
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("productId", String.valueOf(r[0]));
            row.put("sku", r[1]);
            row.put("name", r[2]);
            row.put("categoryName", r[3] == null ? "Sin categoría" : r[3]);
            row.put("warehouseName", r[4]);
            row.put("quantity", quantity.doubleValue());
            row.put("stockStatus", quantity.signum() <= 0 ? "out-of-stock" : "low-stock");
            rows.add(row);
        }

        // Active products with no stock record at all in these branches are out of stock too — the
        // Products screen shows them as "Agotado" — even though they have no row above to list them by.
        Set<Long> stocked = new HashSet<>(em.createQuery(
                        "SELECT DISTINCT s.product.id FROM StockLevel s WHERE s.warehouse.branch.company.id = :companyId " +
                        "AND s.warehouse.deletedAt IS NULL AND (:all = true OR s.warehouse.branch.id IN :branchIds)", Long.class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .getResultList());
        for (ProductStock p : productStock(filter)) {
            if (!stocked.contains(p.id)) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", String.valueOf(p.id));
                row.put("sku", p.sku);
                row.put("name", p.name);
                row.put("categoryName", p.categoryName);
                row.put("warehouseName", "Sin existencias registradas");
                row.put("quantity", 0.0);
                row.put("stockStatus", "out-of-stock");
                rows.add(row);
            }
        }

        // What can still be saved first (low stock), then what is already gone; each by name.
        rows.sort(Comparator
                .comparing((Map<String, Object> r) -> "out-of-stock".equals(r.get("stockStatus")))
                .thenComparing(r -> (String) r.get("name")));
        return Map.of("rows", rows.size() > CRITICAL_LIMIT ? rows.subList(0, CRITICAL_LIMIT) : rows);
    }

    // ---------------------------------------------------------------- movements

    @Transactional(readOnly = true)
    public Map<String, Object> stockMovements(ReportFilter filter) {
        List<Object[]> grouped = em.createQuery(
                        "SELECT FUNCTION('DATE', FUNCTION('CONVERT_TZ', m.createdAt, '+00:00', '" + LOCAL_OFFSET + "')), " +
                        "m.movementType, COUNT(m), SUM(m.quantity) " +
                        "FROM StockMovement m JOIN m.warehouse w " +
                        "WHERE w.branch.company.id = :companyId AND (:all = true OR w.branch.id IN :branchIds) " +
                        "AND m.createdAt >= :from AND m.createdAt < :to " +
                        "GROUP BY FUNCTION('DATE', FUNCTION('CONVERT_TZ', m.createdAt, '+00:00', '" + LOCAL_OFFSET + "')), m.movementType",
                        Object[].class)
                .setParameter("companyId", SecurityUtils.requireCurrentCompanyId())
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .getResultList();

        // category -> bucket start -> total absolute quantity
        Map<String, TreeMap<LocalDate, Double>> byCategory = new LinkedHashMap<>();
        for (String category : List.of("Compras e ingreso inicial", "Ventas", "Transferencias recibidas", "Transferencias enviadas", "Ajustes", "Devoluciones")) {
            byCategory.put(category, new TreeMap<>());
        }
        long total = 0;
        BigDecimal net = BigDecimal.ZERO;
        for (Object[] g : grouped) {
            LocalDate day = ((Date) g[0]).toLocalDate();
            String category = movementCategory((String) g[1]);
            BigDecimal quantity = (BigDecimal) g[3];
            total += (Long) g[2];
            net = net.add(quantity);
            byCategory.get(category).merge(filter.bucketStart(day), quantity.abs().doubleValue(), Double::sum);
        }

        TreeMap<LocalDate, Boolean> buckets = new TreeMap<>();
        for (LocalDate d = filter.bucketStart(filter.from()); !d.isAfter(filter.to()); d = nextBucket(filter, d)) {
            buckets.put(d, true);
        }

        List<Map<String, Object>> series = new ArrayList<>();
        int index = 0;
        for (Map.Entry<String, TreeMap<LocalDate, Double>> entry : byCategory.entrySet()) {
            if (entry.getValue().isEmpty()) {
                continue;
            }
            List<Map<String, Object>> points = new ArrayList<>();
            for (LocalDate bucket : buckets.keySet()) {
                points.add(Map.of("label", bucketLabel(filter, bucket), "value", entry.getValue().getOrDefault(bucket, 0.0)));
            }
            series.add(Map.of("id", "mv-" + index++, "name", entry.getKey(), "points", points));
        }

        return Map.of("series", series, "totalMovements", total, "netQuantityChange", net.doubleValue());
    }

    // ---------------------------------------------------------------- adjustments

    @Transactional(readOnly = true)
    public Map<String, Object> adjustmentActivity(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();

        List<Object[]> byReason = em.createQuery(
                        "SELECT a.reason, COUNT(DISTINCT a.id), COALESCE(SUM(i.differenceQuantity), 0) " +
                        "FROM StockAdjustment a LEFT JOIN a.items i " +
                        "WHERE a.company.id = :companyId AND a.status = com.boxy.boxy.modules.inventory.entity.AdjustmentStatus.APPROVED " +
                        "AND (:all = true OR a.warehouse.branch.id IN :branchIds) " +
                        "AND a.createdAt >= :from AND a.createdAt < :to GROUP BY a.reason ORDER BY COUNT(DISTINCT a.id) DESC",
                        Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .getResultList();

        List<Map<String, Object>> reasonRows = new ArrayList<>();
        for (Object[] r : byReason) {
            String reason = r[0] == null ? "OTHER" : (String) r[0];
            reasonRows.add(Map.of(
                    "reasonId", reason,
                    "reasonName", ADJUSTMENT_REASONS.getOrDefault(reason, reason),
                    "count", ((Long) r[1]).intValue(),
                    "netQuantity", ((BigDecimal) r[2]).doubleValue()));
        }

        List<Object[]> byWarehouse = em.createQuery(
                        "SELECT a.warehouse.name, COUNT(a) FROM StockAdjustment a " +
                        "WHERE a.company.id = :companyId AND a.status = com.boxy.boxy.modules.inventory.entity.AdjustmentStatus.APPROVED " +
                        "AND (:all = true OR a.warehouse.branch.id IN :branchIds) " +
                        "AND a.createdAt >= :from AND a.createdAt < :to GROUP BY a.warehouse.name ORDER BY COUNT(a) DESC",
                        Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .setParameter("from", filter.fromInstant())
                .setParameter("to", filter.toInstantExclusive())
                .getResultList();
        List<Map<String, Object>> points = byWarehouse.stream()
                .map(r -> Map.<String, Object>of("label", r[0], "value", ((Long) r[1]).doubleValue()))
                .toList();

        return Map.of("byReason", reasonRows,
                "byWarehouseChart", Map.of("id", "adjustments-by-warehouse", "name", "Ajustes", "points", points));
    }

    // ---------------------------------------------------------------- transfers

    @Transactional(readOnly = true)
    public Map<String, Object> transferStatus(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        long received = countTransfers(companyId, filter, "RECEIVED", "t.receivedAt", true);
        long rejected = countTransfers(companyId, filter, "REJECTED", "t.createdAt", true);
        // What is open right now is a backlog, not a period figure.
        long inTransit = countTransfers(companyId, filter, "IN_TRANSIT", null, false);
        long pending = countTransfers(companyId, filter, "REQUESTED", null, false)
                + countTransfers(companyId, filter, "APPROVED", null, false);

        return Map.of("kpis", List.of(
                kpi("trans-received", "Transferencias recibidas en el periodo", received, "number", "neutral"),
                kpi("trans-transit", "En tránsito ahora", inTransit, "number", inTransit > 0 ? "neutral" : "neutral"),
                kpi("trans-pending", "Pendientes de despacho", pending, "number", pending > 0 ? "negative" : "neutral"),
                kpi("trans-rejected", "Rechazadas en el periodo", rejected, "number", "neutral")));
    }

    private long countTransfers(Long companyId, ReportFilter filter, String status, String dateField, boolean inPeriod) {
        String query = "SELECT COUNT(t) FROM StockTransfer t WHERE t.company.id = :companyId " +
                "AND t.status = com.boxy.boxy.modules.inventory.entity.TransferStatus." + status + " " +
                "AND (:all = true OR t.sourceWarehouse.branch.id IN :branchIds OR t.destinationWarehouse.branch.id IN :branchIds)" +
                (inPeriod ? " AND " + dateField + " >= :from AND " + dateField + " < :to" : "");
        var q = em.createQuery(query, Long.class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam());
        if (inPeriod) {
            q.setParameter("from", filter.fromInstant()).setParameter("to", filter.toInstantExclusive());
        }
        return q.getSingleResult();
    }

    // ---------------------------------------------------------------- stale

    @Transactional(readOnly = true)
    public Map<String, Object> stale(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Map<Long, java.time.Instant> lastMovement = new HashMap<>();
        for (Object[] r : em.createQuery(
                        "SELECT m.product.id, MAX(m.createdAt) FROM StockMovement m " +
                        "WHERE m.warehouse.branch.company.id = :companyId AND (:all = true OR m.warehouse.branch.id IN :branchIds) " +
                        "GROUP BY m.product.id", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .getResultList()) {
            lastMovement.put((Long) r[0], (java.time.Instant) r[1]);
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        productStock(filter).stream()
                .filter(p -> p.quantity.signum() > 0)
                .filter(p -> {
                    java.time.Instant last = lastMovement.get(p.id);
                    return last == null || last.isBefore(filter.fromInstant());
                })
                .sorted(Comparator.comparing((ProductStock p) -> p.quantity.multiply(p.cost)).reversed())
                .limit(TABLE_LIMIT)
                .forEach(p -> {
                    java.time.Instant last = lastMovement.get(p.id);
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("productId", String.valueOf(p.id));
                    row.put("sku", p.sku);
                    row.put("name", p.name);
                    row.put("categoryName", p.categoryName);
                    row.put("currentStock", p.quantity.doubleValue());
                    row.put("lastMovementAt", last == null ? null : last.atZone(ReportFilter.ZONE).toLocalDate().toString());
                    rows.add(row);
                });
        return Map.of("rows", rows);
    }

    // ---------------------------------------------------------------- helpers

    private record ProductStock(Long id, String sku, String name, String categoryName,
                                BigDecimal cost, BigDecimal minStock, BigDecimal quantity) { }

    /** Every active product with what the selected branches hold of it right now (0 when nothing). */
    private List<ProductStock> productStock(ReportFilter filter) {
        Long companyId = SecurityUtils.requireCurrentCompanyId();
        Map<Long, BigDecimal> held = new HashMap<>();
        for (Object[] r : em.createQuery(
                        "SELECT s.product.id, COALESCE(SUM(s.quantityAvailable), 0) FROM StockLevel s " +
                        "WHERE s.warehouse.branch.company.id = :companyId AND s.warehouse.deletedAt IS NULL " +
                        "AND (:all = true OR s.warehouse.branch.id IN :branchIds) GROUP BY s.product.id", Object[].class)
                .setParameter("companyId", companyId)
                .setParameter("all", filter.allBranches())
                .setParameter("branchIds", filter.branchIdsParam())
                .getResultList()) {
            held.put((Long) r[0], (BigDecimal) r[1]);
        }

        List<ProductStock> out = new ArrayList<>();
        for (Object[] r : em.createQuery(
                        "SELECT p.id, p.sku, p.name, c.name, p.costPrice, p.minStockAlert FROM Product p " +
                        "LEFT JOIN p.category c WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true",
                        Object[].class)
                .setParameter("companyId", companyId)
                .getResultList()) {
            out.add(new ProductStock((Long) r[0], (String) r[1], (String) r[2],
                    r[3] == null ? "Sin categoría" : (String) r[3],
                    r[4] == null ? BigDecimal.ZERO : (BigDecimal) r[4],
                    r[5] == null ? BigDecimal.ZERO : (BigDecimal) r[5],
                    held.getOrDefault((Long) r[0], BigDecimal.ZERO)));
        }
        return out;
    }

    private static String movementCategory(String type) {
        return switch (type) {
            case "PURCHASE_IN", "INITIAL_STOCK" -> "Compras e ingreso inicial";
            case "SALE_OUT" -> "Ventas";
            case "TRANSFER_IN" -> "Transferencias recibidas";
            case "TRANSFER_OUT" -> "Transferencias enviadas";
            case "RETURN" -> "Devoluciones";
            default -> "Ajustes";
        };
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
