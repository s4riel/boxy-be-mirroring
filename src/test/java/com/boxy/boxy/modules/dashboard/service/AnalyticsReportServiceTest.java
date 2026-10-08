package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.UserPrincipal;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.User;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.catalog.entity.Category;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import com.boxy.boxy.modules.sales.entity.CashierSession;
import com.boxy.boxy.modules.sales.entity.Customer;
import com.boxy.boxy.modules.sales.entity.Invoice;
import com.boxy.boxy.modules.sales.entity.InvoiceItem;
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
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The analytics reports run real JPQL against the real schema (rolled back afterwards). */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-signing-secret-do-not-use-in-any-real-environment-1234567890",
        "app.recaptcha.enabled=false"
})
@Transactional
class AnalyticsReportServiceTest {

    @Autowired private AnalyticsReportService service;
    @PersistenceContext private EntityManager em;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private Company company;
    private Branch branchA;
    private Branch branchB;
    private Warehouse warehouseA;
    private Warehouse warehouseB;
    private CashierSession sessionA;
    private CashierSession sessionB;
    private User seller;
    private Customer customer;
    private UnitOfMeasure unit;
    private Category category;
    private int folio;

    @BeforeEach
    void seed() {
        company = persist(Company.builder().name("Test Co " + tag).taxId("TAX-" + tag).build());
        branchA = persist(Branch.builder().company(company).code("A" + tag).name("Sucursal A").build());
        branchB = persist(Branch.builder().company(company).code("B" + tag).name("Sucursal B").build());
        warehouseA = persist(Warehouse.builder().branch(branchA).code("WA" + tag).name("Almacén A").build());
        warehouseB = persist(Warehouse.builder().branch(branchB).code("WB" + tag).name("Almacén B").build());
        seller = persist(User.builder().company(company).username("u" + tag).email(tag + "@boxy.dev")
                .passwordHash("x").firstName("Ana").lastName("Pérez").build());
        sessionA = persist(CashierSession.builder().branch(branchA).user(seller).build());
        sessionB = persist(CashierSession.builder().branch(branchB).user(seller).build());
        customer = persist(Customer.builder().company(company).documentType("OTHER").documentNumber("1" + tag).name("Cliente Uno").build());
        unit = persist(UnitOfMeasure.builder().company(company).code("U" + tag).name("Unidad").symbol("und").build());
        category = persist(Category.builder().company(company).code("C" + tag).name("Herramientas").build());

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

    private Product product(String sku, String name) {
        return persist(Product.builder().company(company).unit(unit).category(category).sku(sku + tag).name(name)
                .costPrice(new BigDecimal("10")).minStockAlert(new BigDecimal("5")).build());
    }

    private Invoice sale(Branch branch, Warehouse warehouse, CashierSession session, Product product, String total, String qty) {
        Invoice invoice = persist(Invoice.builder().company(company).branch(branch).warehouse(warehouse)
                .cashierSession(session).customer(customer).documentType("TICKET").paymentMethod("cash")
                .series("S" + tag).number(String.valueOf(++folio)).status("ISSUED").createdBy(seller)
                .totalAmount(new BigDecimal(total)).subtotal(new BigDecimal(total)).build());
        persist(InvoiceItem.builder().invoice(invoice).product(product).productName(product.getName()).sku(product.getSku())
                .quantity(new BigDecimal(qty)).unitPrice(new BigDecimal(total)).unitCost(new BigDecimal("10"))
                .totalAmount(new BigDecimal(total)).build());
        return invoice;
    }

    private ReportFilter all() {
        return ReportFilter.of("last-30-days", null, null, null, "day");
    }

    @Test
    @SuppressWarnings("unchecked")
    void trendsGiveSalesUnitsAndCountOnOneTimeAxis() {
        Product hammer = product("H", "Martillo");
        sale(branchA, warehouseA, sessionA, hammer, "100", "2");
        sale(branchA, warehouseA, sessionA, hammer, "50", "1");
        em.flush();

        List<Map<String, Object>> series = (List<Map<String, Object>>) service.trends(all()).get("series");

        assertThat(series).extracting(s -> s.get("name")).containsExactly("Ventas (Bs)", "Unidades vendidas", "Número de ventas");
        for (Map<String, Object> s : series) {
            assertThat((List<?>) s.get("points")).hasSize(30);
        }
        double[] totals = series.stream().mapToDouble(s -> ((List<Map<String, Object>>) s.get("points")).stream()
                .mapToDouble(p -> (double) p.get("value")).sum()).toArray();
        assertThat(totals).containsExactly(150.0, 3.0, 2.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void branchComparisonListsEveryBranchEvenWithoutSales_withStockFigures() {
        Product hammer = product("H", "Martillo");
        sale(branchA, warehouseA, sessionA, hammer, "100", "1");
        persist(StockLevel.builder().warehouse(warehouseA).product(hammer).quantityAvailable(new BigDecimal("3")).build());
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.branchComparison(all()).get("rows");

        assertThat(rows).extracting(r -> r.get("branchName")).containsExactly("Sucursal A", "Sucursal B");
        assertThat(rows.get(0).get("revenue")).isEqualTo(100.0);
        assertThat(rows.get(0).get("salesCount")).isEqualTo(1L);
        assertThat(rows.get(0).get("inventoryValue")).isEqualTo(30.0);      // 3 × cost 10
        assertThat(rows.get(0).get("criticalStockCount")).isEqualTo(1L);    // 3 <= minimum 5
        assertThat(rows.get(1).get("revenue")).isEqualTo(0.0);
        assertThat(rows.get(1).get("salesCount")).isEqualTo(0L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void periodComparisonSetsThisPeriodAgainstTheOneJustBefore() {
        Product hammer = product("H", "Martillo");
        sale(branchA, warehouseA, sessionA, hammer, "300", "1");                       // this period
        Invoice old = sale(branchA, warehouseA, sessionA, hammer, "100", "1");        // 40 days ago
        em.flush();
        em.createQuery("UPDATE Invoice i SET i.createdAt = :at WHERE i.id = :id")
                .setParameter("at", Instant.now().minus(java.time.Duration.ofDays(40)))
                .setParameter("id", old.getId()).executeUpdate();
        em.clear();

        Map<String, Object> report = service.periodComparison(all());
        Map<String, Object> revenue = ((List<Map<String, Object>>) report.get("kpis")).get(0);

        assertThat(revenue.get("value")).isEqualTo(300.0);
        assertThat(revenue.get("previousValue")).isEqualTo(100.0);
        assertThat(revenue.get("deltaPercent")).isEqualTo(200.0);
        assertThat(revenue.get("direction")).isEqualTo("up");
        assertThat(revenue.get("intent")).isEqualTo("positive");
        Map<String, Object> previous = (Map<String, Object>) report.get("previousPeriod");
        assertThat(LocalDate.parse((String) previous.get("to"))).isEqualTo(all().from().minusDays(1));
    }

    @Test
    @SuppressWarnings("unchecked")
    void rankingsOrderByUnitsAndMoney_andTheBottomOnlyAppearsWithMoreThanFive() {
        for (int i = 1; i <= 7; i++) {
            sale(branchA, warehouseA, sessionA, product("P" + i + "-", "Producto " + i), String.valueOf(i * 10), String.valueOf(i));
        }
        em.flush();

        Map<String, Object> rankings = service.rankings(all());
        Map<String, Object> products = (Map<String, Object>) rankings.get("products");
        List<Map<String, Object>> top = (List<Map<String, Object>>) products.get("top");
        List<Map<String, Object>> bottom = (List<Map<String, Object>>) products.get("bottom");

        assertThat(products.get("format")).isEqualTo("number");
        assertThat(top).extracting(e -> e.get("name")).containsExactly("Producto 7", "Producto 6", "Producto 5", "Producto 4", "Producto 3");
        assertThat(bottom).extracting(e -> e.get("name")).containsExactly("Producto 1", "Producto 2");

        Map<String, Object> customers = (Map<String, Object>) rankings.get("customers");
        assertThat((List<?>) customers.get("top")).hasSize(1);
        assertThat((List<?>) customers.get("bottom")).isEmpty();
        assertThat(((Map<String, Object>) rankings.get("categories")).get("format")).isEqualTo("currency");
    }
}
