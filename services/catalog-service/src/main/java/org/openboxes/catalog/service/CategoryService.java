package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.CategoryCache;
import org.openboxes.catalog.dto.CategoryDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit (no React POST/PUT/DELETE callers for Category).
@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryCache cache;

    public CategoryService(CategoryCache cache) {
        this.cache = cache;
    }

    public Optional<CategoryDto> get(String id) {
        return cache.get(id).map(CategoryDto::from);
    }

    public List<CategoryDto> list() {
        return cache.getAll().stream().map(CategoryDto::from).toList();
    }
}
