package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductAttributeDto;
import org.openboxes.catalog.repository.ProductAttributeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per FD#1 (zero React callers). Repo-backed (mirrors ProductService) — NOT cache-backed:
// the Grails domain has no `cache true` and product_attribute has no audit/cache shape. Class-level
// read-only transaction keeps the LAZY FK proxies attachable during DTO mapping; the DTO reads only
// proxy ids (no init), so list() stays a single query.
@Service
@Transactional(readOnly = true)
public class ProductAttributeService {
    private final ProductAttributeRepository repo;

    public ProductAttributeService(ProductAttributeRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductAttributeDto> get(String id) {
        return repo.findById(id).map(ProductAttributeDto::from);
    }

    public List<ProductAttributeDto> list() {
        return repo.findAll().stream().map(ProductAttributeDto::from).toList();
    }
}
