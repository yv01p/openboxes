package org.pih.warehouse.auth

class PasswordTooWeakException extends RuntimeException {
    PasswordTooWeakException(String message) {
        super(message)
    }
}
