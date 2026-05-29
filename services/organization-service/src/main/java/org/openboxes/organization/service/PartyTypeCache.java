package org.openboxes.organization.service;

import jakarta.annotation.PostConstruct;
import org.openboxes.organization.entity.PartyType;
import org.openboxes.organization.repository.PartyTypeRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class PartyTypeCache {
    private final PartyTypeRepository repo;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, PartyType> byId = new HashMap<>();
    private volatile Map<String, PartyType> byCode = new HashMap<>();

    public PartyTypeCache(PartyTypeRepository r) { this.repo = r; }

    @PostConstruct
    public void refresh() {
        lock.writeLock().lock();
        try {
            Map<String, PartyType> freshById = new HashMap<>();
            Map<String, PartyType> freshByCode = new HashMap<>();
            for (PartyType pt : repo.findAll()) {
                freshById.put(pt.getId(), pt);
                freshByCode.put(pt.getCode(), pt);
            }
            this.byId = freshById;
            this.byCode = freshByCode;
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<PartyType> getById(String id) {
        Optional<PartyType> hit = Optional.ofNullable(byId.get(id));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byId.get(id));
    }

    public Optional<PartyType> findByCode(String code) {
        Optional<PartyType> hit = Optional.ofNullable(byCode.get(code));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byCode.get(code));
    }

    public List<PartyType> getAll() {
        if (byId.isEmpty()) refresh();  // RC-6 fix: refresh-on-empty
        return List.copyOf(byId.values());
    }
}
