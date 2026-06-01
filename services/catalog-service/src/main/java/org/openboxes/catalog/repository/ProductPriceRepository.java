package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductPrice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, String> {
    // No custom finders needed at T5: prices are written through the package endpoint and read by id.
}
