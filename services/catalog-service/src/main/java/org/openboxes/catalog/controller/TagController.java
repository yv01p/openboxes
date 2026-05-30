package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.TagService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 Step 7 disposition; FD#9 M:N write protocol deferred until catalog-side Tag writes arrive.
@RestController
@RequestMapping("/api/tag")
public class TagController {
    private final TagService service;

    public TagController(TagService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(t -> ResponseEntity.ok(Map.of("data", t)))
            .orElse(ResponseEntity.notFound().build());
    }
}
