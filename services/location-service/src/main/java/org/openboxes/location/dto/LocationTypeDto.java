package org.openboxes.location.dto;

import org.openboxes.location.entity.LocationType;
import java.util.Set;

public record LocationTypeDto(
    String id,
    String name,
    String description,
    String locationTypeCode,
    Integer sortOrder,
    Set<String> supportedActivities
) {
    public static LocationTypeDto from(LocationType lt) {
        return new LocationTypeDto(
            lt.getId(),
            lt.getName(),
            lt.getDescription(),
            lt.getLocationTypeCode() == null ? null : lt.getLocationTypeCode().name(),
            lt.getSortOrder(),
            lt.getSupportedActivities()
        );
    }
}
