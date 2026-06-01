package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.ProductCatalogItem;
import org.openboxes.catalog.repository.ProductCatalogItemRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache, refresh-on-miss. Phase 3 RC-6 fix: refresh-on-empty in getAll().
@Component
public class ProductCatalogItemCache {
    private final ProductCatalogItemRepository repo;
    private final Map<String, ProductCatalogItem> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public ProductCatalogItemCache(ProductCatalogItemRepository repo) {
        this.repo = repo;
    }

    public Optional<ProductCatalogItem> get(String id) {
        if (!loaded) refresh();
        ProductCatalogItem cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(pci -> byId.put(pci.getId(), pci));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<ProductCatalogItem> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(pci -> byId.put(pci.getId(), pci));
        loaded = true;
    }

    // Test-isolation hook: replaces the prior reflective clearCaches() helper.
    // Public visibility intentional — accessed from CatalogServiceIntegrationTest.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
