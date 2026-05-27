package org.openboxes.identity.dto;

import org.openboxes.identity.entity.User;

public record UserLookupResponse(String id, String username, String firstName, String lastName, String email) {
    public static UserLookupResponse from(User user) {
        return new UserLookupResponse(
            user.getId(),
            user.getUsername(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail()
        );
    }
}
