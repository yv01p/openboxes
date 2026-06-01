package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductSupplier;

import java.math.BigDecimal;
import java.time.Instant;

// Flat FK-only DTO per FD#2: ALL FKs exposed as raw String ids (no nested entities).
// defaultProductPackageId is wired to the T4 @ManyToOne; contractPriceId is wired to the T5 @ManyToOne
// (both read in from(), resolved by the service). No hard-null FK fields remain.
public record ProductSupplierDto(
    String id,
    String code,
    String name,
    String description,
    String productCode,
    String productId,
    // Read-only/derived: the list page (Task LQ) shows a Product-Name column. Populated in from()
    // from ps.product.name (loaded via the @EntityGraph on the list query — no N+1; the single-row
    // get/post/put paths trigger one cheap lazy load). applyTo() IGNORES this (never written). It is a
    // flat String, NOT a nested `product` object, so the flatness test (productSupplierGet_dtoIsFlat_*)
    // still passes.
    String productName,
    String ndc,
    String upc,
    String manufacturerId,
    String manufacturerCode,
    String manufacturerName,
    String brandName,
    String modelNumber,
    String supplierId,
    String supplierCode,
    String supplierName,
    String ratingTypeCode,
    BigDecimal standardLeadTimeDays,
    BigDecimal minOrderQuantity,
    String comments,
    Boolean tieredPricing,
    Boolean active,
    String contractPriceId,
    String defaultProductPackageId,
    Instant dateCreated,
    Instant lastUpdated,
    String createdById,
    String updatedById
) {
    public static ProductSupplierDto from(ProductSupplier ps) {
        return new ProductSupplierDto(
            ps.getId(),
            ps.getCode(),
            ps.getName(),
            ps.getDescription(),
            ps.getProductCode(),
            ps.getProduct() == null ? null : ps.getProduct().getId(),
            // productName (read-only): from the product association (fetched by the list @EntityGraph).
            ps.getProduct() == null ? null : ps.getProduct().getName(),
            ps.getNdc(),
            ps.getUpc(),
            ps.getManufacturerId(),
            ps.getManufacturerCode(),
            ps.getManufacturerName(),
            ps.getBrandName(),
            ps.getModelNumber(),
            ps.getSupplierId(),
            ps.getSupplierCode(),
            ps.getSupplierName(),
            ps.getRatingTypeCode(),
            ps.getStandardLeadTimeDays(),
            ps.getMinOrderQuantity(),
            ps.getComments(),
            ps.getTieredPricing(),
            ps.getActive(),
            // contractPriceId: wired to the T5 @ManyToOne.
            ps.getContractPrice() == null ? null : ps.getContractPrice().getId(),
            // defaultProductPackageId: wired to the T4 @ManyToOne.
            ps.getDefaultProductPackage() == null ? null : ps.getDefaultProductPackage().getId(),
            ps.getDateCreated(),
            ps.getLastUpdated(),
            ps.getCreatedById(),
            ps.getUpdatedById()
        );
    }

    // Maps flat scalar/FK-string fields onto an entity. The `product`, `defaultProductPackage` and
    // `contractPrice` associations are resolved and set by the service; audit fields (date/by) are
    // populated by the auditing listener; id and version are managed by the service/Hibernate. The FK
    // id fields (contractPriceId, defaultProductPackageId) are resolved by the service, not applyTo.
    public void applyTo(ProductSupplier ps) {
        ps.setCode(code);
        ps.setName(name);
        ps.setDescription(description);
        ps.setProductCode(productCode);
        ps.setNdc(ndc);
        ps.setUpc(upc);
        ps.setManufacturerId(manufacturerId);
        ps.setManufacturerCode(manufacturerCode);
        ps.setManufacturerName(manufacturerName);
        ps.setBrandName(brandName);
        ps.setModelNumber(modelNumber);
        ps.setSupplierId(supplierId);
        ps.setSupplierCode(supplierCode);
        ps.setSupplierName(supplierName);
        ps.setRatingTypeCode(ratingTypeCode);
        ps.setStandardLeadTimeDays(standardLeadTimeDays);
        ps.setMinOrderQuantity(minOrderQuantity);
        ps.setComments(comments);
        // tiered_pricing is NOT NULL: default false when the client omits it.
        ps.setTieredPricing(tieredPricing != null ? tieredPricing : false);
        ps.setActive(active != null ? active : true);
    }

    public ProductSupplier toEntity() {
        ProductSupplier ps = new ProductSupplier();
        applyTo(ps);
        return ps;
    }
}
