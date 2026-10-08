package com.boxy.boxy.modules.dashboard.service;

import com.boxy.boxy.core.security.UserPrincipal;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.User;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.catalog.entity.Category;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.purchasing.entity.PurchaseOrder;
import com.boxy.boxy.modules.purchasing.entity.Supplier;
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
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** The financial reports run real JPQL against the real schema (rolled back afterwards). */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-signing-secret-do-not-use-in-any-real-environment-1234567890",
        "app.recaptcha.enabled=false"
})
@Transactional
class FinancialReportServiceTest {

    @Autowired private FinancialReportService service;
    @PersistenceContext private EntityManager em;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private Company company;
    private Branch branch;
    private Warehouse warehouse;
    private CashierSession session;
    private User seller;
    private Customer customer;
    private Product product;
    private Category category;
    private int folio;

    @BeforeEach
    void seed() {
        company = persist(Company.builder().name("Test Co " + tag).taxId("TAX-" + tag).build());
        branch = persist(Branch.builder().company(company).code("A" + tag).name("Sucursal A").build());
        warehouse = persist(Warehouse.builder().branch(branch).code("WA" + tag).name("Almacén A").build());
        seller = persist(User.builder().company(company).username("u" + tag).email(tag + "@boxy.dev")
                .passwordHash("x").firstName("Ana").lastName("Pérez").build());
        session = persist(CashierSession.builder().branch(branch).user(seller).build());
        customer = persist(Customer.builder().company(company).documentType("OTHER").documentNumber("1" + tag).name("Cliente").build());
        UnitOfMeasure unit = persist(UnitOfMeasure.builder().company(company).code("U" + tag).name("Unidad").symbol("und").build());
        category = persist(Category.builder().company(company).code("C" + tag).name("Herramientas").build());
        product = persist(Product.builder().company(company).unit(unit).category(category).sku("H" + tag).name("Martillo").build());

        UserPrincipal principal = UserPrincipal.create(
                1L, company.getId(), "tester", "t@boxy.dev", "hash", "Tester", branch.getId(), "ACTIVE", List.of());
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

    /** A sale of {@code total} (tax included) with {@code tax} inside it, costing {@code cost} for the single unit. */
    private void sale(String status, String total, String tax, String discount, String cost) {
        Invoice invoice = persist(Invoice.builder().company(company).branch(branch).warehouse(warehouse)
                .cashierSession(session).customer(customer).documentType("TICKET").paymentMethod("cash")
                .series("S" + tag).number(String.valueOf(++folio)).status(status).createdBy(seller)
                .totalAmount(new BigDecimal(total)).taxAmount(new BigDecimal(tax))
                .discountAmount(new BigDecimal(discount)).subtotal(new BigDecimal(total)).build());
        persist(InvoiceItem.builder().invoice(invoice).product(product).productName("Martillo").sku(product.getSku())
                .quantity(BigDecimal.ONE).unitPrice(new BigDecimal(total)).unitCost(new BigDecimal(cost))
                .taxAmount(new BigDecimal(tax)).totalAmount(new BigDecimal(total)).build());
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
    void summaryHasSalesCostProfitTaxAndDiscountsOfBilledSalesOnly() {
        sale("ISSUED", "116", "16", "10", "40");
        sale("ISSUED", "232", "32", "0", "80");
        sale("VOIDED", "999", "99", "0", "1");
        em.flush();

        Map<String, Object> summary = service.summary(all());

        assertThat(kpi(summary, "fin-rev")).isEqualTo(348.0);
        assertThat(kpi(summary, "fin-cogs")).isEqualTo(120.0);
        // net of tax: (116-16) + (232-32) = 300; minus cost 120
        assertThat(kpi(summary, "fin-gross-profit")).isEqualTo(180.0);
        assertThat(kpi(summary, "fin-tax")).isEqualTo(48.0);
        assertThat(kpi(summary, "fin-discounts")).isEqualTo(10.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void revenueGivesATrendPerDayAndTheShareByCategory() {
        sale("ISSUED", "100", "0", "0", "10");
        em.flush();

        Map<String, Object> report = service.revenue(all());

        assertThat((List<?>) ((Map<String, Object>) report.get("trend")).get("points")).hasSize(30);
        List<Map<String, Object>> byCategory = (List<Map<String, Object>>) report.get("byCategory");
        assertThat(byCategory).hasSize(1);
        assertThat(byCategory.get(0).get("categoryName")).isEqualTo("Herramientas");
        assertThat(byCategory.get(0).get("revenueShare")).isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void taxesAreGroupedByTheRateInsideThePrice_andDiscountsByBranchAndSeller() {
        sale("ISSUED", "113", "13", "5", "40");      // 13 %
        sale("ISSUED", "113", "13", "0", "40");      // 13 %
        sale("ISSUED", "100", "0", "0", "40");       // exempt
        em.flush();

        Map<String, Object> report = service.taxesDiscounts(all());

        List<Map<String, Object>> byRate = (List<Map<String, Object>>) report.get("byRate");
        assertThat(byRate).hasSize(2);
        assertThat(byRate.get(0).get("rateLabel")).isEqualTo("Impuesto 13 %");
        assertThat(byRate.get(0).get("taxCollected")).isEqualTo(26.0);
        assertThat(byRate.get(1).get("rateLabel")).isEqualTo("Exento (0 %)");

        List<Map<String, Object>> byEmployee = (List<Map<String, Object>>) report.get("discountsByEmployee");
        assertThat(byEmployee).hasSize(1);
        assertThat(byEmployee.get(0).get("employeeName")).isEqualTo("Ana Pérez");
        assertThat(byEmployee.get(0).get("discountTotal")).isEqualTo(5.0);
        Map<String, Object> chart = (Map<String, Object>) report.get("discountsByBranchChart");
        assertThat((List<?>) chart.get("points")).hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void purchaseSpendCountsCommittedOrdersBySupplierAndLeavesOutDraftsAndCancelled() {
        Supplier supplier = persist(Supplier.builder().company(company).name("Proveedor Uno").build());
        order(supplier, "ISSUED", "500", 1);
        order(supplier, "COMPLETED", "300", 2);
        order(supplier, "DRAFT", "999", 3);
        order(supplier, "CANCELLED", "999", 4);
        em.flush();

        Map<String, Object> report = service.purchaseSpend(all());

        List<Map<String, Object>> bySupplier = (List<Map<String, Object>>) report.get("bySupplier");
        assertThat(bySupplier).hasSize(1);
        assertThat(bySupplier.get(0).get("orderCount")).isEqualTo(2L);
        assertThat(bySupplier.get(0).get("totalSpend")).isEqualTo(800.0);
        assertThat((List<?>) ((Map<String, Object>) report.get("trend")).get("points")).hasSize(30);
    }

    @Test
    @SuppressWarnings("unchecked")
    void cashClosingsShowExpectedVersusCountedPerRegister_withShortagesAndSurpluses() {
        CashierSession shortRegister = persist(CashierSession.builder().branch(branch).user(seller)
                .status("CLOSED").closedAt(java.time.Instant.now()).initialCash(new BigDecimal("100"))
                .expectedCash(new BigDecimal("500")).actualCash(new BigDecimal("480")).difference(new BigDecimal("-20")).build());
        persist(CashierSession.builder().branch(branch).user(seller)
                .status("CLOSED").closedAt(java.time.Instant.now()).initialCash(new BigDecimal("50"))
                .expectedCash(new BigDecimal("300")).actualCash(new BigDecimal("310")).difference(new BigDecimal("10")).build());
        persist(CashierSession.builder().branch(branch).user(seller)
                .status("CLOSED").closedAt(java.time.Instant.now()).initialCash(BigDecimal.ZERO)
                .expectedCash(new BigDecimal("200")).actualCash(new BigDecimal("200")).difference(BigDecimal.ZERO).build());
        persist(CashierSession.builder().branch(branch).user(seller).build());   // still open
        sale("ISSUED", "116", "16", "0", "40");
        em.flush();
        em.createQuery("UPDATE Invoice i SET i.cashierSession = :s WHERE i.company.id = :c")
                .setParameter("s", shortRegister).setParameter("c", company.getId()).executeUpdate();
        em.clear();

        Map<String, Object> report = service.cashClosings(all());
        List<Map<String, Object>> kpis = (List<Map<String, Object>>) report.get("kpis");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) report.get("rows");

        assertThat(rows).hasSize(5);   // the four above plus the register the seed opened
        assertThat(rows).extracting(r -> r.get("result")).contains("short", "over", "balanced", "open");
        assertThat(kpis.stream().filter(k -> "cc-closed".equals(k.get("id"))).findFirst().orElseThrow().get("value")).isEqualTo(3.0);
        assertThat(kpis.stream().filter(k -> "cc-with-difference".equals(k.get("id"))).findFirst().orElseThrow().get("value")).isEqualTo(2.0);
        assertThat(kpis.stream().filter(k -> "cc-shortage".equals(k.get("id"))).findFirst().orElseThrow().get("value")).isEqualTo(20.0);
        assertThat(kpis.stream().filter(k -> "cc-surplus".equals(k.get("id"))).findFirst().orElseThrow().get("value")).isEqualTo(10.0);
        Map<String, Object> shortRow = rows.stream().filter(r -> "short".equals(r.get("result"))).findFirst().orElseThrow();
        assertThat(shortRow.get("salesCount")).isEqualTo(1L);
        assertThat(shortRow.get("salesTotal")).isEqualTo(116.0);
        assertThat(shortRow.get("cashierName")).isEqualTo("Ana Pérez");
        assertThat(shortRow.get("difference")).isEqualTo(-20.0);
    }

    private void order(Supplier supplier, String status, String total, int n) {
        persist(PurchaseOrder.builder().company(company).branch(branch).supplier(supplier)
                .orderNumber("OC-" + tag + n).issueDate(LocalDate.now().minusDays(2)).status(status)
                .subtotal(new BigDecimal(total)).totalAmount(new BigDecimal(total)).createdBy(seller).build());
    }
}
