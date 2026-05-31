package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.Category;
import org.openboxes.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache. Plan calls for "refresh-on-write" semantics; CategoryService is GET-only today (per T1 audit),
// so refresh() is package-private and behaves like UnitOfMeasureCache (refresh-on-miss) until writes arrive.
@Component
public class CategoryCache {
    private final CategoryRepository repo;
    private final Map<String, Category> byId = new ConcurrentHashMap<>();
    private volatile boolean loaded = false;

    public CategoryCache(CategoryRepository repo) {
        this.repo = repo;
    }

    public Optional<Category> get(String id) {
        if (!loaded) refresh();
        Category cached = byId.get(id);
        if (cached == null) {
            // refresh-on-miss for individual ID
            repo.findById(id).ifPresent(c -> byId.put(c.getId(), c));
            return Optional.ofNullable(byId.get(id));
        }
        return Optional.of(cached);
    }

    public List<Category> getAll() {
        if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
        return List.copyOf(byId.values());
    }

    // Package-private until Category write paths land (Phase 5.5+); expose publicly when CategoryService.save() introduces a write flow that needs cache refresh.
    synchronized void refresh() {
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
