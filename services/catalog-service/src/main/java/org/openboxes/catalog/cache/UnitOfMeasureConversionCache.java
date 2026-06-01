package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.UnitOfMeasureConversion;
import org.openboxes.catalog.repository.UnitOfMeasureConversionRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class UnitOfMeasureConversionCache {
    private final UnitOfMeasureConversionRepository repo;
    private final Map<String, UnitOfMeasureConversion> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public UnitOfMeasureConversionCache(UnitOfMeasureConversionRepository repo) {
        this.repo = repo;
    }

    public Optional<UnitOfMeasureConversion> get(String id) {
        if (!loaded) refresh();
        UnitOfMeasureConversion cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(c -> byId.put(c.getId(), c));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<UnitOfMeasureConversion> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(c -> byId.put(c.getId(), c));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
