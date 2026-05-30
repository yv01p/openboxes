package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductType;

import java.util.List;

// Flat FK-only DTO per FD#3.
public record ProductTypeDto(
    String id,
    String name,
    String code,
    String productTypeCode,
    String productIdentifierFormat,
    Integer sequenceNumber,
    List<String> supportedActivities,
    List<String> requiredFields,
    List<String> displayedFields
) {
    public static ProductTypeDto from(ProductType pt) {
        return new ProductTypeDto(
            pt.getId(),
            pt.getName(),
            pt.getCode(),
            pt.getProductTypeCode(),
            pt.getProductIdentifierFormat(),
            pt.getSequenceNumber(),
            pt.getSupportedActivities() == null ? List.of() : List.copyOf(pt.getSupportedActivities()),
            pt.getRequiredFields() == null ? List.of() : List.copyOf(pt.getRequiredFields()),
            pt.getDisplayedFields() == null ? List.of() : List.copyOf(pt.getDisplayedFields())
        );
    }
}
