package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductPrice;

import java.math.BigDecimal;
import java.time.Instant;

// Flat FK-only DTO per FD#2: the only FK (currency) is exposed as a raw String id (currencyId), no
// nested entity. `toDate` is what the React form calls `validUntil` (Deviation #2 — the live table has
// no valid_until column; contractPriceValidUntil maps to to_date).
public record ProductPriceDto(
    String id,
    BigDecimal price,
    String currencyId,
    String type,
    Instant fromDate,
    Instant toDate,
    Instant dateCreated,
    Instant lastUpdated,
    String createdById,
    String updatedById
) {
    public static ProductPriceDto from(ProductPrice pp) {
        return new ProductPriceDto(
            pp.getId(),
            pp.getPrice(),
            pp.getCurrency() == null ? null : pp.getCurrency().getId(),
            pp.getType(),
            pp.getFromDate(),
            pp.getToDate(),
            pp.getDateCreated(),
            pp.getLastUpdated(),
            pp.getCreatedById(),
            pp.getUpdatedById()
        );
    }

    // Maps flat scalar fields onto an entity. The `currency` @ManyToOne is resolved and set by the
    // service; audit fields are populated by the listener; id/version are managed by the
    // service/Hibernate. `type` defaults to "DEFAULT_PRICE" when the client omits it (NOT NULL column).
    public void applyTo(ProductPrice pp) {
        pp.setPrice(price);
        pp.setType(type != null ? type : "DEFAULT_PRICE");
        pp.setFromDate(fromDate);
        pp.setToDate(toDate);
    }

    public ProductPrice toEntity() {
        ProductPrice pp = new ProductPrice();
        applyTo(pp);
        return pp;
    }
}
