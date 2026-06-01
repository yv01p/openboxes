package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductAttribute;
import org.springframework.data.jpa.repository.JpaRepository;

// Bare repo. NO @EntityGraph: the DTO reads only FK proxy ids (no DB hit), so findAll() is already a
// single SELECT — an @EntityGraph would only add LEFT JOINs we don't need.
public interface ProductAttributeRepository extends JpaRepository<ProductAttribute, String> {
}
