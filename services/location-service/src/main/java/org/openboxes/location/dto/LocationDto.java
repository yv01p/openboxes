package org.openboxes.location.dto;

import org.openboxes.location.entity.Location;
import java.util.Set;

public record LocationDto(
    String id,
    String name,
    String description,
    String locationNumber,
    Boolean active,
    Integer sortOrder,
    String locationTypeId,
    String locationTypeCode,
    String locationTypeName,
    String locationGroupId,
    String parentLocationId,
    String zoneId,
    String organizationId,
    String managerId,
    String addressId,
    Set<String> supportedActivities
) {
    public static LocationDto from(Location l) {
        return new LocationDto(
            l.getId(),
            l.getName(),
            l.getDescription(),
            l.getLocationNumber(),
            l.getActive(),
            l.getSortOrder(),
            l.getLocationType() == null ? null : l.getLocationType().getId(),
            l.getLocationType() == null || l.getLocationType().getLocationTypeCode() == null ? null : l.getLocationType().getLocationTypeCode().name(),
            l.getLocationType() == null ? null : l.getLocationType().getName(),
            l.getLocationGroupId(),
            l.getParentLocationId(),
            l.getZoneId(),
            l.getOrganizationId(),
            l.getManagerId(),
            l.getAddressId(),
            l.getSupportedActivities()
        );
    }
}
