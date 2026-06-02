package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductPackage;

import java.math.BigDecimal;
import java.time.Instant;

// Flat FK-only DTO per FD#2: ALL FKs exposed as raw String ids (no nested entities).
// productId, uomId, productSupplierId are all String ids matching the entity's flat-FK convention.
// productPriceId (T5) is the READ-side id of the package's own ProductPrice (the productPrice @ManyToOne).
//
// WRITE-ONLY price-VALUE fields (T5): productPackagePrice, contractPricePrice, contractPriceValidUntil.
// At cutover the React form posts price VALUES (not ids) via buildPackagePayload in
// useProductSupplierForm.js: productPackagePrice, contractPricePrice, contractPriceValidUntil.
// These are NOT entity columns — the service (ProductPackageService.save) materializes ProductPrice rows
// from them. They are write-only: from() always emits null for them (there is nothing to read back from
// the package entity), so a round-tripped DTO never re-exposes the raw input values.
// contractPriceValidUntil maps to ProductPrice.toDate (Deviation #2 — there is no valid_until column).
public record ProductPackageDto(
    String id,
    String productId,
    String uomId,
    String productSupplierId,
    String name,
    String description,
    String gtin,
    Integer quantity,
    String productPriceId,
    // Write-only embedded-price inputs (see header). Always null in from().
    BigDecimal productPackagePrice,
    BigDecimal contractPricePrice,
    Instant contractPriceValidUntil,
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
            // productPriceId: read the package's own price id (T5 association).
            pp.getProductPrice() == null ? null : pp.getProductPrice().getId(),
            // The three price-VALUE fields are write-only inputs, not entity columns — always null here.
            null,
            null,
            null,
            pp.getDateCreated(),
            pp.getLastUpdated(),
            pp.getCreatedById(),
            pp.getUpdatedById()
        );
    }

    // Maps flat scalar fields onto an entity. The product/uom/productSupplier @ManyToOne associations
    // are resolved and set by the service (via getReferenceById); audit fields are populated by the
    // listener; id/version are managed by the service/Hibernate. The embedded-price fields
    // (productPackagePrice/contractPricePrice/contractPriceValidUntil) are NOT applied here — the
    // service materializes ProductPrice rows from them (price persistence is a service concern).
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
