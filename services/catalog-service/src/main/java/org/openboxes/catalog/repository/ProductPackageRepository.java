package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductPackage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductPackageRepository extends JpaRepository<ProductPackage, String> {
    // Cutover read-GET — load all packages for a given ProductSupplier.
    List<ProductPackage> findByProductSupplierId(String productSupplierId);

    // App-layer friendly pre-check porting the Grails validator's null-safe
    // findWhere(uom, product, quantity, productSupplier) tuple lookup. GORM turns null FKs into
    // `IS NULL`; product/uom/productSupplier are all nullable, so a naive derived query or one using
    // implicit inner joins (pp.uom.id) would WRONGLY drop null-FK rows. Use explicit LEFT JOINs with
    // null-guarded predicates. quantity is NOT NULL so it needs no null branch.
    @Query("SELECT pp FROM ProductPackage pp " +
           "LEFT JOIN pp.product p LEFT JOIN pp.productSupplier ps LEFT JOIN pp.uom u " +
           "WHERE pp.quantity = :quantity " +
           "AND ((:productId IS NULL AND p.id IS NULL) OR p.id = :productId) " +
           "AND ((:productSupplierId IS NULL AND ps.id IS NULL) OR ps.id = :productSupplierId) " +
           "AND ((:uomId IS NULL AND u.id IS NULL) OR u.id = :uomId)")
    List<ProductPackage> findMatchingTuple(@Param("productId") String productId,
                                           @Param("productSupplierId") String productSupplierId,
                                           @Param("uomId") String uomId,
                                           @Param("quantity") Integer quantity);
}
