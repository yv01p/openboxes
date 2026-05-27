package org.openboxes.identity.dto;

public record SignupRequest(
    String username,
    String password,
    String firstName,
    String lastName,
    String email,
    String phoneNumber,
    String recaptchaToken
) {}
