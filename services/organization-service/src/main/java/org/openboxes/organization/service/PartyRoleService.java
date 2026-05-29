package org.openboxes.organization.service;

import org.openboxes.organization.dto.PartyRoleDto;
import org.openboxes.organization.repository.PartyRoleRepository;
import org.springframework.stereotype.Service;
import java.util.List;

@Service
public class PartyRoleService {
    private final PartyRoleRepository repo;
    public PartyRoleService(PartyRoleRepository r) { this.repo = r; }

    public List<PartyRoleDto> findBy(String partyId, String roleType) {
        List<?> rows;
        if (partyId != null && roleType != null) rows = repo.findByPartyIdAndRoleType(partyId, roleType);
        else if (partyId != null) rows = repo.findByPartyId(partyId);
        else if (roleType != null) rows = repo.findByRoleType(roleType);
        else rows = repo.findAll();
        return rows.stream().map(r -> PartyRoleDto.from((org.openboxes.organization.entity.PartyRole) r)).toList();
    }
}
