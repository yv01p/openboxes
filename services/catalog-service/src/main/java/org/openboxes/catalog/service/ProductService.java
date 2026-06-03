package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductDto;
import org.openboxes.catalog.repository.ProductRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// R/O per FD#1; class-level read-only transaction keeps lazy collections accessible
// during DTO mapping (Tag M:N, Synonym OneToMany, ProductGroup M:N, productFamily).
@Service
@Transactional(readOnly = true)
public class ProductService {
    private final ProductRepository repo;

    public ProductService(ProductRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductDto> get(String id) {
        return repo.findById(id).map(ProductDto::from);
    }

    public List<ProductDto> list() {
        return repo.findAll().stream().map(ProductDto::from).toList();
    }

    // RC-16 (T4): global distinct non-empty Product.abcClass. Consumed by inventory-service over HTTP.
    public java.util.List<String> distinctAbcClasses() {
        return repo.findDistinctAbcClasses();
    }
}
