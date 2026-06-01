package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.ProductCatalog;
import org.openboxes.catalog.repository.ProductCatalogRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class ProductCatalogCache {
    private final ProductCatalogRepository repo;
    private final Map<String, ProductCatalog> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public ProductCatalogCache(ProductCatalogRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductCatalog> get(String id) {
        if (!loaded) refresh();
        ProductCatalog cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(pc -> byId.put(pc.getId(), pc));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<ProductCatalog> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(pc -> byId.put(pc.getId(), pc));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
