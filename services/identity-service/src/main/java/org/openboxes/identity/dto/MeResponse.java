package org.openboxes.identity.dto;

import org.openboxes.identity.service.MeResult;

import java.util.List;

public record MeResponse(UserDto user, LocationDto location, List<String> roleIds) {
    public static MeResponse from(MeResult result) {
        return new MeResponse(
            UserDto.from(result.user()),
            result.location() != null ? LocationDto.from(result.location()) : null,
            result.roleIds()
        );
    }
}
