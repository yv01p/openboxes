package org.openboxes.identity.service;

public class LocationDisabledException extends RuntimeException {
    public LocationDisabledException(String message) {
        super(message);
    }
}
