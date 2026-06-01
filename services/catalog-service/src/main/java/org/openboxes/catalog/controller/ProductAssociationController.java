package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.ProductAssociationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit (ProductAssociation has zero React callers; verbs = "none"; FD#1 default
// GET-only). Its own dedicated controller, endpoint /api/productAssociations (plural). NO POST/PUT/DELETE
// — writes stay in the Grails GSP ProductAssociationController (validator + beforeDelete remain
// Grails-side; see the entity header forward-compat note).
@RestController
@RequestMapping("/api/productAssociations")
public class ProductAssociationController {
    private final ProductAssociationService service;

    public ProductAssociationController(ProductAssociationService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(pa -> ResponseEntity.ok(Map.of("data", pa)))
            .orElse(ResponseEntity.notFound().build());
    }
}
