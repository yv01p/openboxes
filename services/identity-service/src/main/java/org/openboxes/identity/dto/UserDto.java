package org.openboxes.identity.dto;

import org.openboxes.identity.entity.User;

public record UserDto(String id, String username, String firstName, String lastName, String email) {
    public static UserDto from(User user) {
        return new UserDto(
            user.getId(),
            user.getUsername(),
            user.getFirstName(),
            user.getLastName(),
            user.getEmail()
        );
    }
}
