package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductSupplier;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, String> {
    // Plan-specified finders, pre-staged for downstream filtered queries (e.g. a product's suppliers /
    // a supplier's products) reused by T3–T5. Not yet called by T2's unfiltered list() — kept per plan
    // Step 4, mirroring the pre-staged-finder convention in SynonymRepository.
    List<ProductSupplier> findByProductId(String productId);
    List<ProductSupplier> findBySupplierId(String supplierId);
}
