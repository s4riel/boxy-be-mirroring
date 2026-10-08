package com.boxy.boxy.modules.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

/** Filter options for the storefront sidebar, counted over the visible (active, in-stock) products. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicCatalogFacetsDto {
    private List<Facet> categories;
    private List<Facet> brands;
    private PriceRange priceRange;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Facet {
        private Long id;
        private String name;
        private long count;
    }

    /** Both bounds are {@code null} when the catalog has no visible products. */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PriceRange {
        private BigDecimal min;
        private BigDecimal max;
    }
}
