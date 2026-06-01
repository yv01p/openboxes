package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductSupplierDto;
import org.openboxes.catalog.entity.Product;
import org.openboxes.catalog.entity.ProductPackage;
import org.openboxes.catalog.entity.ProductSupplier;
import org.openboxes.catalog.repository.ProductPackageRepository;
import org.openboxes.catalog.repository.ProductRepository;
import org.openboxes.catalog.repository.ProductSupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// First WRITE service in catalog-service (T2 canonical template). Verb scope = full CRUD.
// Write-race disposition = accept-silent-duplicates: T1 verified NO unique constraint exists at the
// DB or GORM layer for product_supplier, so there is NO app-layer uniqueness check.
// Class-level @Transactional(readOnly=true); write methods override with read-write @Transactional.
@Service
@Transactional(readOnly = true)
public class ProductSupplierService {
    private final ProductSupplierRepository repo;
    private final ProductRepository productRepo;
    private final ProductPackageRepository productPackageRepo;

    public ProductSupplierService(
        ProductSupplierRepository repo,
        ProductRepository productRepo,
        ProductPackageRepository productPackageRepo
    ) {
        this.repo = repo;
        this.productRepo = productRepo;
        this.productPackageRepo = productPackageRepo;
    }

    public List<ProductSupplierDto> list() {
        return repo.findAll().stream().map(ProductSupplierDto::from).toList();
    }

    public Optional<ProductSupplierDto> get(String id) {
        return repo.findById(id).map(ProductSupplierDto::from);
    }

    @Transactional
    public ProductSupplierDto save(ProductSupplierDto dto) {
        ProductSupplier ps = dto.toEntity();
        // id is app-assigned (Grails uuid-style; no DB auto-increment). char(38) holds a 36-char UUID.
        ps.setId(UUID.randomUUID().toString());
        ps.setProduct(resolveProduct(dto.productId()));
        ps.setDefaultProductPackage(resolveProductPackage(dto.defaultProductPackageId()));
        return ProductSupplierDto.from(repo.save(ps));
    }

    @Transactional
    public Optional<ProductSupplierDto> update(String id, ProductSupplierDto dto) {
        return repo.findById(id).map(ps -> {
            dto.applyTo(ps);
            ps.setProduct(resolveProduct(dto.productId()));
            ps.setDefaultProductPackage(resolveProductPackage(dto.defaultProductPackageId()));
            return ProductSupplierDto.from(repo.save(ps));
        });
    }

    @Transactional
    public boolean delete(String id) {
        if (!repo.existsById(id)) {
            return false;
        }
        repo.deleteById(id);
        return true;
    }

    // product is the only @ManyToOne FK (catalog-internal, NOT NULL). Resolve a reference so the
    // FK column is set without loading the full Product graph.
    private Product resolveProduct(String productId) {
        if (productId == null) {
            return null;  // product is NOT NULL — Hibernate will reject the insert/update.
        }
        return productRepo.getReferenceById(productId);
    }

    // T4 forward-decl split: defaultProductPackage is a nullable @ManyToOne. Resolved on every
    // save/update (a PUT omitting it clears it, consistent with resolveProduct's behavior).
    // getReferenceById(null) throws, so null-guard and return null.
    private ProductPackage resolveProductPackage(String defaultProductPackageId) {
        if (defaultProductPackageId == null) {
            return null;
        }
        return productPackageRepo.getReferenceById(defaultProductPackageId);
    }
}
