package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductAttribute;

// Flat FK-only DTO per FD#2/FD#3. GET-only (T8): from(ProductAttribute) mapper only — no
// applyTo/toEntity. All 4 FKs exposed as raw String ids (productId, attributeId, unitOfMeasureId,
// productSupplierId), NOT nested entities. The scalar `value` (varchar 255) is the entity's payload
// and IS exposed. version is omitted (the established GET-only DTOs omit it).
//
// Each FK id is read via pa.getX()==null ? null : pa.getX().getId(). Reading the LAZY-proxy .getId()
// needs no DB hit (the proxy id is populated without initialization) — this is what keeps list() to a
// single query (no N+1 / no off-session LazyInitializationException). NEVER navigate a LAZY proxy to a
// non-id field (no .getName()/.getCode()) — that would initialize the proxy and fan out queries.
public record ProductAttributeDto(
    String id,
    String productId,
    String attributeId,
    String value,
    String unitOfMeasureId,
    String productSupplierId
) {
    public static ProductAttributeDto from(ProductAttribute pa) {
        return new ProductAttributeDto(
            pa.getId(),
            pa.getProduct() == null ? null : pa.getProduct().getId(),
            pa.getAttribute() == null ? null : pa.getAttribute().getId(),
            pa.getValue(),
            pa.getUnitOfMeasure() == null ? null : pa.getUnitOfMeasure().getId(),
            pa.getProductSupplier() == null ? null : pa.getProductSupplier().getId()
        );
    }
}
