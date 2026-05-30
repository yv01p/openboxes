package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.AttributeCache;
import org.openboxes.catalog.dto.AttributeDto;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit.
@Service
@Transactional(readOnly = true)
public class AttributeService {
    private final AttributeCache cache;

    public AttributeService(AttributeCache cache) {
        this.cache = cache;
    }

    public Optional<AttributeDto> get(String id) {
        return cache.get(id).map(AttributeDto::from);
    }

    public List<AttributeDto> list() {
        return cache.getAll().stream().map(AttributeDto::from).toList();
    }
}
