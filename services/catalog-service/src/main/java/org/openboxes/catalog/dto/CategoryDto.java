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

    // Maps flat scalar/FK-string fields onto an entity. parentCategory is resolved+set by the service
    // from parentCategoryId; id/version/audit timestamps are managed by the service/Hibernate/auditing.
    // PUT is full-replace: a request that omits a field resets it (e.g. omitting isRoot demotes a root
    // to false), so callers must send the complete desired state. Mirrors ProductSupplierDto.applyTo.
    public void applyTo(Category c) {
        c.setName(name);
        c.setDescription(description);
        c.setSortOrder(sortOrder);
        c.setIsRoot(isRoot != null ? isRoot : false);   // is_root is nullable but default false in the domain
        c.setGlAccountId(glAccountId);
    }

    public Category toEntity() {
        Category c = new Category();
        applyTo(c);
        return c;
    }
}
