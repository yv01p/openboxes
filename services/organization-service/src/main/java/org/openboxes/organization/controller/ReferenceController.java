package org.openboxes.organization.controller;

import org.openboxes.organization.dto.PartyRoleDto;
import org.openboxes.organization.dto.PartyTypeDto;
import org.openboxes.organization.service.PartyRoleService;
import org.openboxes.organization.service.PartyTypeCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organization")
public class ReferenceController {

    private final PartyTypeCache cache;
    private final PartyRoleService roles;

    public ReferenceController(PartyTypeCache c, PartyRoleService r) {
        this.cache = c;
        this.roles = r;
    }

    @GetMapping("/partyType")
    public Map<String, List<PartyTypeDto>> listPartyTypes() {
        return Map.of("data", cache.getAll().stream().map(PartyTypeDto::from).toList());
    }

    @GetMapping("/partyType/{id}")
    public ResponseEntity<Map<String, PartyTypeDto>> readPartyType(@PathVariable String id) {
        return cache.getById(id)
            .map(pt -> ResponseEntity.ok(Map.of("data", PartyTypeDto.from(pt))))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/partyRole")
    public Map<String, List<PartyRoleDto>> listPartyRoles(
        @RequestParam(required = false) String partyId,
        @RequestParam(required = false) String roleType
    ) {
        return Map.of("data", roles.findBy(partyId, roleType));
    }
}
