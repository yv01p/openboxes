package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductSupplier;

import java.math.BigDecimal;
import java.time.Instant;

// Flat FK-only DTO per FD#2: ALL FKs exposed as raw String ids (no nested entities).
// contractPriceId + defaultProductPackageId are included for DTO stability across T4/T5 but always
// map to null in T2 (the entity doesn't hold those associations yet — T4/T5 wire them and populate here).
public record ProductSupplierDto(
    String id,
    String code,
    String name,
    String description,
    String productCode,
    String productId,
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
            // contractPriceId / defaultProductPackageId: always null in T2 (associations added by T5/T4).
            null,
            null,
            ps.getDateCreated(),
            ps.getLastUpdated(),
            ps.getCreatedById(),
            ps.getUpdatedById()
        );
    }

    // Maps flat scalar/FK-string fields onto an entity. The `product` association is resolved and set
    // by the service (from ProductRepository); audit fields (date/by) are populated by the auditing
    // listener; id and version are managed by the service/Hibernate. contractPriceId /
    // defaultProductPackageId are ignored in T2 (associations don't exist on the entity yet).
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
