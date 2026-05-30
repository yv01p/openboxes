package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.Category;

// Flat FK-only DTO per FD#3.
public record CategoryDto(
    String id,
    String name,
    String description,
    String parentCategoryId,
    Integer sortOrder,
    Boolean isRoot,
    String glAccountId
) {
    public static CategoryDto from(Category c) {
        return new CategoryDto(
            c.getId(),
            c.getName(),
            c.getDescription(),
            c.getParentCategory() == null ? null : c.getParentCategory().getId(),
            c.getSortOrder(),
            c.getIsRoot(),
            c.getGlAccountId()
        );
    }
}
