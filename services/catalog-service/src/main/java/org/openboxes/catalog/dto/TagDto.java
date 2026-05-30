package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.Tag;

import java.util.Set;
import java.util.stream.Collectors;

// Flat FK-only DTO per FD#3.
public record TagDto(
    String id,
    String tag,
    Boolean isActive,
    Set<String> productIds
) {
    public static TagDto from(Tag t) {
        return new TagDto(
            t.getId(),
            t.getTag(),
            t.getIsActive(),
            t.getProducts() == null ? Set.of() : t.getProducts().stream().map(p -> p.getId()).collect(Collectors.toSet())
        );
    }
}
