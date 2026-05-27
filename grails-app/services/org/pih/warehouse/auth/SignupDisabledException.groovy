package org.pih.warehouse.auth

class SignupDisabledException extends RuntimeException {
    SignupDisabledException(String message) {
        super(message)
    }
}
