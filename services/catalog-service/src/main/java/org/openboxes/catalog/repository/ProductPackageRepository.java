package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductPackage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductPackageRepository extends JpaRepository<ProductPackage, String> {
    // Cutover read-GET — load all packages for a given ProductSupplier.
    List<ProductPackage> findByProductSupplierId(String productSupplierId);

    // CUT upsert finder — ports the Grails setPackageData find:
    //   productSupplier.productPackages.find { it.uom == command.uom && it.quantity == command.productPackageQuantity }
    // Match a supplier's existing package by (productSupplier, uom, quantity). The service only calls
    // this when uomId AND quantity are both non-null (the null guard skips package creation otherwise),
    // so this derived query never needs an IS NULL branch — both predicates compare against non-null
    // values. (The find-or-create here is what makes the POST an UPSERT instead of create-only.)
    List<ProductPackage> findByProductSupplier_IdAndUom_IdAndQuantity(
        String productSupplierId, String uomId, Integer quantity);
}
