package org.openboxes.identity.dto;

import org.openboxes.identity.service.LoginResult;

public record LoginResponse(UserDto user, LocationDto location) {
    public static LoginResponse from(LoginResult result) {
        return new LoginResponse(
            UserDto.from(result.user()),
            result.location() != null ? LocationDto.from(result.location()) : null
        );
    }
}
