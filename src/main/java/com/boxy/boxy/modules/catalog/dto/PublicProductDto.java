package com.boxy.boxy.modules.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

/**
 * A product as the anonymous storefront sees it. Deliberately has no cost, barcode, minimum stock,
 * audit or quantity fields: availability is a coarse label, never the real number on the shelf.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicProductDto {
    /** {@code "in-stock"} or {@code "low-stock"} (at or below the product's minimum-stock alert). */
    public static final String IN_STOCK = "in-stock";
    public static final String LOW_STOCK = "low-stock";

    private Long id;
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private String imageUrl;
    private Long categoryId;
    private String categoryName;
    private Long brandId;
    private String brandName;
    private String unitCode;
    private String unitName;
    private String availability;
}
