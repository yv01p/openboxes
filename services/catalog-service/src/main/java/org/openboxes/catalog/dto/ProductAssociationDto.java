package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductAssociation;

// Flat FK-only DTO per FD#2/FD#3. GET-only (T9): from(ProductAssociation) mapper only — no
// applyTo/toEntity. The three FKs (product, associatedProduct, mutualAssociation — the self-FK) are
// exposed as raw String ids (productId, associatedProductId, mutualAssociationId), NOT nested entities.
// The scalars code, quantity, comments, dateCreated, lastUpdated are read directly. version is omitted
// (the established GET-only DTOs omit it).
//
// Each FK id is read via pa.getX()==null ? null : pa.getX().getId() — including the self-FK
// mutualAssociation. Reading the LAZY-proxy .getId() needs no DB hit (the proxy id is populated without
// initialization) → list() stays a single query (no N+1 / no off-session LazyInitializationException).
// NEVER navigate a LAZY proxy to a non-id field (no .getName()/.getCode()) — that would initialize the
// proxy and fan out queries.
public record ProductAssociationDto(
    String id,
    String code,
    String productId,
    String associatedProductId,
    java.math.BigDecimal quantity,
    String comments,
    String mutualAssociationId,
    java.time.Instant dateCreated,
    java.time.Instant lastUpdated
) {
    public static ProductAssociationDto from(ProductAssociation pa) {
        return new ProductAssociationDto(
            pa.getId(),
            pa.getCode(),
            pa.getProduct() == null ? null : pa.getProduct().getId(),
            pa.getAssociatedProduct() == null ? null : pa.getAssociatedProduct().getId(),
            pa.getQuantity(),
            pa.getComments(),
            pa.getMutualAssociation() == null ? null : pa.getMutualAssociation().getId(),
            pa.getDateCreated(),
            pa.getLastUpdated()
        );
    }
}
