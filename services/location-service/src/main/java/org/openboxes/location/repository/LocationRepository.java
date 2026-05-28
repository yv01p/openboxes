package org.openboxes.location.repository;

import org.openboxes.location.entity.Location;
import org.openboxes.location.enums.LocationTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LocationRepository extends JpaRepository<Location, String> {
    @Query("SELECT l FROM Location l WHERE l.id = :id AND l.locationType.locationTypeCode NOT IN :internalTypes")
    Optional<Location> findByIdExcludingInternal(@Param("id") String id, @Param("internalTypes") Set<LocationTypeCode> internalTypes);

    @Query("SELECT l FROM Location l WHERE " +
           "(:type IS NULL OR l.locationType.locationTypeCode = :type) AND " +
           "(:parentId IS NULL OR l.parentLocationId = :parentId) AND " +
           "(:active IS NULL OR l.active = :active) AND " +
           "(:includeInternal = TRUE OR l.locationType.locationTypeCode NOT IN :internalTypes)")
    List<Location> findFiltered(@Param("type") LocationTypeCode type,
                                @Param("parentId") String parentId,
                                @Param("active") Boolean active,
                                @Param("includeInternal") boolean includeInternal,
                                @Param("internalTypes") Set<LocationTypeCode> internalTypes);
}
