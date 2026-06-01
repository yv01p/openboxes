package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductCatalog;

// Flat DTO per FD#3. GET-only (T6): from(ProductCatalog) mapper only — no applyTo/toEntity.
// Exposes the business fields only (mirrors ProductTypeDto); version/dates are internal and not
// surfaced.
public record ProductCatalogDto(
    String id,
    String code,
    String name,
    String description,
    Boolean active,
    String color
) {
    public static ProductCatalogDto from(ProductCatalog pc) {
        return new ProductCatalogDto(
            pc.getId(),
            pc.getCode(),
            pc.getName(),
            pc.getDescription(),
            pc.getActive(),
            pc.getColor()
        );
    }
}
