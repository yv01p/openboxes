package org.openboxes.catalog.service;

import org.openboxes.catalog.dto.TagDto;
import org.openboxes.catalog.repository.TagRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

// Tag GET-only per T1 Step 7 disposition; FD#9 forced decision deferred to whichever
// later phase first introduces catalog-side Tag writes.
@Service
@Transactional(readOnly = true)
public class TagService {
    private final TagRepository repo;

    public TagService(TagRepository repo) {
        this.repo = repo;
    }

    public Optional<TagDto> get(String id) {
        return repo.findById(id).map(TagDto::from);
    }

    public List<TagDto> list() {
        return repo.findAll().stream().map(TagDto::from).toList();
    }
}
