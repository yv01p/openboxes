package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.Tag;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TagRepository extends JpaRepository<Tag, String> {
    // additional query methods added per service needs in T6
}
