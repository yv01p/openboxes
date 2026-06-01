package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductPackageDto;
import org.openboxes.catalog.entity.Product;
import org.openboxes.catalog.entity.ProductPackage;
import org.openboxes.catalog.entity.ProductSupplier;
import org.openboxes.catalog.entity.UnitOfMeasure;
import org.openboxes.catalog.repository.ProductPackageRepository;
import org.openboxes.catalog.repository.ProductRepository;
import org.openboxes.catalog.repository.ProductSupplierRepository;
import org.openboxes.catalog.repository.UnitOfMeasureRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// T4 ProductPackage write service. Mirrors the T2/T3 canonical template. Verb scope = POST (create)
// + GET (cutover load read) only — React ProductPackageApi.js only calls save (POST), so per YAGNI
// there is NO update/delete.
// Write-race disposition: the live DB has UNIQUE KEY product_package_uniq_idx
// (product_id, product_supplier_id, uom_id, quantity). save() ports the Grails validator's
// null-safe findWhere tuple pre-check for a friendly 409; the DB unique index is the cross-instance
// backstop (DataIntegrityViolationException → 409 via C2 advice).
// Class-level @Transactional(readOnly=true); write methods override with read-write @Transactional.
@Service
@Transactional(readOnly = true)
public class ProductPackageService {
    private final ProductPackageRepository repo;
    private final ProductRepository productRepo;
    private final UnitOfMeasureRepository uomRepo;
    private final ProductSupplierRepository productSupplierRepo;

    public ProductPackageService(
        ProductPackageRepository repo,
        ProductRepository productRepo,
        UnitOfMeasureRepository uomRepo,
        ProductSupplierRepository productSupplierRepo
    ) {
        this.repo = repo;
        this.productRepo = productRepo;
        this.uomRepo = uomRepo;
        this.productSupplierRepo = productSupplierRepo;
    }

    public List<ProductPackageDto> list(String productSupplierId) {
        if (productSupplierId == null) {
            return repo.findAll().stream().map(ProductPackageDto::from).toList();
        }
        return repo.findByProductSupplierId(productSupplierId).stream()
            .map(ProductPackageDto::from).toList();
    }

    public Optional<ProductPackageDto> get(String id) {
        return repo.findById(id).map(ProductPackageDto::from);
    }

    @Transactional
    public ProductPackageDto save(ProductPackageDto dto) {
        ProductPackage pp = dto.toEntity();
        // id is app-assigned (Grails uuid-style; no DB auto-increment). char(38) holds a 36-char UUID.
        pp.setId(UUID.randomUUID().toString());
        pp.setProduct(resolveProduct(dto.productId()));
        pp.setUom(resolveUom(dto.uomId()));
        pp.setProductSupplier(resolveProductSupplier(dto.productSupplierId()));

        // Friendly pre-check porting the Grails validator's null-safe findWhere tuple lookup.
        // On a hit with a DIFFERENT id (a genuine pre-existing duplicate), throw → 409 via advice.
        // The DB UNIQUE index is the cross-instance-race backstop (→ DataIntegrityViolationException).
        List<ProductPackage> matches = repo.findMatchingTuple(
            dto.productId(), dto.productSupplierId(), dto.uomId(), dto.quantity());
        for (ProductPackage match : matches) {
            // Self-exclusion mirrors the T3 template: for this create-only path pp's id is a fresh
            // UUID never yet persisted, so the guard is always true (a match is always a real
            // pre-existing duplicate). Kept for template symmetry / safety if a PUT path is added later.
            if (!match.getId().equals(pp.getId())) {
                throw new DuplicatePackageException(
                    "duplicate (product, productSupplier, uom, quantity) tuple: " +
                    "productId=" + dto.productId() +
                    ", productSupplierId=" + dto.productSupplierId() +
                    ", uomId=" + dto.uomId() +
                    ", quantity=" + dto.quantity());
            }
        }

        return ProductPackageDto.from(repo.save(pp));
    }

    // product is a nullable @ManyToOne (live column DEFAULT NULL + GORM product(nullable:true)).
    // getReferenceById(null) throws, so null-guard and return null.
    private Product resolveProduct(String productId) {
        if (productId == null) {
            return null;
        }
        return productRepo.getReferenceById(productId);
    }

    // uom is a nullable @ManyToOne — null-guard the reference lookup.
    private UnitOfMeasure resolveUom(String uomId) {
        if (uomId == null) {
            return null;
        }
        return uomRepo.getReferenceById(uomId);
    }

    // productSupplier is a nullable @ManyToOne — null-guard the reference lookup.
    private ProductSupplier resolveProductSupplier(String productSupplierId) {
        if (productSupplierId == null) {
            return null;
        }
        return productSupplierRepo.getReferenceById(productSupplierId);
    }
}
