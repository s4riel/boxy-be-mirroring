package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.UserPrincipal;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.User;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.sales.entity.CashierSession;
import com.boxy.boxy.modules.sales.entity.Customer;
import com.boxy.boxy.modules.sales.entity.Invoice;
import com.boxy.boxy.modules.sales.entity.InvoiceItem;
import com.boxy.boxy.modules.sales.entity.Payment;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Sales reports run real JPQL against the real schema (rolled back afterwards). */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-signing-secret-do-not-use-in-any-real-environment-1234567890",
        "app.recaptcha.enabled=false"
})
@Transactional
class SalesReportServiceTest {

    @Autowired private SalesReportService service;
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
    private Product hammer;
    private Product drill;
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
        UnitOfMeasure unit = persist(UnitOfMeasure.builder().company(company).code("U" + tag).name("Unidad").symbol("und").build());
        hammer = persist(Product.builder().company(company).unit(unit).sku("H" + tag).name("Martillo").build());
        drill = persist(Product.builder().company(company).unit(unit).sku("D" + tag).name("Taladro").build());

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

    /** One invoice with one line: sold at {@code total} (no tax), costing {@code cost} per unit for {@code qty} units. */
    private Invoice sale(Branch branch, Warehouse warehouse, CashierSession session, Product product,
                         String method, String status, String total, String qty, String cost) {
        Invoice invoice = persist(Invoice.builder().company(company).branch(branch).warehouse(warehouse)
                .cashierSession(session).customer(customer).documentType("TICKET").paymentMethod(method)
                .series("S" + tag).number(String.valueOf(++folio)).status(status).createdBy(seller)
                .totalAmount(new BigDecimal(total)).subtotal(new BigDecimal(total)).build());
        persist(InvoiceItem.builder().invoice(invoice).product(product).productName(product.getName())
                .sku(product.getSku()).quantity(new BigDecimal(qty)).unitPrice(new BigDecimal(total))
                .unitCost(new BigDecimal(cost)).totalAmount(new BigDecimal(total)).build());
        return invoice;
    }

    private ReportFilter all() {
        return ReportFilter.of("last-30-days", null, null, null, "day");
    }

    @SuppressWarnings("unchecked")
    private double kpi(Map<String, Object> summary, String id) {
        return (double) ((List<Map<String, Object>>) summary.get("kpis")).stream()
                .filter(k -> id.equals(k.get("id"))).findFirst().orElseThrow().get("value");
    }

    @Test
    @SuppressWarnings("unchecked")
    void summaryCountsOnlyBilledSalesAndMeasuresMarginOnCost() {
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "100", "2", "30");   // margin 40/100
        sale(branchB, warehouseB, sessionB, drill, "card", "ISSUED", "300", "1", "150");   // margin 150/300
        sale(branchA, warehouseA, sessionA, hammer, "cash", "VOIDED", "999", "1", "1");    // ignored
        em.flush();

        Map<String, Object> summary = service.summary(all());

