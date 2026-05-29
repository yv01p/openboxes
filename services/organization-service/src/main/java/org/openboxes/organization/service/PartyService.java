package org.openboxes.organization.service;

import org.openboxes.organization.dto.PartyDto;
import org.openboxes.organization.repository.PartyRepository;
import org.springframework.stereotype.Service;
import java.util.Optional;

@Service
public class PartyService {
    private final PartyRepository repo;
    public PartyService(PartyRepository r) { this.repo = r; }

    public Optional<PartyDto> getById(String id) {
        return repo.findById(id).map(PartyDto::from);
    }
}
