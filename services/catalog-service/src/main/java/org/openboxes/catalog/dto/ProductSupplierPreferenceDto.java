package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductSupplierPreference;

import java.time.Instant;

// Flat FK-only DTO per FD#2: ALL FKs exposed as raw String ids (no nested entities).
// productSupplierId, destinationPartyId, preferenceTypeId are all String ids matching the
// entity's flat FK pattern (only productSupplier is @ManyToOne; the others are raw columns).
public record ProductSupplierPreferenceDto(
    String id,
    String productSupplierId,
    String destinationPartyId,
    String preferenceTypeId,
    String comments,
    Instant validityStartDate,
    Instant validityEndDate,
    Instant dateCreated,
    Instant lastUpdated,
    String createdById,
    String updatedById
) {
    public static ProductSupplierPreferenceDto from(ProductSupplierPreference psp) {
        return new ProductSupplierPreferenceDto(
            psp.getId(),
            psp.getProductSupplier() == null ? null : psp.getProductSupplier().getId(),
            psp.getDestinationPartyId(),
            psp.getPreferenceTypeId(),
            psp.getComments(),
            psp.getValidityStartDate(),
            psp.getValidityEndDate(),
            psp.getDateCreated(),
            psp.getLastUpdated(),
            psp.getCreatedById(),
            psp.getUpdatedById()
        );
    }

    // Maps flat scalar fields onto an entity. The `productSupplier` @ManyToOne association
    // is resolved and set by the service (ProductSupplierRepository.getReferenceById);
    // audit fields are populated by the listener; id/version are managed by the service/Hibernate.
    public void applyTo(ProductSupplierPreference psp) {
        psp.setDestinationPartyId(destinationPartyId);
        psp.setPreferenceTypeId(preferenceTypeId);
        psp.setComments(comments);
        psp.setValidityStartDate(validityStartDate);
        psp.setValidityEndDate(validityEndDate);
    }

    public ProductSupplierPreference toEntity() {
        ProductSupplierPreference psp = new ProductSupplierPreference();
        applyTo(psp);
        return psp;
    }
}
