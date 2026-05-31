package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.UnitOfMeasure;
import org.openboxes.catalog.repository.UnitOfMeasureRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class UnitOfMeasureCache {
    private final UnitOfMeasureRepository repo;
    private final Map<String, UnitOfMeasure> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public UnitOfMeasureCache(UnitOfMeasureRepository repo) {
        this.repo = repo;
    }

    public Optional<UnitOfMeasure> get(String id) {
        if (!loaded) refresh();
        UnitOfMeasure cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(u -> byId.put(u.getId(), u));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<UnitOfMeasure> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(u -> byId.put(u.getId(), u));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
