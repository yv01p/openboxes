package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductCatalog;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCatalogRepository extends JpaRepository<ProductCatalog, String> {
}
