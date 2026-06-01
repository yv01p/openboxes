package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductPackageDto;
import org.openboxes.catalog.entity.Product;
import org.openboxes.catalog.entity.ProductPackage;
import org.openboxes.catalog.entity.ProductPrice;
import org.openboxes.catalog.entity.ProductSupplier;
import org.openboxes.catalog.entity.UnitOfMeasure;
import org.openboxes.catalog.repository.ProductPackageRepository;
import org.openboxes.catalog.repository.ProductPriceRepository;
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
// T5 extends save() with embedded-price persistence (ports Grails setPackageData/setContractPriceData):
// the form posts price VALUES, and save() materializes the package's own ProductPrice (product_price_id)
// plus the supplier's contract ProductPrice (product_supplier.contract_price_id). Explicit saves, no
// cascade; prices persisted before the owner references them (FK direction owner → price).
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
    private final ProductPriceRepository priceRepo;

    public ProductPackageService(
        ProductPackageRepository repo,
        ProductRepository productRepo,
        UnitOfMeasureRepository uomRepo,
        ProductSupplierRepository productSupplierRepo,
        ProductPriceRepository priceRepo
    ) {
        this.repo = repo;
        this.productRepo = productRepo;
        this.uomRepo = uomRepo;
        this.productSupplierRepo = productSupplierRepo;
        this.priceRepo = priceRepo;
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

        // T5 embedded-price persistence — ports the Grails ProductPackageService.setPackageData /
        // setContractPriceData. The form posts price VALUES (not ids); we materialize ProductPrice rows.
        // FK direction is owner → price (product_package.product_price_id, product_supplier.contract_price_id),
        // so prices MUST be persisted BEFORE the owner references them. Explicit saves, NO JPA cascade
        // (matches the "no cascade from owner side" decision; the orphan-price case is not a concern).

        // Package price: if a productPackagePrice value is posted, create the package's own ProductPrice.
        // (Create-only path: this POST creates a fresh package, so there is no existing productPrice to
        // update — the Grails "update existing price" branch is not reachable here.)
        if (dto.productPackagePrice() != null) {
            ProductPrice packagePrice = new ProductPrice();
            packagePrice.setId(UUID.randomUUID().toString());
            packagePrice.setPrice(dto.productPackagePrice());
            // type defaults to "DEFAULT_PRICE" at the field; currency is left null (form posts no currency).
            priceRepo.save(packagePrice);
            pp.setProductPrice(packagePrice);
        }

        ProductPackage saved = repo.save(pp);

        // Contract price: price is NOT NULL, so a contractPricePrice value is REQUIRED to create/update a
        // contract price (toDate is optional). Load the package's supplier as a MANAGED entity (findById,
        // not the getReferenceById proxy) so we can read/update its contractPrice. Reuse the supplier's
        // existing contractPrice if present (update path) else create a new one.
        if (dto.contractPricePrice() != null && dto.productSupplierId() != null) {
            productSupplierRepo.findById(dto.productSupplierId()).ifPresent(supplier -> {
                ProductPrice contractPrice = supplier.getContractPrice();
                if (contractPrice == null) {
                    contractPrice = new ProductPrice();
                    contractPrice.setId(UUID.randomUUID().toString());
                }
                contractPrice.setPrice(dto.contractPricePrice());
                // contractPriceValidUntil ↔ toDate (Deviation #2). Optional — null clears the date.
                contractPrice.setToDate(dto.contractPriceValidUntil());
                priceRepo.save(contractPrice);
                supplier.setContractPrice(contractPrice);
                productSupplierRepo.save(supplier);
            });
        }
        // NOTE: the Grails "clear-on-empty deletes the contract price" case (setContractPriceData case 2)
        // is OUT OF T5 scope — deletion of a contract price on an empty submit is a CUT/follow-up concern.

        return ProductPackageDto.from(saved);
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
