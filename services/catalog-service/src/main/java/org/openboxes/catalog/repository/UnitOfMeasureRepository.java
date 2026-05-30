package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.UnitOfMeasure;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UnitOfMeasureRepository extends JpaRepository<UnitOfMeasure, String> {
    // additional query methods added per service needs in T6
}
