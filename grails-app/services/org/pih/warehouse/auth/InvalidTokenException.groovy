package org.pih.warehouse.auth

class InvalidTokenException extends RuntimeException {
    InvalidTokenException(String message) {
        super(message)
    }
}
