package org.openboxes.identity.dto;

import org.openboxes.identity.entity.User;

public record SignupResponse(
    String id,
    String username,
    String email,
    boolean active
) {
    public static SignupResponse from(User user) {
        return new SignupResponse(
            user.getId(),
            user.getUsername(),
            user.getEmail(),
            Boolean.TRUE.equals(user.getActive())
        );
    }
}
