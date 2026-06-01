package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductAssociationDto;
import org.openboxes.catalog.repository.ProductAssociationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per FD#1 (zero React callers). Repo-backed (mirrors ProductService/ProductAttributeService) —
// NOT cache-backed: the Grails domain has no `cache true`. Class-level read-only transaction keeps the
// LAZY FK proxies attachable during DTO mapping; the DTO reads only proxy ids (no init, incl the self-FK),
// so list() stays a single query.
@Service
@Transactional(readOnly = true)
public class ProductAssociationService {
    private final ProductAssociationRepository repo;

    public ProductAssociationService(ProductAssociationRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductAssociationDto> get(String id) {
        return repo.findById(id).map(ProductAssociationDto::from);
    }

    public List<ProductAssociationDto> list() {
        return repo.findAll().stream().map(ProductAssociationDto::from).toList();
    }
}
