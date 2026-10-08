package com.boxy.boxy.modules.catalog.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** What an anonymous visitor may know about a company: branding and currency only. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PublicStoreProfileDto {
    private String slug;
    private String name;
    private String logoUrl;
    private String slogan;
    private String currencyCode;
    private String currencySymbol;
    private String termProduct;
    private String termProducts;
}
