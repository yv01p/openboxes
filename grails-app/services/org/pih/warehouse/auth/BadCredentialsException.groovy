package org.pih.warehouse.auth

class BadCredentialsException extends RuntimeException {
    BadCredentialsException(String message) {
        super(message)
    }
}
