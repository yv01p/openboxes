package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductSupplierPreference;

// Task LQ2: a TINY flat ref carried per-row inside ProductSupplierListItemDto.preferences. Only the
// flat ids the list page's "Preference Type" column + its modal read (the frontend resolves the
// preferenceType / destinationParty NAMES client-side, so we embed ONLY the flat ids here — no names,
// no nested entities, per FD#2). Deliberately minimal: the heavy ProductSupplierPreferenceDto (comments,
// validity dates, full audit block) is NOT what the list table needs, so we use this dedicated record.
public record ProductSupplierPreferenceRef(
    String id,
    String preferenceTypeId,
    String destinationPartyId
) {
    public static ProductSupplierPreferenceRef from(ProductSupplierPreference psp) {
        return new ProductSupplierPreferenceRef(
            psp.getId(),
            psp.getPreferenceTypeId(),
            psp.getDestinationPartyId()
        );
    }
}
