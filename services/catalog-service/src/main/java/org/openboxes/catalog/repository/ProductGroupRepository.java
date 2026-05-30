package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductGroupRepository extends JpaRepository<ProductGroup, String> {
    // additional query methods added per service needs in T6
}
