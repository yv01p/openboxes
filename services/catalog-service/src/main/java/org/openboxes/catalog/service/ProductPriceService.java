package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductPriceDto;
import org.openboxes.catalog.repository.ProductPriceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

// T5 ProductPrice read service. Verb scope = GET by id (cutover load read) ONLY — the React form does
// GET /api/productPrices/{id}. Prices are WRITTEN through the package endpoint (ProductPackageService),
// not directly, so per YAGNI there is NO list/save/update/delete here.
@Service
@Transactional(readOnly = true)
public class ProductPriceService {
    private final ProductPriceRepository repo;

    public ProductPriceService(ProductPriceRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductPriceDto> get(String id) {
        return repo.findById(id).map(ProductPriceDto::from);
    }
}
