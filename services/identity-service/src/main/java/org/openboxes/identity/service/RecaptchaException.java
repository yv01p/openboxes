package org.openboxes.identity.service;

public class RecaptchaException extends RuntimeException {
    public RecaptchaException(String message) {
        super(message);
    }
}
