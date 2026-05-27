package org.pih.warehouse.auth

class AccountDisabledException extends RuntimeException {
    AccountDisabledException(String message) {
        super(message)
    }
}
