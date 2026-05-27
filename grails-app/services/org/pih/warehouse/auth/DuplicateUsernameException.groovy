package org.pih.warehouse.auth

class DuplicateUsernameException extends RuntimeException {
    DuplicateUsernameException(String message) {
        super(message)
    }
}
