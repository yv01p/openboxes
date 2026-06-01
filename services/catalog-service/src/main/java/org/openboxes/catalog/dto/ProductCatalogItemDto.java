package org.openboxes.catalog.dto;

import org.openboxes.catalog.entity.ProductCatalogItem;

// Flat FK-only DTO per FD#2/FD#3. GET-only (T7): from(ProductCatalogItem) mapper only — no
// applyTo/toEntity. Both FKs exposed as raw String ids (productId, productCatalogId), NOT nested
// entities. Reading the LAZY-proxy .getId() is cache-safe off-session (the id is populated on the
// proxy without initialization); do NOT navigate to any other field on the FK — that would
// initialize the proxy (LazyInitializationException risk off a cached/detached entity + N+1). No
// denormalized product name/code is exposed (would require proxy initialization).
public record ProductCatalogItemDto(
    String id,
    String productId,
    String productCatalogId,
    Boolean active
) {
    public static ProductCatalogItemDto from(ProductCatalogItem pci) {
        return new ProductCatalogItemDto(
            pci.getId(),
            pci.getProduct() == null ? null : pci.getProduct().getId(),
            pci.getProductCatalog() == null ? null : pci.getProductCatalog().getId(),
            pci.getActive()
        );
    }
}
