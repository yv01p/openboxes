package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductGroup;

import java.util.Set;
import java.util.stream.Collectors;

// Flat FK-only DTO per FD#3.
public record ProductGroupDto(
    String id,
    String name,
    String description,
    String categoryId,
    Set<String> productIds,
    Set<String> siblingIds
) {
    public static ProductGroupDto from(ProductGroup g) {
        return new ProductGroupDto(
            g.getId(),
            g.getName(),
            g.getDescription(),
            g.getCategory() == null ? null : g.getCategory().getId(),
            g.getProducts() == null ? Set.of() : g.getProducts().stream().map(p -> p.getId()).collect(Collectors.toSet()),
            g.getSiblings() == null ? Set.of() : g.getSiblings().stream().map(p -> p.getId()).collect(Collectors.toSet())
        );
    }
}
