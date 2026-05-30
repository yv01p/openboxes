package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.Attribute;

import java.util.List;

// Flat FK-only DTO per FD#3.
public record AttributeDto(
    String id,
    String code,
    String name,
    String description,
    Boolean active,
    Boolean exportable,
    String unitOfMeasureClassId,
    List<String> options,
    String defaultValue,
    Boolean required,
    Boolean allowOther,
    Boolean allowMultiple,
    List<String> entityTypeCodes
) {
    public static AttributeDto from(Attribute a) {
        return new AttributeDto(
            a.getId(),
            a.getCode(),
            a.getName(),
            a.getDescription(),
            a.getActive(),
            a.getExportable(),
            a.getUnitOfMeasureClass() == null ? null : a.getUnitOfMeasureClass().getId(),
            a.getOptions() == null ? List.of() : List.copyOf(a.getOptions()),
            a.getDefaultValue(),
            a.getRequired(),
            a.getAllowOther(),
            a.getAllowMultiple(),
            a.getEntityTypeCodes() == null ? List.of() : List.copyOf(a.getEntityTypeCodes())
        );
    }
}
