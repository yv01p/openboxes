package org.openboxes.catalog.repository;

import org.openboxes.catalog.entity.ProductSupplier;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface ProductSupplierRepository extends JpaRepository<ProductSupplier, String> {
    // Plan-specified finders, pre-staged for downstream filtered queries (e.g. a product's suppliers /
    // a supplier's products) reused by T3–T5. Not yet called by T2's unfiltered list() — kept per plan
    // Step 4, mirroring the pre-staged-finder convention in SynonymRepository.
    List<ProductSupplier> findByProductId(String productId);
    List<ProductSupplier> findBySupplierId(String supplierId);

    // Task LQ: the Product Sources list page (design §5). Optional filters via null-guards; the
    // preferenceTypes filter is a NON-multiplying EXISTS subquery (NOT a JOIN — a JOIN duplicates a
    // supplier that has several matching preferences and inflates totalCount/page count). An empty
    // preferenceTypes list is invalid for JPQL `IN ()` in some providers, so the service passes null
    // (not an empty list) when the param is absent/empty; `:preferenceTypes IS NULL` then short-circuits.
    //
    // @EntityGraph (NOT a JPQL JOIN FETCH) eagerly loads `product` for the page so the list response
    // carries productName with no N+1. An explicit fetch-free countQuery is declared so getTotalElements()
    // is a correct distinct-supplier count (the EntityGraph fetch must never leak into the count query).
    //
    // Task LQ2: ALSO eagerly fetch the defaultProductPackage @ManyToOne chain (defaultProductPackage,
    // its uom, and its productPrice) so the derived packageSize/packagePrice/unitPrice columns are
    // computed without N+1. These are ALL @ManyToOne (single-valued), so they are SAFE to fetch alongside
    // pagination — they do NOT multiply rows or trigger in-memory pagination. Preferences are NOT a
    // mapped association on ProductSupplier (the FK lives on ProductSupplierPreference), so they cannot
    // be fetched here anyway; they are loaded by a SECOND batch query in the service
    // (findByProductSupplierIdIn) grouped by supplier id — never per-row (which would N+1). (Even if a
    // @OneToMany were added, a collection in a paginated @EntityGraph would force in-memory pagination,
    // so the separate batch query would remain the right approach.)
    @EntityGraph(attributePaths = {
        "product",
        "defaultProductPackage",
        "defaultProductPackage.uom",
        "defaultProductPackage.productPrice"
    })
    @Query(
        value = "SELECT ps FROM ProductSupplier ps WHERE "
            + "(:product IS NULL OR ps.product.id = :product) AND "
            + "(:supplier IS NULL OR ps.supplierId = :supplier) AND "
            + "(:preferenceTypes IS NULL OR EXISTS ("
            + "  SELECT 1 FROM ProductSupplierPreference p "
            + "  WHERE p.productSupplier = ps AND p.preferenceTypeId IN :preferenceTypes))",
        countQuery = "SELECT COUNT(ps) FROM ProductSupplier ps WHERE "
            + "(:product IS NULL OR ps.product.id = :product) AND "
            + "(:supplier IS NULL OR ps.supplierId = :supplier) AND "
            + "(:preferenceTypes IS NULL OR EXISTS ("
            + "  SELECT 1 FROM ProductSupplierPreference p "
            + "  WHERE p.productSupplier = ps AND p.preferenceTypeId IN :preferenceTypes))"
    )
    Page<ProductSupplier> findFiltered(
        @Param("product") String product,
        @Param("supplier") String supplier,
        @Param("preferenceTypes") List<String> preferenceTypes,
        Pageable pageable
    );
}
