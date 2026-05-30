package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.UnitOfMeasureClass;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitOfMeasureClassRepository extends JpaRepository<UnitOfMeasureClass, String> {
    // additional query methods added per service needs in T6
}
