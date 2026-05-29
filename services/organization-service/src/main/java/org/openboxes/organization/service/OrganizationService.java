package org.openboxes.organization.service;

import org.openboxes.organization.dto.CreateOrganizationCommand;
import org.openboxes.organization.dto.OrganizationDto;
import org.openboxes.organization.entity.Organization;
import org.openboxes.organization.entity.PartyType;
import org.openboxes.organization.repository.OrganizationRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
@Transactional
public class OrganizationService {

    private static final String DEFAULT_ORGANIZATION_CODE = "ORG";

    private final OrganizationRepository repo;
    private final PartyTypeCache partyTypeCache;
    private final OrganizationIdentifierService identifierService;

    public OrganizationService(OrganizationRepository r, PartyTypeCache c, OrganizationIdentifierService i) {
        this.repo = r;
        this.partyTypeCache = c;
        this.identifierService = i;
    }

    public Optional<OrganizationDto> getById(String id) {
        return repo.findById(id).map(OrganizationDto::from);
    }

    public List<OrganizationDto> list(String q, List<String> roleTypes, Boolean active, Integer max, Integer offset) {
        boolean hasRoles = roleTypes != null && !roleTypes.isEmpty();
        return repo.findFiltered(q, active, hasRoles, hasRoles ? roleTypes : List.of())
            .stream()
            .skip(offset == null ? 0 : offset)
            .limit(max == null ? 50 : max)
            .map(OrganizationDto::from)
            .toList();
    }

    public OrganizationDto create(CreateOrganizationCommand cmd) {
        Organization org = new Organization();
        org.setName(cmd.name());
        org.setDescription(cmd.description());
        org.setCode(cmd.code() == null || cmd.code().isBlank()
            ? identifierService.generate(cmd.name())
            : cmd.code());

        PartyType orgType = partyTypeCache.findByCode(DEFAULT_ORGANIZATION_CODE)
            .orElseThrow(() -> new IllegalStateException("PartyType 'ORG' not seeded — A27 must hold"));
        org.setPartyType(orgType);

        // active defaults to true via entity field initializer (CDR R1 §2.3).
        // No default PartyRoles — verified against Grails OrganizationService.createOrganization at T1 baseline capture.

        return OrganizationDto.from(repo.save(org));
    }
}
