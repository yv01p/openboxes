package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.UnitOfMeasure;

// Flat FK-only DTO per FD#3.
public record UnitOfMeasureDto(
    String id,
    String name,
    String code,
    String description,
    String uomClassId
) {
    public static UnitOfMeasureDto from(UnitOfMeasure u) {
        return new UnitOfMeasureDto(
            u.getId(),
            u.getName(),
            u.getCode(),
            u.getDescription(),
            u.getUomClass() == null ? null : u.getUomClass().getId()
        );
    }
}
