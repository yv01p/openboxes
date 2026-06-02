package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductPackage;
import org.openboxes.catalog.entity.ProductPrice;
import org.openboxes.catalog.entity.ProductSupplier;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

// Task LQ2: the ENRICHED list-item DTO for the React "Product Sources" list page (the cutover from the
// Grails /api/productSuppliers list). Flat record (scalars + flat-id strings only, NO nested entities,
// per FD#2). It is DISTINCT from the write/read ProductSupplierDto (which is the GET-by-id / POST / PUT
// contract and must NOT change). This DTO carries ONLY what the list TABLE reads, PLUS the three derived
// pricing fields (packageSize/packagePrice/unitPrice) and a per-row flat preferences list — columns the
// flat ProductSupplierDto lacks but the live Grails list serves (via transient getters on
// ProductSupplier.groovy).
public record ProductSupplierListItemDto(
    // From the supplier — names/types match ProductSupplierDto.
    String id,
    String code,
    String name,
    String productId,
    String productCode,
    String productName,
    String supplierName,
    String supplierCode,
    String manufacturerName,
    Boolean active,
    Instant dateCreated,
    String createdById,
    String updatedById,
    // Derived (mirror the Grails transient getters on ProductSupplier.groovy:154/166/178).
    String packageSize,
    BigDecimal packagePrice,
    BigDecimal unitPrice,
    // Per-row flat preference refs (the list page's "Preference Type" column + its modal). Attached by
    // the service via a SECOND batch query (preferences are a separate entity, not a mapped collection
    // on ProductSupplier), grouped by supplier id.
    List<ProductSupplierPreferenceRef> preferences
) {
    // A price column floors to 0.00 (never null) — mirrors the Grails getters' `?: 0.0`.
    private static final BigDecimal ZERO_PRICE = new BigDecimal("0.00");

    public static ProductSupplierListItemDto from(ProductSupplier ps, List<ProductSupplierPreferenceRef> preferences) {
        return new ProductSupplierListItemDto(
            ps.getId(),
            ps.getCode(),
            ps.getName(),
            ps.getProduct() == null ? null : ps.getProduct().getId(),
            ps.getProductCode(),
            ps.getProduct() == null ? null : ps.getProduct().getName(),
            ps.getSupplierName(),
            ps.getSupplierCode(),
            ps.getManufacturerName(),
            ps.getActive(),
            ps.getDateCreated(),
            ps.getCreatedById(),
            ps.getUpdatedById(),
            packageSize(ps.getDefaultProductPackage()),
            packagePrice(ps.getDefaultProductPackage()),
            unitPrice(ps.getDefaultProductPackage()),
            preferences == null ? List.of() : preferences
        );
    }

    // getPackageSize() — Grails ProductSupplier.groovy:154. With a default package: "<uomCode>/<qty>"
    // (uom may be null → "null/<qty>", mirroring Groovy GString interpolation of a null). WITHOUT a
    // default package: null. (We do NOT mirror the Grails defaultProductPackageDerived "old way"
    // fallback — that derived getter is not modeled in catalog-service; the 1:1 default_product_package_id
    // association is the migrated source of truth.)
    private static String packageSize(ProductPackage pkg) {
        if (pkg == null) {
            return null;
        }
        String uomCode = pkg.getUom() == null ? null : pkg.getUom().getCode();
        return uomCode + "/" + pkg.getQuantity();
    }

    // getPackagePrice() — Grails ProductSupplier.groovy:166. With a default package:
    // productPrice?.price?.setScale(2, HALF_UP) ?: 0.0  (so a package with no price → 0.0). WITHOUT a
    // default package: 0.0. Matches the Grails getter exactly (never null).
    private static BigDecimal packagePrice(ProductPackage pkg) {
        if (pkg == null) {
            return ZERO_PRICE;
        }
        ProductPrice price = pkg.getProductPrice();
        if (price == null || price.getPrice() == null) {
            return ZERO_PRICE;
        }
        return price.getPrice().setScale(2, RoundingMode.HALF_UP);
    }

    // getUnitPrice() — Grails ProductSupplier.groovy:178. With a default package:
    // productPrice ? (price / quantity).setScale(2, HALF_UP) : 0.0. WITHOUT a default package: 0.0.
    // The division mirrors Groovy's BigDecimal `/` (which uses a default MathContext, never throwing on
    // non-terminating decimals like 9.99/12) by dividing under DECIMAL128 then setScale(2, HALF_UP).
    // Quantity is NOT NULL on product_package, but null-guard defensively to avoid a divide-by-zero/NPE.
    private static BigDecimal unitPrice(ProductPackage pkg) {
        if (pkg == null) {
            return ZERO_PRICE;
        }
        ProductPrice price = pkg.getProductPrice();
        if (price == null || price.getPrice() == null) {
            return ZERO_PRICE;
        }
        Integer qty = pkg.getQuantity();
        if (qty == null || qty == 0) {
            return ZERO_PRICE;
        }
        return price.getPrice()
            .divide(BigDecimal.valueOf(qty), MathContext.DECIMAL128)
            .setScale(2, RoundingMode.HALF_UP);
    }
}
