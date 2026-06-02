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

    // Optional productSupplier filter: the CUT form-load passes a supplier id to fetch ONLY that
    // supplier's attribute values (avoids shipping the whole product_attribute table to the client).
    // Null/absent → unfiltered list (the GET-only entity's default).
    public List<ProductAttributeDto> list(String productSupplierId) {
        List<org.openboxes.catalog.entity.ProductAttribute> rows = productSupplierId == null
            ? repo.findAll()
            : repo.findByProductSupplierId(productSupplierId);
        return rows.stream().map(ProductAttributeDto::from).toList();
    }
}
