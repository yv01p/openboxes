package org.openboxes.organization.controller;

import org.openboxes.organization.dto.PartyDto;
import org.openboxes.organization.service.PartyService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/organization/party")
public class PartyController {
    private final PartyService service;
    public PartyController(PartyService s) { this.service = s; }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, PartyDto>> read(@PathVariable String id) {
        return service.getById(id)
            .map(dto -> ResponseEntity.ok(Map.of("data", dto)))
            .orElse(ResponseEntity.notFound().build());
    }
}
