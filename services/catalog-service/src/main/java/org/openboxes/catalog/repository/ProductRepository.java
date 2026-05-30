package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {
    // additional query methods added per service needs in T6
}
