package org.pih.warehouse.auth

class ValidationException extends RuntimeException {
    ValidationException(String message) {
        super(message)
    }
}
