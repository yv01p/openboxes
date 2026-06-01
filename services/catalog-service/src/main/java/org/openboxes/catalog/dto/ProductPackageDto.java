package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductPackage;

import java.time.Instant;

// Flat FK-only DTO per FD#2: ALL FKs exposed as raw String ids (no nested entities).
// productId, uomId, productSupplierId are all String ids matching the entity's flat-FK convention.
// productPriceId is OMITTED at T4 (the productPrice association doesn't exist on the entity yet —
// T5 wires it and adds the field here).
public record ProductPackageDto(
    String id,
    String productId,
    String uomId,
    String productSupplierId,
    String name,
    String description,
    String gtin,
    Integer quantity,
    Instant dateCreated,
    Instant lastUpdated,
    String createdById,
    String updatedById
) {
    public static ProductPackageDto from(ProductPackage pp) {
        return new ProductPackageDto(
            pp.getId(),
            pp.getProduct() == null ? null : pp.getProduct().getId(),
            pp.getUom() == null ? null : pp.getUom().getId(),
            pp.getProductSupplier() == null ? null : pp.getProductSupplier().getId(),
            pp.getName(),
            pp.getDescription(),
            pp.getGtin(),
            pp.getQuantity(),
            pp.getDateCreated(),
            pp.getLastUpdated(),
            pp.getCreatedById(),
            pp.getUpdatedById()
        );
    }

    // Maps flat scalar fields onto an entity. The product/uom/productSupplier @ManyToOne associations
    // are resolved and set by the service (via getReferenceById); audit fields are populated by the
    // listener; id/version are managed by the service/Hibernate.
    public void applyTo(ProductPackage pp) {
        pp.setName(name);
        pp.setDescription(description);
        pp.setGtin(gtin);
        pp.setQuantity(quantity);
    }

    public ProductPackage toEntity() {
        ProductPackage pp = new ProductPackage();
        applyTo(pp);
        return pp;
    }
}
