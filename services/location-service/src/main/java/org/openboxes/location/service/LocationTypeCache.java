package org.openboxes.location.service;

import jakarta.annotation.PostConstruct;
import org.openboxes.location.entity.LocationType;
import org.openboxes.location.repository.LocationTypeRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class LocationTypeCache {
    private final LocationTypeRepository repo;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, LocationType> byId = new HashMap<>();

    public LocationTypeCache(LocationTypeRepository r) { this.repo = r; }

    @PostConstruct
    public void refresh() {
        lock.writeLock().lock();
        try {
            Map<String, LocationType> fresh = new HashMap<>();
            for (LocationType lt : repo.findAll()) fresh.put(lt.getId(), lt);
            this.byId = fresh;
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<LocationType> getById(String id) {
        Optional<LocationType> hit = Optional.ofNullable(byId.get(id));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byId.get(id));
    }

    public List<LocationType> getAll() { return List.copyOf(byId.values()); }
}
