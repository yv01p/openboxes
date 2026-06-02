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

// T4 ProductPackage write service. Mirrors the T2/T3 canonical template. Verb scope = POST (upsert)
// + GET (cutover load read) only — React ProductPackageApi.js only calls save (POST), so per YAGNI
// there is NO update/delete.
// T5 extends save() with embedded-price persistence (ports Grails setPackageData/setContractPriceData):
// the form posts price VALUES, and save() materializes the package's own ProductPrice (product_price_id)
// plus the supplier's contract ProductPrice (product_supplier.contract_price_id). Explicit saves, no
// cascade; prices persisted before the owner references them (FK direction owner → price).
//
// CUT — save() is an UPSERT, not create-only. The React ProductSupplier form POSTs /api/productPackages
// on BOTH create AND edit of a source, expecting upsert semantics (ports Grails setPackageData):
//   1. Find-or-create the supplier's package by (productSupplier, uom, quantity). FOUND → update in
//      place (no new row, no new id); NOT found → create a fresh package.
//   2. ALWAYS re-link supplier.defaultProductPackage = the upserted package, on BOTH paths. This is
//      REQUIRED because the form's details PUT (which runs just before this POST in the fan-out) sends
//      no defaultProductPackageId and therefore CLEARS supplier.defaultProductPackage; this POST must
//      re-establish it or the package is invisible on reload/list (both read defaultProductPackageId).
//   3. Package price upsert: update the package's ProductPrice in place if present, else create one.
//   4. Contract price upsert + clear-on-empty (ports setContractPriceData cases 2-4).
// Null guard: if uomId OR quantity is null we do NOT create/link a junk package (the form may submit
// with no package); the contract-price block still runs so a pure contract-price edit is honored.
// Write-race disposition: the live DB has UNIQUE KEY product_package_uniq_idx
// (product_id, product_supplier_id, uom_id, quantity). The upsert never creates a duplicate, so the
// app-layer pre-check is gone; the DB unique index is the cross-instance-race backstop only
// (DataIntegrityViolationException → 409 via C2 advice).
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
        // Load the supplier as a MANAGED entity (NOT the getReferenceById proxy) so we can set its
        // defaultProductPackage / contractPrice associations and save it. Mirrors the contract-price
        // block's findById-and-save pattern. May be null if no productSupplierId was posted.
        ProductSupplier supplier = dto.productSupplierId() == null
            ? null
            : productSupplierRepo.findById(dto.productSupplierId()).orElse(null);

        // ----- Package find-or-create (ports Grails setPackageData) -----
        // Null guard (step 6): a package needs a uom AND a quantity to be a real package. If either is
        // null, do NOT create/link a junk package — skip the package block entirely (the form may submit
        // with no package, e.g. a pure contract-price edit). The contract-price block below still runs.
        ProductPackage saved = null;
        if (dto.uomId() != null && dto.quantity() != null && supplier != null) {
            // Find the supplier's existing package by (productSupplier, uom, quantity). The finder is
            // intentionally product-agnostic (the DB unique key is the 4-tuple incl. product_id); the
            // form always posts one product per supplier, so findFirst() resolves to that single row.
            ProductPackage pp = repo
                .findByProductSupplier_IdAndUom_IdAndQuantity(
                    dto.productSupplierId(), dto.uomId(), dto.quantity())
                .stream()
                .findFirst()
                .orElse(null);

            if (pp == null) {
                // NOT found → create a fresh package (new UUID). Name/description mirror Grails
                // setPackageData: name "${uom.code}/${qty}", description "${uom.name} of ${qty}".
                pp = new ProductPackage();
                pp.setId(UUID.randomUUID().toString());
                pp.setProduct(resolveProduct(dto.productId()));
                pp.setUom(resolveUom(dto.uomId()));
                pp.setProductSupplier(supplier);
                pp.setQuantity(dto.quantity());
                UnitOfMeasure uom = pp.getUom();
                pp.setName((uom == null ? null : uom.getCode()) + "/" + dto.quantity());
                pp.setDescription((uom == null ? null : uom.getName()) + " of " + dto.quantity());
            }
            // FOUND → update that package in place (no new row, no new id). Grails only updates pricing
            // on the existing package, so we leave name/description/uom/quantity as-is on the found row.

            // Package price upsert (ports Grails setPackageData pricing branches): if a productPackagePrice
            // value is posted, update the package's own ProductPrice in place if it already has one, else
            // create a fresh one. If null, leave pricing untouched. FK direction is owner → price
            // (product_package.product_price_id), so the price MUST be persisted BEFORE the package
            // references it. Explicit save, NO JPA cascade.
            if (dto.productPackagePrice() != null) {
                ProductPrice packagePrice = pp.getProductPrice();
                if (packagePrice == null) {
                    packagePrice = new ProductPrice();
                    packagePrice.setId(UUID.randomUUID().toString());
                    // type defaults to "DEFAULT_PRICE" at the field; currency left null (no currency posted).
                }
                packagePrice.setPrice(dto.productPackagePrice());
                priceRepo.save(packagePrice);
                pp.setProductPrice(packagePrice);
            }

            saved = repo.save(pp);

            // ALWAYS re-link the default (step 2) — on BOTH the create and update paths. The form's
            // details PUT clears supplier.defaultProductPackage just before this POST, so we must
            // re-establish it here or the package is invisible on reload/list.
            supplier.setDefaultProductPackage(saved);
            productSupplierRepo.save(supplier);
        }

        // ----- Contract price upsert + clear-on-empty (ports Grails setContractPriceData) -----
        // The supplier is a MANAGED entity; reuse its existing contractPrice if present (update path),
        // else create a new one. FK direction is owner → price (product_supplier.contract_price_id), so
        // the price is persisted BEFORE the supplier references it.
        if (supplier != null) {
            ProductPrice contractPrice = supplier.getContractPrice();
            boolean hasPrice = dto.contractPricePrice() != null;
            boolean hasDate = dto.contractPriceValidUntil() != null;

            if (hasPrice || hasDate) {
                // setContractPriceData cases 3/4: create-if-absent, then set the passed data.
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
            } else if (contractPrice != null) {
                // setContractPriceData case 2: contract price EXISTS but both inputs are null → clear it
                // (unlink the supplier, then delete the now-orphaned ProductPrice). Edit-parity addition.
                supplier.setContractPrice(null);
                productSupplierRepo.save(supplier);
                priceRepo.delete(contractPrice);
            }
            // setContractPriceData case 1 (no contract price, no inputs) → nothing to do.
        }

        // If no package was created/updated (null-uom/quantity guard, or no supplier), there is no
        // package row to echo back. Return a flat DTO carrying just the posted scalars in that case so
        // the response shape stays consistent (no NPE). The normal path returns the persisted package.
        if (saved == null) {
            return new ProductPackageDto(
                null, dto.productId(), dto.uomId(), dto.productSupplierId(),
                dto.name(), dto.description(), dto.gtin(), dto.quantity(),
                null, null, null, null, null, null, null, null);
        }
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

    // uom is a nullable @ManyToOne — null-guard the reference lookup. (The create branch only calls this
    // when uomId is non-null per the guard, but the null-guard is kept for safety/symmetry.)
    private UnitOfMeasure resolveUom(String uomId) {
        if (uomId == null) {
            return null;
        }
        return uomRepo.getReferenceById(uomId);
    }
}
