package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.Product;

import java.math.BigDecimal;
import java.util.Set;
import java.util.stream.Collectors;

// Flat FK-only DTO per FD#3 (catalog-service spec).
public record ProductDto(
    String id,
    String name,
    String description,
    String productCode,
    String productTypeId,
    String categoryId,
    String unitOfMeasureId,
    BigDecimal pricePerUnit,
    BigDecimal costPerUnit,
    Boolean active,
    Set<String> tagIds,
    Set<String> synonymIds,
    Set<String> productGroupIds,
    String productFamilyId
) {
    public static ProductDto from(Product p) {
        return new ProductDto(
            p.getId(),
            p.getName(),
            p.getDescription(),
            p.getProductCode(),
            p.getProductType() == null ? null : p.getProductType().getId(),
            p.getCategory() == null ? null : p.getCategory().getId(),
            p.getUnitOfMeasure() == null ? null : p.getUnitOfMeasure().getId(),
            p.getPricePerUnit(),
            p.getCostPerUnit(),
            p.getActive(),
            p.getTags() == null ? Set.of() : p.getTags().stream().map(t -> t.getId()).collect(Collectors.toSet()),
            p.getSynonyms() == null ? Set.of() : p.getSynonyms().stream().map(s -> s.getId()).collect(Collectors.toSet()),
            p.getProductGroups() == null ? Set.of() : p.getProductGroups().stream().map(g -> g.getId()).collect(Collectors.toSet()),
            p.getProductFamily() == null ? null : p.getProductFamily().getId()
        );
    }
}
