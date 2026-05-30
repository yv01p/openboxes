package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.UnitOfMeasureClass;

// Flat FK-only DTO per FD#3.
public record UnitOfMeasureClassDto(
    String id,
    String name,
    String code,
    String description,
    Boolean active,
    String type,
    String baseUomId
) {
    public static UnitOfMeasureClassDto from(UnitOfMeasureClass c) {
        return new UnitOfMeasureClassDto(
            c.getId(),
            c.getName(),
            c.getCode(),
            c.getDescription(),
            c.getActive(),
            c.getType(),
            c.getBaseUom() == null ? null : c.getBaseUom().getId()
        );
    }
}
