package com.boxy.boxy.modules.catalog.service;

import com.boxy.boxy.core.exception.ResourceNotFoundException;
import com.boxy.boxy.modules.administration.entity.Branch;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.entity.Warehouse;
import com.boxy.boxy.modules.catalog.dto.PublicCatalogFacetsDto;
import com.boxy.boxy.modules.catalog.dto.PublicProductDto;
import com.boxy.boxy.modules.catalog.entity.Brand;
import com.boxy.boxy.modules.catalog.entity.Category;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.entity.UnitOfMeasure;
import com.boxy.boxy.modules.inventory.entity.StockLevel;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The anonymous storefront must show only active, in-stock products of the company behind the slug,
 * and must not leak another tenant's data. Checked against the real schema (rolled back afterwards).
 */
@SpringBootTest(properties = {
        "app.jwt.secret=test-only-jwt-signing-secret-do-not-use-in-any-real-environment-1234567890",
        "app.recaptcha.enabled=false"
})
@Transactional
class PublicCatalogServiceTest {

    @Autowired private PublicCatalogService service;
    @PersistenceContext private EntityManager em;

    private final String tag = UUID.randomUUID().toString().substring(0, 8);
    private Company company;
    private Company otherCompany;
    private UnitOfMeasure unit;
    private Warehouse warehouse;
    private Brand brand;
    private Category category;

    @BeforeEach
    void seed() {
        company = persist(Company.builder().name("Store " + tag).slug("store-" + tag).taxId("TAX-" + tag).build());
        otherCompany = persist(Company.builder().name("Other " + tag).slug("other-" + tag).taxId("TAXO-" + tag).build());
        unit = persist(UnitOfMeasure.builder().company(company).code("U" + tag).name("Unidad").symbol("und").build());
        brand = persist(Brand.builder().company(company).name("Marcazul" + tag).build());
        category = persist(Category.builder().company(company).code("C" + tag).name("Herramientas" + tag).build());
        Branch branch = persist(Branch.builder().company(company).code("B" + tag).name("Central").build());
        warehouse = persist(Warehouse.builder().branch(branch).code("W" + tag).name("Principal").build());
    }

    private <T> T persist(T entity) {
        em.persist(entity);
        return entity;
    }

    private Product product(String sku, String name, String price, String stock, boolean active) {
        Product product = persist(Product.builder().company(company).unit(unit).sku(sku + tag).name(name)
                .brand(brand).category(category).sellingPrice(new BigDecimal(price)).isActive(active).build());
        persist(StockLevel.builder().warehouse(warehouse).product(product)
                .quantityAvailable(new BigDecimal(stock)).quantityReserved(BigDecimal.ZERO)
                .quantityInTransit(BigDecimal.ZERO).build());
        return product;
    }

    private List<String> names(String search, List<Long> categoryIds, List<Long> brandIds, BigDecimal min, BigDecimal max) {
        em.flush();
        return service.searchProducts("store-" + tag, search, categoryIds, brandIds, min, max, "name", "asc", 1, 50)
                .map(PublicProductDto::getName).getContent();
    }

    @Test
    void onlyActiveInStockProductsAreListed() {
        product("A", "Martillo", "10", "5", true);
        product("B", "Taladro", "20", "0", true);
        product("C", "Sierra", "30", "9", false);

        assertThat(names(null, null, null, null, null)).containsExactly("Martillo");
    }

    @Test
    void anotherCompanysProductsNeverAppear() {
        product("A", "Martillo", "10", "5", true);
        UnitOfMeasure otherUnit = persist(UnitOfMeasure.builder().company(otherCompany).code("X" + tag).name("Unidad").symbol("und").build());
        Product foreign = persist(Product.builder().company(otherCompany).unit(otherUnit).sku("F" + tag).name("Ajeno")
                .sellingPrice(BigDecimal.TEN).build());
        persist(StockLevel.builder().warehouse(warehouse).product(foreign).quantityAvailable(BigDecimal.TEN)
                .quantityReserved(BigDecimal.ZERO).quantityInTransit(BigDecimal.ZERO).build());

        assertThat(names(null, null, null, null, null)).containsExactly("Martillo");
    }

    @Test
    void filtersByPriceRange() {
        product("A", "Martillo", "10", "5", true);
        product("B", "Taladro", "50", "5", true);
        product("C", "Sierra", "90", "5", true);

        assertThat(names(null, null, null, new BigDecimal("20"), new BigDecimal("60"))).containsExactly("Taladro");
        assertThat(names(null, null, null, new BigDecimal("50"), null)).containsExactly("Sierra", "Taladro");
    }

    @Test
    void filtersByCategoryBrandAndText() {
        product("A", "Martillo", "10", "5", true);

        assertThat(names("marti", List.of(category.getId()), List.of(brand.getId()), null, null)).containsExactly("Martillo");
        assertThat(names(null, List.of(category.getId() + 999), null, null, null)).isEmpty();
    }

    @Test
    void availabilityIsACoarseLabelNeverAQuantity() {
        Product low = product("A", "Martillo", "10", "2", true);
        low.setMinStockAlert(new BigDecimal("5"));
        product("B", "Taladro", "20", "40", true);
        em.flush();

        var page = service.searchProducts("store-" + tag, null, null, null, null, null, "name", "asc", 1, 50);

        assertThat(page.getContent()).extracting(PublicProductDto::getAvailability)
                .containsExactly(PublicProductDto.LOW_STOCK, PublicProductDto.IN_STOCK);
    }

    @Test
    void sortsByPriceDescending() {
        product("A", "Martillo", "10", "5", true);
        product("B", "Taladro", "50", "5", true);
        em.flush();

        var page = service.searchProducts("store-" + tag, null, null, null, null, null, "price", "desc", 1, 50);

        assertThat(page.map(PublicProductDto::getName).getContent()).containsExactly("Taladro", "Martillo");
    }

    @Test
    void facetsCountOnlyVisibleProducts() {
        product("A", "Martillo", "10", "5", true);
        product("B", "Taladro", "50", "5", true);
        product("C", "Sierra", "90", "0", true);
        em.flush();

        PublicCatalogFacetsDto facets = service.getFacets("store-" + tag);

        assertThat(facets.getCategories()).singleElement().satisfies(f -> assertThat(f.getCount()).isEqualTo(2));
        assertThat(facets.getBrands()).singleElement().satisfies(f -> assertThat(f.getCount()).isEqualTo(2));
        assertThat(facets.getPriceRange().getMin()).isEqualByComparingTo("10");
        assertThat(facets.getPriceRange().getMax()).isEqualByComparingTo("50");
    }

    @Test
    void profileExposesBrandingOnly() {
        assertThat(service.getProfile("store-" + tag).getName()).isEqualTo("Store " + tag);
    }

    @Test
    void theSlugIsMatchedIgnoringCase() {
        company.setSlug("latinaTools-" + tag);
        em.flush();

        assertThat(service.getProfile("latinaTools-" + tag).getSlug()).isEqualTo("latinaTools-" + tag);
        assertThat(service.getProfile("LATINATOOLS-" + tag).getName()).isEqualTo("Store " + tag);
        assertThat(service.getProfile("  latinatools-" + tag + " ").getName()).isEqualTo("Store " + tag);
    }

    @Test
    void unknownOrDisabledSlugIsNotFound() {
        company.setSlug(null);
        em.flush();

        assertThatThrownBy(() -> service.getProfile("store-" + tag)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.getProfile("does-not-exist-" + tag)).isInstanceOf(ResourceNotFoundException.class);
    }
}
