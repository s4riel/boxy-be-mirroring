package com.boxy.boxy.modules.catalog.controller;

import com.boxy.boxy.core.response.ApiResponse;
import com.boxy.boxy.core.response.PageMeta;
import com.boxy.boxy.modules.catalog.dto.PublicCatalogFacetsDto;
import com.boxy.boxy.modules.catalog.dto.PublicProductDto;
import com.boxy.boxy.modules.catalog.dto.PublicStoreProfileDto;
import com.boxy.boxy.modules.catalog.service.PublicCatalogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;

/**
 * Anonymous, read-only storefront for a company's in-stock products.
 *
 * <p>This is the only controller without {@code @PreAuthorize}: {@code SecurityConfig} permits
 * {@code GET /api/v1/public/**}, and the tenant is the {@code {slug}} path variable. It must stay
 * read-only and must never return cost, quantity or audit data (see the public DTOs).
 * Exempted in {@code PreAuthorizeCoverageTest}.
 */
@RestController
@RequestMapping("/api/v1/public/catalog/{slug}")
@RequiredArgsConstructor
@Tag(name = "Public Catalog", description = "Anonymous storefront: in-stock products of a company, found by its slug")
public class PublicCatalogController {

    private final PublicCatalogService publicCatalogService;

    @GetMapping
    @Operation(summary = "Public profile of the store (name, logo, currency)")
    public ResponseEntity<ApiResponse<PublicStoreProfileDto>> getProfile(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(publicCatalogService.getProfile(slug)));
    }

    @GetMapping("/facets")
    @Operation(summary = "Category / brand counts and price range of the in-stock products")
    public ResponseEntity<ApiResponse<PublicCatalogFacetsDto>> getFacets(@PathVariable String slug) {
        return ResponseEntity.ok(ApiResponse.ok(publicCatalogService.getFacets(slug)));
    }

    @GetMapping("/products")
    @Operation(summary = "Search the in-stock products, one page at a time")
    public ResponseEntity<ApiResponse<List<PublicProductDto>>> searchProducts(
            @PathVariable String slug,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(required = false) Integer pageSize,
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "sortField", required = false) String sortField,
            @RequestParam(name = "sortDirection", required = false) String sortDirection,
            @RequestParam(name = "filter.categoryId", required = false) String categoryIds,
            @RequestParam(name = "filter.brandId", required = false) String brandIds,
            @RequestParam(name = "filter.minPrice", required = false) BigDecimal minPrice,
            @RequestParam(name = "filter.maxPrice", required = false) BigDecimal maxPrice) {
        Page<PublicProductDto> result = publicCatalogService.searchProducts(
                slug, search, parseIds(categoryIds), parseIds(brandIds), minPrice, maxPrice,
                sortField, sortDirection, page, pageSize);
        return ResponseEntity.ok(ApiResponse.paged(result.getContent(), PageMeta.from(result)));
    }

    /** Comma-separated ids; blank and non-numeric entries are ignored rather than rejected. */
    private static List<Long> parseIds(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(part -> part.matches("\\d{1,18}"))
                .map(Long::valueOf)
                .toList();
    }
}
