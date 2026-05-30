package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.SynonymDto;
import org.openboxes.catalog.repository.SynonymRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// Synonym GET-only per T1 audit (no React surface today); FD#10 validator port deferred
// until Synonym writes are introduced. SynonymRepository.countByProductIdAndLocaleAndSynonymTypeCode
// is already defined and will back the validator when writes arrive.
@Service
@Transactional(readOnly = true)
public class SynonymService {
    private final SynonymRepository repo;

    public SynonymService(SynonymRepository repo) {
        this.repo = repo;
    }

    public Optional<SynonymDto> get(String id) {
        return repo.findById(id).map(SynonymDto::from);
    }

    public List<SynonymDto> list() {
        return repo.findAll().stream().map(SynonymDto::from).toList();
    }
}
