package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.ProductCatalogItemCache;
import org.openboxes.catalog.dto.ProductCatalogItemDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit (ProductCatalogItem has zero React callers).
@Service
@Transactional(readOnly = true)
public class ProductCatalogItemService {
    private final ProductCatalogItemCache cache;

    public ProductCatalogItemService(ProductCatalogItemCache cache) {
        this.cache = cache;
    }

    public Optional<ProductCatalogItemDto> get(String id) {
        return cache.get(id).map(ProductCatalogItemDto::from);
    }

    public List<ProductCatalogItemDto> list() {
        return cache.getAll().stream().map(ProductCatalogItemDto::from).toList();
    }
}
