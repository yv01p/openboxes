package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductTypeRepository extends JpaRepository<ProductType, String> {
    // additional query methods added per service needs in T6
}
