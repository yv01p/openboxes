package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductComponent;

// Flat FK-only DTO per FD#2/FD#3. GET-only (T10): from(ProductComponent) mapper only — no
// applyTo/toEntity. The three FKs (assemblyProduct, componentProduct, unitOfMeasure) are exposed as raw
// String ids (assemblyProductId, componentProductId, unitOfMeasureId), NOT nested entities. The scalars
// quantity, dateCreated, lastUpdated are read directly. version is omitted (the established GET-only DTOs
// omit it).
//
// Each FK id is read via pc.getX()==null ? null : pc.getX().getId(). Reading the LAZY-proxy .getId()
// needs no DB hit (the proxy id is populated without initialization) → list() stays a single query (no
// N+1 / no off-session LazyInitializationException). NEVER navigate a LAZY proxy to a non-id field (no
// .getName()/.getCode()) — that would initialize the proxy and fan out queries.
public record ProductComponentDto(
    String id,
    String assemblyProductId,
    String componentProductId,
    java.math.BigDecimal quantity,
    String unitOfMeasureId,
    java.time.Instant dateCreated,
    java.time.Instant lastUpdated
) {
    public static ProductComponentDto from(ProductComponent pc) {
        return new ProductComponentDto(
            pc.getId(),
            pc.getAssemblyProduct() == null ? null : pc.getAssemblyProduct().getId(),
            pc.getComponentProduct() == null ? null : pc.getComponentProduct().getId(),
            pc.getQuantity(),
            pc.getUnitOfMeasure() == null ? null : pc.getUnitOfMeasure().getId(),
            pc.getDateCreated(),
            pc.getLastUpdated()
        );
    }
}
