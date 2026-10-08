package com.boxy.boxy.modules.catalog.service;

import com.boxy.boxy.core.exception.ResourceNotFoundException;
import com.boxy.boxy.modules.administration.entity.Company;
import com.boxy.boxy.modules.administration.repository.CompanyRepository;
import com.boxy.boxy.modules.catalog.dto.PublicCatalogFacetsDto;
import com.boxy.boxy.modules.catalog.dto.PublicProductDto;
import com.boxy.boxy.modules.catalog.dto.PublicStoreProfileDto;
import com.boxy.boxy.modules.catalog.entity.Product;
import com.boxy.boxy.modules.catalog.repository.ProductRepository;
import com.boxy.boxy.modules.inventory.repository.StockLevelRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Read-only catalog for anonymous visitors. The tenant comes from the URL slug, never from a token
 * ({@code SecurityUtils} is intentionally not used here), and every query is scoped to that company
 * and to active, in-stock products.
 */
@Service
@RequiredArgsConstructor
public class PublicCatalogService {

    static final int MAX_PAGE_SIZE = 48;
    static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_SEARCH_LENGTH = 100;

    private final CompanyRepository companyRepository;
    private final ProductRepository productRepository;
    private final StockLevelRepository stockLevelRepository;

    @Transactional(readOnly = true)
    public PublicStoreProfileDto getProfile(String slug) {
        Company company = requireCompany(slug);
        String displayName = company.getTradeName() != null && !company.getTradeName().isBlank()
                ? company.getTradeName()
                : company.getName();
        return PublicStoreProfileDto.builder()
                .slug(company.getSlug())
                .name(displayName)
                .logoUrl(company.getLogoUrl())
                .slogan(company.getSlogan())
                .currencyCode(company.getCurrencyCode())
                .currencySymbol(company.getCurrencySymbol())
                .termProduct(company.getTermProduct())
                .termProducts(company.getTermProducts())
                .build();
    }

    @Transactional(readOnly = true)
    public PublicCatalogFacetsDto getFacets(String slug) {
        Long companyId = requireCompany(slug).getId();
        Object[] range = productRepository.findPublicPriceRange(companyId).stream().findFirst().orElse(new Object[] {null, null});
        return PublicCatalogFacetsDto.builder()
                .categories(toFacets(productRepository.countPublicByCategory(companyId)))
                .brands(toFacets(productRepository.countPublicByBrand(companyId)))
                .priceRange(PublicCatalogFacetsDto.PriceRange.builder()
                        .min((BigDecimal) range[0])
                        .max((BigDecimal) range[1])
                        .build())
                .build();
    }

    @Transactional(readOnly = true)
    public Page<PublicProductDto> searchProducts(String slug, String search, List<Long> categoryIds, List<Long> brandIds,
                                                 BigDecimal minPrice, BigDecimal maxPrice, String sortField,
                                                 String sortDirection, int page, Integer pageSize) {
        Long companyId = requireCompany(slug).getId();
        int size = Math.min(Math.max(pageSize != null ? pageSize : DEFAULT_PAGE_SIZE, 1), MAX_PAGE_SIZE);
        Pageable pageable = PageRequest.of(Math.max(page, 1) - 1, size, toSort(sortField, sortDirection));

        Page<Product> products = productRepository.searchPublicCatalog(
                companyId, normalizeSearch(search), emptyToNull(categoryIds), emptyToNull(brandIds), minPrice, maxPrice, pageable);

        // One grouped query for the whole page — not one per product.
        Map<Long, BigDecimal> available = new HashMap<>();
        if (!products.isEmpty()) {
            List<Long> ids = products.getContent().stream().map(Product::getId).toList();
            for (Object[] row : stockLevelRepository.sumAvailableByProductIds(ids)) {
                available.put(((Number) row[0]).longValue(), new BigDecimal(row[1].toString()));
            }
        }
        return products.map(product -> toDto(product, available.getOrDefault(product.getId(), BigDecimal.ZERO)));
    }

    private Company requireCompany(String slug) {
        // A missing, blank or disabled slug all look the same to the caller: there is no such catalog.
        if (slug == null || slug.isBlank()) {
            throw new ResourceNotFoundException("Catalog not found.");
        }
        return companyRepository.findBySlugIgnoreCaseAndIsActiveTrueAndDeletedAtIsNull(slug.trim())
                .orElseThrow(() -> new ResourceNotFoundException("Catalog not found."));
    }

    /** {@code sortField} is one of name | price | createdAt; anything else falls back to name A→Z. */
    private static Sort toSort(String sortField, String sortDirection) {
        Sort.Direction direction = "desc".equalsIgnoreCase(sortDirection) ? Sort.Direction.DESC : Sort.Direction.ASC;
        String property = switch (sortField == null ? "" : sortField) {
            case "price" -> "sellingPrice";
            case "createdAt" -> "createdAt";
            default -> "name";
        };
        return Sort.by(direction, property).and(Sort.by("id"));
    }

    private static String normalizeSearch(String search) {
        if (search == null || search.isBlank()) {
            return null;
        }
        String trimmed = search.trim();
        return trimmed.length() > MAX_SEARCH_LENGTH ? trimmed.substring(0, MAX_SEARCH_LENGTH) : trimmed;
    }

    private static <T> List<T> emptyToNull(List<T> values) {
        return values == null || values.isEmpty() ? null : values;
    }

    private static List<PublicCatalogFacetsDto.Facet> toFacets(List<Object[]> rows) {
        return rows.stream()
                .map(row -> PublicCatalogFacetsDto.Facet.builder()
                        .id(((Number) row[0]).longValue())
                        .name((String) row[1])
                        .count(((Number) row[2]).longValue())
                        .build())
                .toList();
    }

    private static PublicProductDto toDto(Product p, BigDecimal availableQuantity) {
        BigDecimal minStock = Objects.requireNonNullElse(p.getMinStockAlert(), BigDecimal.ZERO);
        boolean low = minStock.signum() > 0 && availableQuantity.compareTo(minStock) <= 0;
        return PublicProductDto.builder()
                .id(p.getId())
                .sku(p.getSku())
                .name(p.getName())
                .description(p.getDescription())
                .price(p.getSellingPrice())
                .imageUrl(p.getImageUrl())
                .categoryId(p.getCategory() != null ? p.getCategory().getId() : null)
                .categoryName(p.getCategory() != null ? p.getCategory().getName() : null)
                .brandId(p.getBrand() != null ? p.getBrand().getId() : null)
                .brandName(p.getBrand() != null ? p.getBrand().getName() : null)
                .unitCode(p.getUnit() != null ? p.getUnit().getSymbol() : null)
                .unitName(p.getUnit() != null ? p.getUnit().getName() : null)
                .availability(low ? PublicProductDto.LOW_STOCK : PublicProductDto.IN_STOCK)
                .build();
    }
}
