package org.openboxes.identity.dto;

import org.openboxes.identity.service.ChooseLocationResult;

import java.util.List;

public record ChooseLocationResponse(UserDto user, LocationDto location, List<String> roleIds) {
    public static ChooseLocationResponse from(ChooseLocationResult result) {
        return new ChooseLocationResponse(
            UserDto.from(result.user()),
            LocationDto.from(result.location()),
            result.roleIds()
        );
    }
}
