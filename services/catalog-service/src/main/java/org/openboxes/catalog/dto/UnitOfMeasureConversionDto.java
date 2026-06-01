package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.UnitOfMeasureConversion;

// Flat FK-only DTO per FD#2/FD#3. GET-only (T11): from(UnitOfMeasureConversion) mapper only — no
// applyTo/toEntity. Both FKs exposed as raw String ids (fromUnitOfMeasureId, toUnitOfMeasureId), NOT
// nested entities. Reading the LAZY-proxy .getId() is cache-safe off-session (the id is populated on
// the proxy without initialization); do NOT navigate to any other field on the FK (e.g. .getCode())
// — that would initialize the proxy (LazyInitializationException risk off a cached/detached entity —
// the T7 lesson). version omitted per the GET-only DTO convention.
public record UnitOfMeasureConversionDto(
    String id,
    Boolean active,
    String fromUnitOfMeasureId,
    String toUnitOfMeasureId,
    java.math.BigDecimal conversionRate,
    java.time.Instant dateCreated,
    java.time.Instant lastUpdated
) {
    public static UnitOfMeasureConversionDto from(UnitOfMeasureConversion c) {
        return new UnitOfMeasureConversionDto(
            c.getId(),
            c.getActive(),
            c.getFromUnitOfMeasure() == null ? null : c.getFromUnitOfMeasure().getId(),
            c.getToUnitOfMeasure() == null ? null : c.getToUnitOfMeasure().getId(),
            c.getConversionRate(),
            c.getDateCreated(),
            c.getLastUpdated()
        );
    }
}
