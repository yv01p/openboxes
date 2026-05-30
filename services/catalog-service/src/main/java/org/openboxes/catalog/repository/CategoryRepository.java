package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Category;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CategoryRepository extends JpaRepository<Category, String> {
    java.util.List<Category> findByParentCategoryIsNull();  // root categories
}
