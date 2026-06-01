package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductSupplierDto;
import org.openboxes.catalog.entity.Product;
import org.openboxes.catalog.entity.ProductPackage;
import org.openboxes.catalog.entity.ProductPrice;
import org.openboxes.catalog.entity.ProductSupplier;
import org.openboxes.catalog.repository.ProductPackageRepository;
import org.openboxes.catalog.repository.ProductPriceRepository;
import org.openboxes.catalog.repository.ProductRepository;
import org.openboxes.catalog.repository.ProductSupplierRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.Set;
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
    private final ProductPriceRepository productPriceRepo;

    public ProductSupplierService(
        ProductSupplierRepository repo,
        ProductRepository productRepo,
        ProductPackageRepository productPackageRepo,
        ProductPriceRepository productPriceRepo
    ) {
        this.repo = repo;
        this.productRepo = productRepo;
        this.productPackageRepo = productPackageRepo;
        this.productPriceRepo = productPriceRepo;
    }

    // Task LQ: allowlist of ProductSupplier scalar/string properties a client may sort by. Any sort
    // outside this set (or null) falls back to dateCreated, so a client-supplied `sort` can never raise
    // PropertyReferenceException (→ 500). dateCreated MUST be present: it is the hook's default sort.
    private static final Set<String> SORTABLE = Set.of(
        "code", "name", "productCode", "supplierName", "supplierCode",
        "manufacturerName", "ratingTypeCode", "active", "dateCreated", "lastUpdated"
    );
    private static final String DEFAULT_SORT = "dateCreated";

    // Task LQ: the Product Sources list page. Returns a bare ProductSupplierListResult (data +
    // totalCount); the controller wraps it in the {data, totalCount} transport map the React table
    // hook (useTableData) reads (res.data.data + res.data.totalCount, pages = ceil(totalCount /
    // pageSize)). All filters are optional (the hook strips empty values).
    public ProductSupplierListResult list(
        String product,
        String supplier,
        List<String> preferenceTypes,
        Integer offset,
        Integer max,
        String sort,
        String order
    ) {
        int pageSize = (max == null || max <= 0) ? 10 : max;
        int off = (offset == null || offset < 0) ? 0 : offset;
        int page = off / pageSize;

        String sortProp = (sort != null && SORTABLE.contains(sort)) ? sort : DEFAULT_SORT;
        Sort.Direction dir = "asc".equalsIgnoreCase(order) ? Sort.Direction.ASC : Sort.Direction.DESC;
        PageRequest pageable = PageRequest.of(page, pageSize, Sort.by(dir, sortProp));

        // Normalize an absent/empty preferenceTypes list to null so the repository null-guard treats it
        // as "no filter" (JPQL `IN ()` on an empty collection is invalid in some providers).
        List<String> prefs = (preferenceTypes == null || preferenceTypes.isEmpty()) ? null : preferenceTypes;

        Page<ProductSupplier> result = repo.findFiltered(product, supplier, prefs, pageable);
        return new ProductSupplierListResult(
            result.getContent().stream().map(ProductSupplierDto::from).toList(),
            result.getTotalElements()
        );
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
        ps.setContractPrice(resolveProductPrice(dto.contractPriceId()));
        return ProductSupplierDto.from(repo.save(ps));
    }

    @Transactional
    public Optional<ProductSupplierDto> update(String id, ProductSupplierDto dto) {
        return repo.findById(id).map(ps -> {
            dto.applyTo(ps);
            ps.setProduct(resolveProduct(dto.productId()));
            ps.setDefaultProductPackage(resolveProductPackage(dto.defaultProductPackageId()));
            ps.setContractPrice(resolveProductPrice(dto.contractPriceId()));
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

    // T5 forward-decl split: contractPrice is a nullable @ManyToOne. Resolved on every save/update
    // (a PUT omitting it clears it, consistent with resolveProductPackage's behavior).
    // getReferenceById(null) throws, so null-guard and return null.
    private ProductPrice resolveProductPrice(String contractPriceId) {
        if (contractPriceId == null) {
            return null;
        }
        return productPriceRepo.getReferenceById(contractPriceId);
    }
}
