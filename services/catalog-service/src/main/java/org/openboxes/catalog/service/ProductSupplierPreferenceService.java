package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.ProductSupplierPreferenceDto;
import org.openboxes.catalog.entity.ProductSupplier;
import org.openboxes.catalog.entity.ProductSupplierPreference;
import org.openboxes.catalog.repository.ProductSupplierPreferenceRepository;
import org.openboxes.catalog.repository.ProductSupplierRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

// T3 ProductSupplierPreference write service. Mirrors T2 service pattern + app-layer pair-uniqueness
// per A9 (NO DB unique index — enforce in-app before insert, accept-the-residual-race).
// Batch logic: per-item upsert (NO delete-of-missing) + in-batch dedup + per-item DB pair-check.
// Class-level @Transactional(readOnly=true); write methods override with read-write @Transactional.
@Service
@Transactional(readOnly = true)
public class ProductSupplierPreferenceService {
    private final ProductSupplierPreferenceRepository repo;
    private final ProductSupplierRepository productSupplierRepo;

    public ProductSupplierPreferenceService(
        ProductSupplierPreferenceRepository repo,
        ProductSupplierRepository productSupplierRepo
    ) {
        this.repo = repo;
        this.productSupplierRepo = productSupplierRepo;
    }

    public List<ProductSupplierPreferenceDto> list(String productSupplierId) {
        if (productSupplierId == null) {
            return repo.findAll().stream().map(ProductSupplierPreferenceDto::from).toList();
        }
        return repo.findByProductSupplierId(productSupplierId).stream()
            .map(ProductSupplierPreferenceDto::from).toList();
    }

    @Transactional
    public List<ProductSupplierPreferenceDto> saveOrUpdateBatch(List<ProductSupplierPreferenceDto> dtos) {
        // In-batch dedup tracker: (productSupplierId, destinationPartyId) pairs seen so far.
        // Use a Set<PairKey> to detect duplicates within the batch before DB flush.
        Set<PairKey> seenPairs = new HashSet<>();
        List<ProductSupplierPreferenceDto> results = new ArrayList<>();

        for (ProductSupplierPreferenceDto dto : dtos) {
            PairKey pair = new PairKey(dto.productSupplierId(), dto.destinationPartyId());
            if (!seenPairs.add(pair)) {
                // Duplicate pair within this batch — throw immediately.
                throw new DuplicatePreferenceException(
                    "duplicate (productSupplier, destinationParty) pair in batch: " +
                    "productSupplierId=" + dto.productSupplierId() +
                    ", destinationPartyId=" + dto.destinationPartyId()
                );
            }

            ProductSupplierPreference psp;
            Optional<ProductSupplierPreference> existingById =
                (dto.id() != null) ? repo.findById(dto.id()) : Optional.empty();
            if (existingById.isPresent()) {
                // UPDATE path: load existing entity, applyTo, resolve FK.
                psp = existingById.get();
                dto.applyTo(psp);
                psp.setProductSupplier(resolveProductSupplier(dto.productSupplierId()));
            } else {
                // INSERT path: new entity, generated UUID id, applyTo, resolve FK.
                psp = dto.toEntity();
                psp.setId(UUID.randomUUID().toString());
                psp.setProductSupplier(resolveProductSupplier(dto.productSupplierId()));
            }

            // DB pair-check (app-layer uniqueness per A9): find existing row with same pair.
            // Spring Data AUTO flush flushes prior saves before each finder runs, so the DB check
            // sees earlier saves in this batch — but in-batch Set dedup is still needed to reject
            // duplicates before any write. Self-exclusion (!existing.id.equals(psp.id)) prevents
            // false 409 on UPDATE when the loaded row's own pair is unchanged.
            // Use the IsNull finder for null destinationPartyId (SQL `= NULL` never matches).
            Optional<ProductSupplierPreference> existing;
            if (dto.destinationPartyId() == null) {
                existing = repo.findByProductSupplierIdAndDestinationPartyIdIsNull(dto.productSupplierId());
            } else {
                existing = repo.findByProductSupplierIdAndDestinationPartyId(
                    dto.productSupplierId(),
                    dto.destinationPartyId()
                );
            }
            // If a row exists and it's NOT the current entity (different id) → duplicate pair.
            if (existing.isPresent() && !existing.get().getId().equals(psp.getId())) {
                throw new DuplicatePreferenceException(
                    "duplicate (productSupplier, destinationParty) pair: " +
                    "productSupplierId=" + dto.productSupplierId() +
                    ", destinationPartyId=" + dto.destinationPartyId()
                );
            }

            results.add(ProductSupplierPreferenceDto.from(repo.save(psp)));
        }
        return results;
    }

    @Transactional
    public boolean delete(String id) {
        if (!repo.existsById(id)) {
            return false;
        }
        repo.deleteById(id);
        return true;
    }

    // productSupplier is the only @ManyToOne FK (catalog-internal, NOT NULL per entity's optional=false).
    // Resolve a reference so the FK column is set without loading the full ProductSupplier graph.
    private ProductSupplier resolveProductSupplier(String productSupplierId) {
        if (productSupplierId == null) {
            return null;  // productSupplier is NOT NULL — Hibernate will reject at flush.
        }
        return productSupplierRepo.getReferenceById(productSupplierId);
    }

    // Helper record for in-batch dedup tracking. Equals/hashCode on both fields.
    private record PairKey(String productSupplierId, String destinationPartyId) {}
}
