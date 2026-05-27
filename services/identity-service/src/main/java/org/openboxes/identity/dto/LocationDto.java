package org.openboxes.identity.dto;

import org.openboxes.identity.entity.Location;

public record LocationDto(String id, String name) {
    public static LocationDto from(Location location) {
        return new LocationDto(
            location.getId(),
            location.getName()
        );
    }
}
