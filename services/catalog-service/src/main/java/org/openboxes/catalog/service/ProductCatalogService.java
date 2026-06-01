package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.ProductCatalogCache;
import org.openboxes.catalog.dto.ProductCatalogDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit.
@Service
@Transactional(readOnly = true)
public class ProductCatalogService {
    private final ProductCatalogCache cache;

    public ProductCatalogService(ProductCatalogCache cache) {
        this.cache = cache;
    }

    public Optional<ProductCatalogDto> get(String id) {
        return cache.get(id).map(ProductCatalogDto::from);
    }

    public List<ProductCatalogDto> list() {
        return cache.getAll().stream().map(ProductCatalogDto::from).toList();
    }
}
