package com.boxy.boxy.modules.catalog.repository;

import com.boxy.boxy.modules.catalog.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Optional<Product> findByIdAndDeletedAtIsNull(Long id);
    Optional<Product> findByIdAndCompanyIdAndDeletedAtIsNull(Long id, Long companyId);
    Optional<Product> findByCompanyIdAndSkuAndDeletedAtIsNull(Long companyId, String sku);
    Optional<Product> findByCompanyIdAndSkuIgnoreCaseAndDeletedAtIsNull(Long companyId, String sku);
    Optional<Product> findByCompanyIdAndBarcodeAndDeletedAtIsNull(Long companyId, String barcode);

    @Query("SELECT p FROM Product p LEFT JOIN p.brand sb LEFT JOIN p.category sc " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND " +
           // Name, SKU, barcode, brand and category (LEFT JOINs keep products that have neither).
           "(:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')) OR p.barcode LIKE CONCAT('%', :search, '%') " +
           "  OR LOWER(sb.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:categoryIds IS NULL OR p.category.id IN :categoryIds) AND " +
           "(:brandIds IS NULL OR p.brand.id IN :brandIds) AND " +
           "(:isActive IS NULL OR p.isActive = :isActive) AND " +
           "(:warehouseIds IS NULL OR EXISTS (" +
           "  SELECT 1 FROM StockLevel wsl WHERE wsl.product = p AND wsl.warehouse.id IN :warehouseIds AND wsl.quantityAvailable > 0" +
           ")) AND " +
           "(:stockStatusFilterActive = false OR (" +
           "  (:matchInStock = true AND (SELECT COALESCE(SUM(s1.quantityAvailable), 0) FROM StockLevel s1 WHERE s1.product = p) > p.minStockAlert) OR " +
           "  (:matchLowStock = true AND (SELECT COALESCE(SUM(s2.quantityAvailable), 0) FROM StockLevel s2 WHERE s2.product = p) > 0 AND (SELECT COALESCE(SUM(s2.quantityAvailable), 0) FROM StockLevel s2 WHERE s2.product = p) <= p.minStockAlert) OR " +
           "  (:matchOutOfStock = true AND (SELECT COALESCE(SUM(s3.quantityAvailable), 0) FROM StockLevel s3 WHERE s3.product = p) <= 0) OR " +
           "  (:matchInTransit = true AND (SELECT COALESCE(SUM(s4.quantityInTransit), 0) FROM StockLevel s4 WHERE s4.product = p) > 0)" +
           "))")
    Page<Product> findAllFiltered(
            @Param("companyId") Long companyId,
            @Param("search") String search,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("brandIds") List<Long> brandIds,
            @Param("isActive") Boolean isActive,
            @Param("warehouseIds") List<Long> warehouseIds,
            @Param("stockStatusFilterActive") boolean stockStatusFilterActive,
            @Param("matchInStock") boolean matchInStock,
            @Param("matchLowStock") boolean matchLowStock,
            @Param("matchOutOfStock") boolean matchOutOfStock,
            @Param("matchInTransit") boolean matchInTransit,
            Pageable pageable);

    /**
     * POS catalog page: the active products of a company, narrowed by the Step 1 filters. Kept apart
     * from {@link #findAllFiltered} (the Products screen's query) because POS filters differ: by unit,
     * by "has stock in this branch" and by a plain in/out-of-stock split on the total available.
     * {@code existence} is {@code null}, {@code "in-stock"} or {@code "out-of-stock"}.
     */
    @Query("SELECT p FROM Product p LEFT JOIN p.brand sb LEFT JOIN p.category sc " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           // The text search also matches the brand and the category name (LEFT JOINs: a product
           // with neither must still be findable by name / SKU / barcode).
           "(:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')) OR p.barcode LIKE CONCAT('%', :search, '%') " +
           "  OR LOWER(sb.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:categoryId IS NULL OR p.category.id = :categoryId) AND " +
           "(:brandId IS NULL OR p.brand.id = :brandId) AND " +
           "(:unitId IS NULL OR p.unit.id = :unitId) AND " +
           "(:branchId IS NULL OR EXISTS (" +
           "  SELECT 1 FROM StockLevel bsl WHERE bsl.product = p AND bsl.warehouse.branch.id = :branchId AND bsl.quantityAvailable > 0" +
           ")) AND " +
           "(:existence IS NULL OR " +
           "  (:existence = 'in-stock' AND (SELECT COALESCE(SUM(e1.quantityAvailable), 0) FROM StockLevel e1 WHERE e1.product = p) > 0) OR " +
           "  (:existence = 'out-of-stock' AND (SELECT COALESCE(SUM(e2.quantityAvailable), 0) FROM StockLevel e2 WHERE e2.product = p) <= 0))")
    Page<Product> searchCatalog(
            @Param("companyId") Long companyId,
            @Param("search") String search,
            @Param("categoryId") Long categoryId,
            @Param("brandId") Long brandId,
            @Param("unitId") Long unitId,
            @Param("branchId") Long branchId,
            @Param("existence") String existence,
            Pageable pageable);

    /**
     * The anonymous storefront's search: active, in-stock products only (summed available stock across
     * every warehouse &gt; 0), narrowed by text, category / brand lists and a sale-price range. Unlike
     * {@link #searchCatalog} the existence rule is not optional — a public visitor never sees a product
     * that cannot be bought.
     */
    @Query("SELECT p FROM Product p LEFT JOIN p.brand sb LEFT JOIN p.category sc " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           "(SELECT COALESCE(SUM(e.quantityAvailable), 0) FROM StockLevel e WHERE e.product = p) > 0 AND " +
           "(:search IS NULL OR LOWER(p.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(p.sku) LIKE LOWER(CONCAT('%', :search, '%')) " +
           "  OR LOWER(sb.name) LIKE LOWER(CONCAT('%', :search, '%')) OR LOWER(sc.name) LIKE LOWER(CONCAT('%', :search, '%'))) AND " +
           "(:categoryIds IS NULL OR p.category.id IN :categoryIds) AND " +
           "(:brandIds IS NULL OR p.brand.id IN :brandIds) AND " +
           "(:minPrice IS NULL OR p.sellingPrice >= :minPrice) AND " +
           "(:maxPrice IS NULL OR p.sellingPrice <= :maxPrice)")
    Page<Product> searchPublicCatalog(
            @Param("companyId") Long companyId,
            @Param("search") String search,
            @Param("categoryIds") List<Long> categoryIds,
            @Param("brandIds") List<Long> brandIds,
            @Param("minPrice") java.math.BigDecimal minPrice,
            @Param("maxPrice") java.math.BigDecimal maxPrice,
            Pageable pageable);

    /** [categoryId, categoryName, count] over the storefront's visible (active, in-stock) products. */
    @Query("SELECT c.id, c.name, COUNT(p) FROM Product p JOIN p.category c " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           "(SELECT COALESCE(SUM(e.quantityAvailable), 0) FROM StockLevel e WHERE e.product = p) > 0 " +
           "GROUP BY c.id, c.name ORDER BY c.name")
    List<Object[]> countPublicByCategory(@Param("companyId") Long companyId);

    /** [brandId, brandName, count] over the storefront's visible (active, in-stock) products. */
    @Query("SELECT b.id, b.name, COUNT(p) FROM Product p JOIN p.brand b " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           "(SELECT COALESCE(SUM(e.quantityAvailable), 0) FROM StockLevel e WHERE e.product = p) > 0 " +
           "GROUP BY b.id, b.name ORDER BY b.name")
    List<Object[]> countPublicByBrand(@Param("companyId") Long companyId);

    /** [MIN(sellingPrice), MAX(sellingPrice)] over the storefront's visible products; both null when there are none. */
    @Query("SELECT MIN(p.sellingPrice), MAX(p.sellingPrice) FROM Product p " +
           "WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           "(SELECT COALESCE(SUM(e.quantityAvailable), 0) FROM StockLevel e WHERE e.product = p) > 0")
    List<Object[]> findPublicPriceRange(@Param("companyId") Long companyId);

    /** Active products whose SKU or barcode is one of {@code codes} (already trimmed and lower-cased). */
    @Query("SELECT p FROM Product p WHERE p.company.id = :companyId AND p.deletedAt IS NULL AND p.isActive = true AND " +
           "(LOWER(p.sku) IN :codes OR LOWER(p.barcode) IN :codes)")
    List<Product> findActiveByCodes(@Param("companyId") Long companyId, @Param("codes") List<String> codes);

    List<Product> findTop10ByCompanyIdAndDeletedAtIsNullOrderByCreatedAtDesc(Long companyId);
    List<Product> findByCompanyIdAndDeletedAtIsNull(Long companyId);
    List<Product> findByCompanyIdAndIsActiveTrueAndDeletedAtIsNull(Long companyId);
    long countByCompanyIdAndIsActiveTrueAndDeletedAtIsNull(Long companyId);
    long countByCompanyIdAndDeletedAtIsNull(Long companyId);

    long countByCategoryIdAndDeletedAtIsNull(Long categoryId);
    long countByBrandIdAndDeletedAtIsNull(Long brandId);
    long countByUnitIdAndDeletedAtIsNull(Long unitId);
}
