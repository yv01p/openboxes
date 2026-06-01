package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductCatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductCatalogItemRepository extends JpaRepository<ProductCatalogItem, String> {
}
