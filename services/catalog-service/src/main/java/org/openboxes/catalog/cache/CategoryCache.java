package org.openboxes.catalog.cache;

import org.openboxes.catalog.entity.Category;
import org.openboxes.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

// FD#7: app-level cache. CategoryService writes (T12) invalidate via clear(); reads repopulate on the
// next access (refresh-on-miss / refresh-on-empty), like UnitOfMeasureCache.
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

    public synchronized void refresh() {
        byId.clear();
        repo.findAll().forEach(c -> byId.put(c.getId(), c));
        loaded = true;
    }

    // Write-invalidation + test-isolation hook. Two callers: CategoryService write paths invalidate
    // after commit (via clearCacheAfterCommit), and CatalogServiceIntegrationTest clears per-test.
    // Public visibility intentional.
    public synchronized void clear() {
        byId.clear();
        loaded = false;
    }
}
