package org.openboxes.catalog.service;

// App-layer tuple-uniqueness violation for ProductPackage (product, productSupplier, uom, quantity).
// Ports the Grails validator's findWhere pre-check. The DB UNIQUE KEY product_package_uniq_idx is the
// cross-instance-race backstop (→ DataIntegrityViolationException → 409 via C2 advice).
// Mapped to 409 via GlobalExceptionHandler (C2 advice).
public class DuplicatePackageException extends RuntimeException {
    public DuplicatePackageException(String message) {
        super(message);
    }
}
