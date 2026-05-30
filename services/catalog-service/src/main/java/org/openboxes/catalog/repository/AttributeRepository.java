package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Attribute;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AttributeRepository extends JpaRepository<Attribute, String> {
    // additional query methods added per service needs in T6
}
