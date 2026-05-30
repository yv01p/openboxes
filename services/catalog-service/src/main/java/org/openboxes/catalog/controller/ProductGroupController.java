package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.ProductGroupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit.
@RestController
@RequestMapping("/api/productGroup")
public class ProductGroupController {
    private final ProductGroupService service;

    public ProductGroupController(ProductGroupService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(g -> ResponseEntity.ok(Map.of("data", g)))
            .orElse(ResponseEntity.notFound().build());
    }
}
