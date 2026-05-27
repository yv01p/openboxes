package org.openboxes.identity.service;

public class SignupDisabledException extends RuntimeException {
    public SignupDisabledException(String message) {
        super(message);
    }
}
