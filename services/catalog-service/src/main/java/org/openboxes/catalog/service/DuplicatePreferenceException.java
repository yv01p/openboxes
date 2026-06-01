package org.openboxes.catalog.service;

// App-layer pair-uniqueness violation for ProductSupplierPreference (productSupplier, destinationParty).
// A9: NO DB unique index exists; service enforces the check before save.
// Mapped to 409 via GlobalExceptionHandler (C2 advice).
public class DuplicatePreferenceException extends RuntimeException {
    public DuplicatePreferenceException(String message) {
        super(message);
    }
}
