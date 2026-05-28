package org.openboxes.location.repository;

import org.openboxes.location.entity.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationTypeRepository extends JpaRepository<LocationType, String> {}
