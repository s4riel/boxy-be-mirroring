package com.boxy.boxy.modules.administration.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

@Entity
@Table(name = "companies")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Company {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 150)
    private String name;

    /** URL key of the public catalog (/catalogo/{slug}). {@code null} = public catalog disabled. */
    @Column(length = 60, unique = true)
    private String slug;

    @Column(name = "tax_id", nullable = false, length = 50)
    private String taxId;

    @Column(length = 100)
    private String email;

    @Column(length = 50)
    private String phone;

    @Column(length = 255)
    private String address;

    /** City/country line shown under the address on printed documents (e.g. "Cochabamba - Bolivia"). */
    @Column(length = 100)
    private String city;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(name = "trade_name", length = 150)
    private String tradeName;

    @Column(length = 255)
    private String slogan;

    @Column(name = "primary_color", nullable = false, length = 10)
    @Builder.Default
    private String primaryColor = "#2563eb";

    @Column(name = "primary_hover", nullable = false, length = 10)
    @Builder.Default
    private String primaryHover = "#1d4ed8";

    @Column(name = "primary_subtle_bg", nullable = false, length = 10)
    @Builder.Default
    private String primarySubtleBg = "#eff6ff";

    @Column(name = "currency_code", nullable = false, length = 10)
    @Builder.Default
    private String currencyCode = "USD";

    @Column(name = "currency_symbol", nullable = false, length = 5)
    @Builder.Default
    private String currencySymbol = "$";

    @Column(name = "tax_name", nullable = false, length = 50)
    @Builder.Default
    private String taxName = "IVA";

    @Column(name = "default_tax_rate", nullable = false, precision = 5, scale = 2)
    @Builder.Default
    private java.math.BigDecimal defaultTaxRate = new java.math.BigDecimal("13.00");

    @Column(name = "tax_id_label", nullable = false, length = 20)
    @Builder.Default
    private String taxIdLabel = "NIT";

    @Column(nullable = false, length = 50)
    @Builder.Default
    private String timezone = "UTC";

    @Column(name = "allow_negative_stock", nullable = false)
    @Builder.Default
    private Boolean allowNegativeStock = false;

    @Column(name = "has_pos", nullable = false)
    @Builder.Default
    private Boolean hasPos = true;

    @Column(name = "has_batches", nullable = false)
    @Builder.Default
    private Boolean hasBatches = false;

    @Column(name = "has_variants", nullable = false)
    @Builder.Default
    private Boolean hasVariants = false;

    @Column(name = "has_transfers", nullable = false)
    @Builder.Default
    private Boolean hasTransfers = true;

    @Column(name = "has_purchasing", nullable = false)
    @Builder.Default
    private Boolean hasPurchasing = true;

    @Column(name = "has_quotations", nullable = false)
    @Builder.Default
    private Boolean hasQuotations = true;

    @Column(name = "has_multi_branch", nullable = false)
    @Builder.Default
    private Boolean hasMultiBranch = true;

    @Column(name = "unit_precision", nullable = false, length = 20)
    @Builder.Default
    private String unitPrecision = "integer";

    @Column(name = "term_product", nullable = false, length = 50)
    @Builder.Default
    private String termProduct = "Producto";

    @Column(name = "term_products", nullable = false, length = 50)
    @Builder.Default
    private String termProducts = "Productos";

    @Column(name = "term_inventory", nullable = false, length = 50)
    @Builder.Default
    private String termInventory = "Inventario";

    @Column(name = "term_customer", nullable = false, length = 50)
    @Builder.Default
    private String termCustomer = "Cliente";

    @Column(name = "term_pos", nullable = false, length = 50)
    @Builder.Default
    private String termPos = "Punto de Venta";

    @Column(name = "is_active", nullable = false)
    @Builder.Default
    private Boolean isActive = true;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "deleted_at")
    private Instant deletedAt;
}
