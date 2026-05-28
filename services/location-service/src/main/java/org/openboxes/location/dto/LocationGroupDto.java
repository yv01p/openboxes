package org.openboxes.location.dto;

import org.openboxes.location.entity.LocationGroup;

public record LocationGroupDto(
    String id,
    String name,
    String addressId
) {
    public static LocationGroupDto from(LocationGroup lg) {
        return new LocationGroupDto(lg.getId(), lg.getName(), lg.getAddressId());
    }
}
