package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductRepository extends JpaRepository<Product, String> {
    // additional query methods added per service needs in T6

    // RC-16 (T4): global distinct non-empty Product.abcClass (excludes null and '').
    @org.springframework.data.jpa.repository.Query(
        "select distinct p.abcClass from Product p where p.abcClass is not null and p.abcClass <> ''")
    java.util.List<String> findDistinctAbcClasses();
}
