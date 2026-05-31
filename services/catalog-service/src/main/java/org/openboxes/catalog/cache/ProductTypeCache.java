package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.ProductType;
import org.openboxes.catalog.repository.ProductTypeRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class ProductTypeCache {
    private final ProductTypeRepository repo;
    private final Map<String, ProductType> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public ProductTypeCache(ProductTypeRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductType> get(String id) {
        if (!loaded) refresh();
        ProductType cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(pt -> byId.put(pt.getId(), pt));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<ProductType> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(pt -> byId.put(pt.getId(), pt));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
