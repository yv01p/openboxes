package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.Synonym;

import java.util.Locale;

// Flat FK-only DTO per FD#3. Locale type matches Synonym entity (Hibernate 6 LocaleJavaType handles persistence).
public record SynonymDto(
    String id,
    String productId,
    String name,
    Locale locale,
    String synonymTypeCode
) {
    public static SynonymDto from(Synonym s) {
        return new SynonymDto(
            s.getId(),
            s.getProduct() == null ? null : s.getProduct().getId(),
            s.getName(),
            s.getLocale(),
            s.getSynonymTypeCode()
        );
    }
}
