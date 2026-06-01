package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.CategoryCache;
import org.openboxes.catalog.dto.CategoryDto;
import org.openboxes.catalog.entity.Category;
import org.openboxes.catalog.repository.CategoryRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

// T12: full CRUD (the only write task in this phase per FD#3). Cache-backed reads; each write
// invalidates the app-level cache AFTER COMMIT (see clearCacheAfterCommit) so an in-flight write that
// rolls back never invalidates, and a concurrent read can never repopulate uncommitted state. This is
// the first cache-backed WRITE service, so it sets the cache-on-write pattern for later entities.
// (Residual staleness vs. Grails-side category writes is design-accepted under FD#7's read-heuristic
// cache — the cache self-heals on the next catalog-service write or refresh-on-empty.)
// Write-race disposition = accept-silent-duplicates: NO unique constraint exists on category at the DB
// or GORM layer, so there is NO app-layer uniqueness check. Class-level @Transactional(readOnly=true);
// write methods override with read-write @Transactional.
@Service
@Transactional(readOnly = true)
public class CategoryService {
    private final CategoryCache cache;
    private final CategoryRepository repo;

    public CategoryService(CategoryCache cache, CategoryRepository repo) {
        this.cache = cache;
        this.repo = repo;
    }

    public Optional<CategoryDto> get(String id) {
        return cache.get(id).map(CategoryDto::from);
    }

    public List<CategoryDto> list() {
        return cache.getAll().stream().map(CategoryDto::from).toList();
    }

    @Transactional
    public CategoryDto save(CategoryDto dto) {
        Category c = dto.toEntity();
        // id is app-assigned (Grails uuid-style; no DB auto-increment). char(38) holds a 36-char UUID.
        c.setId(UUID.randomUUID().toString());
        c.setParentCategory(resolveParent(dto.parentCategoryId()));
        Category saved = repo.save(c);
        clearCacheAfterCommit();
        return CategoryDto.from(saved);
    }

    @Transactional
    public Optional<CategoryDto> update(String id, CategoryDto dto) {
        return repo.findById(id).map(c -> {
            dto.applyTo(c);
            c.setParentCategory(resolveParent(dto.parentCategoryId()));
            Category saved = repo.save(c);
            clearCacheAfterCommit();
            return CategoryDto.from(saved);
        });
    }

    @Transactional
    public boolean delete(String id) {
        if (!repo.existsById(id)) {
            return false;
        }
        repo.deleteById(id);
        clearCacheAfterCommit();
        return true;
    }

    // Invalidate the app-level cache only once the write transaction has COMMITTED. Clearing inside the
    // tx (before commit) risks a concurrent read repopulating the cache from a snapshot that excludes the
    // uncommitted row, or a needless clear on rollback. registerSynchronization requires an active tx
    // synchronization (always true inside these @Transactional methods); the isSynchronizationActive
    // guard keeps the helper safe if ever called outside one.
    private void clearCacheAfterCommit() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    cache.clear();
                }
            });
        } else {
            cache.clear();
        }
    }

    // parentCategory is a nullable self-FK @ManyToOne. Resolved on every save/update (a PUT omitting it
    // clears it). getReferenceById(null) throws, so null-guard and return null.
    private Category resolveParent(String parentCategoryId) {
        if (parentCategoryId == null) {
            return null;
        }
        return repo.getReferenceById(parentCategoryId);
    }
}
