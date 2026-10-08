package com.boxy.boxy.modules.catalog.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.math.BigDecimal;

@Data
public class CreateProductRequest {
    private Long categoryId;
    private Long brandId;

    @NotNull(message = "Unit of measure ID is required")
    @JsonAlias({"unitId", "unitOfMeasureId"})
    private Long unitId;

    public Long getUnitOfMeasureId() {
        return unitId;
    }
    public void setUnitOfMeasureId(Long unitOfMeasureId) {
        this.unitId = unitOfMeasureId;
    }

    private Long taxId;

    @NotBlank(message = "El código es requerido")
    private String sku;

    private String barcode;

    @NotBlank(message = "Product name is required")
    private String name;

    private String description;

    @NotNull(message = "Cost price is required")
    @DecimalMin(value = "0.0", message = "Cost price must be >= 0")
    @JsonAlias({"costPrice", "purchasePrice"})
    private BigDecimal costPrice;

    @NotNull(message = "Selling price is required")
    @DecimalMin(value = "0.0", message = "Selling price must be >= 0")
    @JsonAlias({"sellingPrice", "salePrice"})
    private BigDecimal sellingPrice;

    @JsonAlias({"minStockAlert", "minStock"})
    private BigDecimal minStockAlert = BigDecimal.ZERO;

    @JsonAlias({"imageUrl", "thumbnailUrl"})
    private String imageUrl;

    /** Opening stock seeded on create only — never re-applied on update. */
    @DecimalMin(value = "0.0", message = "Initial stock must be >= 0")
    private BigDecimal initialStock;

    /** Warehouse to receive {@link #initialStock}. Falls back server-side when omitted. */
    private Long initialWarehouseId;
}
