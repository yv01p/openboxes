package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductSupplierPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProductSupplierPreferenceRepository extends JpaRepository<ProductSupplierPreference, String> {
    // Cutover read-GET — load all preferences for a given ProductSupplier.
    List<ProductSupplierPreference> findByProductSupplierId(String productSupplierId);

    // App-layer pair-uniqueness checks (A9: NO DB unique index). Two finders for the nullable
    // destinationPartyId: one for non-null values, one for the null-destinationParty case
    // (SQL `= NULL` never matches, so we need the IsNull finder).
    Optional<ProductSupplierPreference> findByProductSupplierIdAndDestinationPartyId(String productSupplierId, String destinationPartyId);
    Optional<ProductSupplierPreference> findByProductSupplierIdAndDestinationPartyIdIsNull(String productSupplierId);
}
