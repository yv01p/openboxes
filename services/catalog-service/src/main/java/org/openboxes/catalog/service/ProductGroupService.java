package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductGroupDto;
import org.openboxes.catalog.repository.ProductGroupRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit.
@Service
@Transactional(readOnly = true)
public class ProductGroupService {
    private final ProductGroupRepository repo;

    public ProductGroupService(ProductGroupRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductGroupDto> get(String id) {
        return repo.findById(id).map(ProductGroupDto::from);
    }

    public List<ProductGroupDto> list() {
        return repo.findAll().stream().map(ProductGroupDto::from).toList();
    }
}
