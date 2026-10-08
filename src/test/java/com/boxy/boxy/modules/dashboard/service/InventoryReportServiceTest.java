package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.UserPrincipal;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import com.boxy.boxy.modules.inventory.entity.StockMovement;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The inventory reports run real JPQL against the real schema (rolled back afterwards): a query that
 * compiles but names a wrong field, or a join that silently drops rows, only shows up here.
 */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-signing-secret-do-not-use-in-any-real-environment-1234567890",
        "app.recaptcha.enabled=false"
})
@Transactional
class InventoryReportServiceTest {

    @Autowired private InventoryReportService service;
    @PersistenceContext private EntityManager em;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private Company company;
    private Branch branchA;
    private Branch branchB;
    private Warehouse warehouseA;
    private Warehouse warehouseB;
    private UnitOfMeasure unit;

    @BeforeEach
    void seed() {
        company = persist(Company.builder().name("Test Co " + tag).taxId("TAX-" + tag).build());
        branchA = persist(Branch.builder().company(company).code("A" + tag).name("Sucursal A").build());
        branchB = persist(Branch.builder().company(company).code("B" + tag).name("Sucursal B").build());
        warehouseA = persist(Warehouse.builder().branch(branchA).code("WA" + tag).name("Almacén A").build());
        warehouseB = persist(Warehouse.builder().branch(branchB).code("WB" + tag).name("Almacén B").build());
        unit = persist(UnitOfMeasure.builder().company(company).code("U" + tag).name("Unidad").symbol("und").build());

        UserPrincipal principal = UserPrincipal.create(
                1L, company.getId(), "tester", "t@boxy.dev", "hash", "Tester", branchA.getId(), "ACTIVE", List.of());
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));
    }

    @AfterEach
    void clearSecurity() {
        SecurityContextHolder.clearContext();
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    private Product product(String sku, String name, String cost, String min) {
        return persist(Product.builder().company(company).unit(unit).sku(sku + tag).name(name)
                .costPrice(new BigDecimal(cost)).minStockAlert(new BigDecimal(min)).build());
    }

    private void stock(Warehouse warehouse, Product product, String quantity) {
        persist(StockLevel.builder().warehouse(warehouse).product(product).quantityAvailable(new BigDecimal(quantity)).build());
    }

    private void movement(Warehouse warehouse, Product product, String type, String quantity) {
        persist(StockMovement.builder().warehouse(warehouse).product(product).movementType(type)
                .quantity(new BigDecimal(quantity)).unitCost(product.getCostPrice())
                .balanceAfter(BigDecimal.ZERO).referenceType("TEST").referenceId("t-" + tag).build());
    }

    private ReportFilter all() {
        return ReportFilter.of("last-30-days", null, null, null, "day");
    }

    private ReportFilter onlyBranch(Branch branch) {
        return ReportFilter.of("last-30-days", null, null, String.valueOf(branch.getId()), "day");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> kpi(Map<String, Object> summary, String id) {
        return ((List<Map<String, Object>>) summary.get("kpis")).stream()
                .filter(k -> id.equals(k.get("id"))).findFirst().orElseThrow();
    }

    @Test
    void summaryCountsRealStockAndHonoursTheBranchFilter() {
        Product hammer = product("H", "Martillo", "10", "5");   // 3 in A -> low stock
        Product drill = product("D", "Taladro", "100", "2");    // 0 anywhere -> out
        Product saw = product("S", "Sierra", "20", "0");        // 10 in B
        stock(warehouseA, hammer, "3");
        stock(warehouseB, saw, "10");
        em.flush();

        Map<String, Object> everywhere = service.summary(all());
        assertThat((double) kpi(everywhere, "inv-valuation").get("value")).isEqualTo(3 * 10 + 10 * 20);
        assertThat((double) kpi(everywhere, "inv-skus").get("value")).isEqualTo(2);
        assertThat((double) kpi(everywhere, "inv-out").get("value")).isEqualTo(1);
        assertThat((double) kpi(everywhere, "inv-low-stock").get("value")).isEqualTo(1);

        Map<String, Object> onlyB = service.summary(onlyBranch(branchB));
        assertThat((double) kpi(onlyB, "inv-valuation").get("value")).isEqualTo(200);
        assertThat((double) kpi(onlyB, "inv-skus").get("value")).isEqualTo(1);
        assertThat(drill.getId()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void criticalStockListsEachWarehouseHoldingLessThanTheMinimum() {
        Product hammer = product("H", "Martillo", "10", "5");
        Product drill = product("D", "Taladro", "100", "2");
        stock(warehouseA, hammer, "3");
        stock(warehouseB, drill, "0");
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.criticalStock(all()).get("rows");

        // Low stock first (still savable), then what is already gone.
        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Martillo", "Taladro");
        assertThat(rows.get(0).get("stockStatus")).isEqualTo("low-stock");
        assertThat(rows.get(1).get("stockStatus")).isEqualTo("out-of-stock");
        assertThat(rows.get(1).get("warehouseName")).isEqualTo("Almacén B");

        // Branch A holds only the hammer; the drill has no stock record there, so it is out of stock there too.
        List<Map<String, Object>> branchA = (List<Map<String, Object>>) service.criticalStock(onlyBranch(this.branchA)).get("rows");
        assertThat(branchA).extracting(r -> r.get("name")).containsExactly("Martillo", "Taladro");
    }

    @Test
    @SuppressWarnings("unchecked")
    void aProductNeverStockedIsListedAsOutOfStock() {
        Product neverStocked = product("N", "Nunca ingresó", "10", "0");
        Product healthy = product("OK", "Con stock", "10", "2");
        stock(warehouseA, healthy, "50");
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.criticalStock(all()).get("rows");

        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Nunca ingresó");
        assertThat(rows.get(0).get("stockStatus")).isEqualTo("out-of-stock");
        assertThat(neverStocked.getId()).isNotNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void movementsAreBucketedByTypeAndPeriod() {
        Product hammer = product("H", "Martillo", "10", "0");
        movement(warehouseA, hammer, "PURCHASE_IN", "20");
        movement(warehouseA, hammer, "SALE_OUT", "-5");
        em.flush();

        Map<String, Object> report = service.stockMovements(all());
        List<Map<String, Object>> series = (List<Map<String, Object>>) report.get("series");

        assertThat(report.get("totalMovements")).isEqualTo(2L);
        assertThat((double) report.get("netQuantityChange")).isEqualTo(15.0);
        assertThat(series).extracting(s -> s.get("name")).containsExactlyInAnyOrder("Compras e ingreso inicial", "Ventas");
        // One point per day of the 30-day period.
        assertThat((List<?>) series.get(0).get("points")).hasSize(30);
    }

    @Test
    @SuppressWarnings("unchecked")
    void staleProductsAreThoseWithStockButNoMovementInThePeriod() {
        Product moving = product("M", "Con movimiento", "10", "0");
        Product idle = product("I", "Sin movimiento", "10", "0");
        stock(warehouseA, moving, "5");
        stock(warehouseA, idle, "7");
        movement(warehouseA, moving, "SALE_OUT", "-1");
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.stale(all()).get("rows");

        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Sin movimiento");
    }

    @Test
    void adjustmentAndTransferReportsRunOnAnEmptyPeriod() {
        assertThat(service.adjustmentActivity(all())).containsKeys("byReason", "byWarehouseChart");
        Map<String, Object> transfers = service.transferStatus(all());
        assertThat(transfers).containsKey("kpis");
        assertThat(ReportFilter.of("custom", LocalDate.now().minusDays(3).toString(), LocalDate.now().toString(), null, "week")
                .from()).isEqualTo(LocalDate.now().minusDays(3));
    }
}
