package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductSupplierPreference;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface ProductSupplierPreferenceRepository extends JpaRepository<ProductSupplierPreference, String> {
    // Cutover read-GET — load all preferences for a given ProductSupplier.
    List<ProductSupplierPreference> findByProductSupplierId(String productSupplierId);

    // Task LQ2: batch-load preferences for a PAGE of suppliers in ONE query (the list-query second
    // query). Preferences are a separate entity (not a mapped collection on ProductSupplier), so the
    // list path loads them here in a single batch keyed by the page's supplier ids and groups the result
    // into a Map<supplierId, refs> — avoiding the per-row N+1 a naive load would cause.
    // An empty/absent id collection is guarded by the service (it skips this query for an empty page).
    List<ProductSupplierPreference> findByProductSupplierIdIn(Collection<String> productSupplierIds);

    // App-layer pair-uniqueness checks (A9: NO DB unique index). Two finders for the nullable
    // destinationPartyId: one for non-null values, one for the null-destinationParty case
    // (SQL `= NULL` never matches, so we need the IsNull finder).
    Optional<ProductSupplierPreference> findByProductSupplierIdAndDestinationPartyId(String productSupplierId, String destinationPartyId);
    Optional<ProductSupplierPreference> findByProductSupplierIdAndDestinationPartyIdIsNull(String productSupplierId);
}
