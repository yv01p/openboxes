package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.ProductTypeCache;
import org.openboxes.catalog.dto.ProductTypeDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit.
@Service
@Transactional(readOnly = true)
public class ProductTypeService {
    private final ProductTypeCache cache;

    public ProductTypeService(ProductTypeCache cache) {
        this.cache = cache;
    }

    public Optional<ProductTypeDto> get(String id) {
        return cache.get(id).map(ProductTypeDto::from);
    }

    public List<ProductTypeDto> list() {
        return cache.getAll().stream().map(ProductTypeDto::from).toList();
    }
}
