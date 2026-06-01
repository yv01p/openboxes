package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.UnitOfMeasureConversionCache;
import org.openboxes.catalog.dto.UnitOfMeasureConversionDto;
import org.openboxes.catalog.repository.UnitOfMeasureConversionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit (UnitOfMeasureConversion has zero React callers; FD#1). CACHE-backed for
// get/list (heuristic cache per the T1 audit §5/§8, paired with UnitOfMeasureCache — low churn);
// the repo is injected ONLY for the ported conversionRateLookup finder, which deliberately bypasses
// the cache (see findConversionRate).
@Service
@Transactional(readOnly = true)
public class UnitOfMeasureConversionService {
    private final UnitOfMeasureConversionCache cache;
    private final UnitOfMeasureConversionRepository repo;

    public UnitOfMeasureConversionService(UnitOfMeasureConversionCache cache,
                                          UnitOfMeasureConversionRepository repo) {
        this.cache = cache;
        this.repo = repo;
    }

    public Optional<UnitOfMeasureConversionDto> get(String id) {
        return cache.get(id).map(UnitOfMeasureConversionDto::from);
    }

    public List<UnitOfMeasureConversionDto> list() {
        return cache.getAll().stream().map(UnitOfMeasureConversionDto::from).toList();
    }

    // Ported conversionRateLookup (Grails UnitOfMeasureConversion.groovy): the most-recent ACTIVE
    // conversion rate for the from→to UoM CODES (Optional.empty if none). Deliberately bypasses the
    // cache — it needs UoM codes, and running it as a real query inside this read tx avoids navigating
    // fromUnitOfMeasure.code off a detached/cached entity (LazyInitializationException risk; the T7
    // lesson). Currently has NO catalog-service consumer: the Grails callers (Invoice.groovy,
    // Order.groovy) use GORM directly and stay Grails-side; ported here as the entity's signature
    // capability for a future cutover (no REST endpoint — the plan asked for a finder, not an endpoint).
    public Optional<java.math.BigDecimal> findConversionRate(String fromCode, String toCode) {
        return repo.findActiveConversionRates(fromCode, toCode).stream().findFirst();
    }
}
