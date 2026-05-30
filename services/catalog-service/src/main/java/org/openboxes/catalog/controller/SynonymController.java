package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.SynonymService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit; FD#10 validator port deferred until Synonym writes are introduced.
@RestController
@RequestMapping("/api/synonym")
public class SynonymController {
    private final SynonymService service;

    public SynonymController(SynonymService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(s -> ResponseEntity.ok(Map.of("data", s)))
            .orElse(ResponseEntity.notFound().build());
    }
}