        assertThat(kpi(summary, "sales-total")).isEqualTo(400.0);
        assertThat(kpi(summary, "sales-count")).isEqualTo(2.0);
        assertThat(((List<Map<String, Object>>) summary.get("kpis")).stream().map(k -> k.get("id"))).doesNotContain("sales-ticket");
        // (400 - (60 + 150)) / 400
        assertThat(kpi(summary, "sales-margin")).isEqualTo(0.475);
        Map<String, Object> trend = (Map<String, Object>) summary.get("revenueTrend");
        assertThat((List<?>) trend.get("points")).hasSize(30);
    }

    @Test
    void branchFilterNarrowsEveryFigure() {
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "100", "1", "10");
        sale(branchB, warehouseB, sessionB, drill, "card", "ISSUED", "300", "1", "10");
        em.flush();

        ReportFilter onlyB = ReportFilter.of("last-30-days", null, null, String.valueOf(branchB.getId()), "day");

        assertThat(kpi(service.summary(onlyB), "sales-total")).isEqualTo(300.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void byProductRanksByRevenueWithShareAndMargin() {
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "100", "2", "30");
        sale(branchA, warehouseA, sessionA, drill, "cash", "ISSUED", "300", "1", "150");
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.byProduct(all()).get("rows");

        assertThat(rows).extracting(r -> r.get("name")).containsExactly("Taladro", "Martillo");
        assertThat(rows.get(0).get("revenueShare")).isEqualTo(0.75);
        assertThat(rows.get(0).get("margin")).isEqualTo(0.5);
        assertThat(rows.get(1).get("quantity")).isEqualTo(2.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void byBranchAndByEmployeeAggregateRealSales() {
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "100", "1", "10");
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "200", "1", "10");
        sale(branchB, warehouseB, sessionB, drill, "card", "ISSUED", "100", "1", "10");
        em.flush();

        List<Map<String, Object>> branches = (List<Map<String, Object>>) service.byBranch(all()).get("rows");
        assertThat(branches).extracting(r -> r.get("branchName")).containsExactly("Sucursal A", "Sucursal B");
        assertThat(branches.get(0).get("salesCount")).isEqualTo(2L);
        assertThat(branches.get(0)).doesNotContainKey("avgTicket");

        List<Map<String, Object>> employees = (List<Map<String, Object>>) service.byEmployee(all()).get("rows");
        assertThat(employees).hasSize(1);
        assertThat(employees.get(0).get("employeeName")).isEqualTo("Ana Pérez");
        assertThat(employees.get(0).get("revenue")).isEqualTo(400.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void paymentMethodsSplitRevenueByHowItWasPaid() {
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "300", "1", "10");
        sale(branchA, warehouseA, sessionA, hammer, "card", "ISSUED", "100", "1", "10");
        em.flush();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.paymentMethods(all()).get("rows");

        assertThat(rows.get(0).get("methodLabel")).isEqualTo("Efectivo");
        assertThat(rows.get(0).get("revenueShare")).isEqualTo(0.75);
        assertThat(rows.get(1).get("methodLabel")).isEqualTo("Tarjeta");
    }

    @Test
    @SuppressWarnings("unchecked")
    void receivablesAgeWhatIsStillOwedByDaysLate() {
        Invoice notDue = sale(branchA, warehouseA, sessionA, hammer, "credit", "ISSUED", "100", "1", "10");
        notDue.setDueDate(Instant.now().plus(java.time.Duration.ofDays(10)));
        Invoice late = sale(branchA, warehouseA, sessionA, hammer, "credit", "ISSUED", "200", "1", "10");
        late.setDueDate(Instant.now().minus(java.time.Duration.ofDays(45)));
        Invoice partlyPaid = sale(branchA, warehouseA, sessionA, hammer, "credit", "ISSUED", "500", "1", "10");
        partlyPaid.setDueDate(Instant.now().minus(java.time.Duration.ofDays(5)));
        persist(Payment.builder().invoice(partlyPaid).paymentMethod("CASH").amount(new BigDecimal("200")).build());
        sale(branchA, warehouseA, sessionA, hammer, "credit", "VOIDED", "999", "1", "10");   // voided: not owed
        sale(branchA, warehouseA, sessionA, hammer, "cash", "ISSUED", "700", "1", "10");     // not credit
        em.flush();
        em.clear();

        List<Map<String, Object>> rows = (List<Map<String, Object>>) service.receivables(all()).get("rows");

        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("saleCount")).isEqualTo(3L);
        assertThat(row.get("totalExposure")).isEqualTo(600.0);
        assertThat(row.get("notDue")).isEqualTo(100.0);
        assertThat(row.get("overdue1to30")).isEqualTo(300.0);
        assertThat(row.get("overdue31to60")).isEqualTo(200.0);
        assertThat(row.get("overdueOver60")).isEqualTo(0.0);
    }

    @Test
    void quotationFunnelRunsOnAnEmptyPeriod() {
        Map<String, Object> report = service.quotationFunnel(all());

        assertThat(report).containsKeys("funnel", "chart");
    }
}
