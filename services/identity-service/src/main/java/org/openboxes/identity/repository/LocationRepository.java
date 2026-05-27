package org.openboxes.identity.repository;

import org.openboxes.identity.entity.Location;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationRepository extends JpaRepository<Location, String> {
}
