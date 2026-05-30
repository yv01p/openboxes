package org.openboxes.catalog.service;

import org.openboxes.catalog.cache.UnitOfMeasureCache;
import org.openboxes.catalog.dto.UnitOfMeasureClassDto;
import org.openboxes.catalog.dto.UnitOfMeasureDto;
import org.openboxes.catalog.repository.UnitOfMeasureClassRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// GET-only per T1 audit. Handles BOTH UoM and UoMClass (no separate UoMClassService per plan File Structure).
@Service
@Transactional(readOnly = true)
public class UnitOfMeasureService {
    private final UnitOfMeasureCache uomCache;
    private final UnitOfMeasureClassRepository uomClassRepo;

    public UnitOfMeasureService(UnitOfMeasureCache uomCache, UnitOfMeasureClassRepository uomClassRepo) {
        this.uomCache = uomCache;
        this.uomClassRepo = uomClassRepo;
    }

    // UoM
    public Optional<UnitOfMeasureDto> getUom(String id) {
        return uomCache.get(id).map(UnitOfMeasureDto::from);
    }

    public List<UnitOfMeasureDto> listUoms() {
        return uomCache.getAll().stream().map(UnitOfMeasureDto::from).toList();
    }

    // UoMClass
    public Optional<UnitOfMeasureClassDto> getUomClass(String id) {
        return uomClassRepo.findById(id).map(UnitOfMeasureClassDto::from);
    }

    public List<UnitOfMeasureClassDto> listUomClasses() {
        return uomClassRepo.findAll().stream().map(UnitOfMeasureClassDto::from).toList();
    }
}
