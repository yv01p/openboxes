package org.openboxes.organization.controller;

import jakarta.validation.Valid;
import org.openboxes.organization.dto.CreateOrganizationCommand;
import org.openboxes.organization.dto.OrganizationDto;
import org.openboxes.organization.service.OrganizationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/organization")
public class OrganizationController {

    private final OrganizationService service;
    public OrganizationController(OrganizationService s) { this.service = s; }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, OrganizationDto>> read(@PathVariable String id) {
        return service.getById(id)
            .map(dto -> ResponseEntity.ok(Map.of("data", dto)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public Map<String, List<OrganizationDto>> list(
        @RequestParam(required = false) String q,
        @RequestParam(name = "roleType", required = false) List<String> roleTypes,
        @RequestParam(required = false) Boolean active,
        @RequestParam(required = false) Integer max,
        @RequestParam(required = false) Integer offset
    ) {
        return Map.of("data", service.list(q, roleTypes, active, max, offset));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Map<String, String>> create(@Valid @RequestBody CreateOrganizationCommand cmd) {
        OrganizationDto created = service.create(cmd);
        return Map.of("data", Map.of("id", created.id()));
    }
}
