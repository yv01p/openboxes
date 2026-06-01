package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductComponentDto;
import org.openboxes.catalog.repository.ProductComponentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per FD#1 (zero React callers). Repo-backed (mirrors ProductAttributeService) — NOT
// cache-backed: the Grails domain has no `cache true`. Class-level read-only transaction keeps the LAZY
// FK proxies attachable during DTO mapping; the DTO reads only proxy ids (no init), so list() stays a
// single query.
@Service
@Transactional(readOnly = true)
public class ProductComponentService {
    private final ProductComponentRepository repo;

    public ProductComponentService(ProductComponentRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductComponentDto> get(String id) {
        return repo.findById(id).map(ProductComponentDto::from);
    }

    public List<ProductComponentDto> list() {
        return repo.findAll().stream().map(ProductComponentDto::from).toList();
    }
}
