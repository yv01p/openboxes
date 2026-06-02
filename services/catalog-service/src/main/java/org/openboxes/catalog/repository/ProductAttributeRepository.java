package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

// Bare repo. NO @EntityGraph: the DTO reads only FK proxy ids (no DB hit), so find* is already a
// single SELECT — an @EntityGraph would only add LEFT JOINs we don't need.
public interface ProductAttributeRepository extends JpaRepository<ProductAttribute, String> {
    // CUT form-load filter: a supplier's saved attribute values (productSupplier is a @ManyToOne, so
    // this resolves to product_supplier_id = ?). Avoids the client fetching+filtering the whole table.
    List<ProductAttribute> findByProductSupplierId(String productSupplierId);
}
