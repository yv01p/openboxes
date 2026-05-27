package org.openboxes.identity.dto;

public record ChangePasswordRequest(String currentPassword, String newPassword) {}
