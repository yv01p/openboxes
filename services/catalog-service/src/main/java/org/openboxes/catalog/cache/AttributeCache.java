package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.Attribute;
import org.openboxes.catalog.repository.AttributeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class AttributeCache {
    private final AttributeRepository repo;
    private final Map<String, Attribute> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public AttributeCache(AttributeRepository repo) {
        this.repo = repo;
    }

    public Optional<Attribute> get(String id) {
        if (!loaded) refresh();
        Attribute cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(a -> byId.put(a.getId(), a));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<Attribute> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(a -> byId.put(a.getId(), a));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
