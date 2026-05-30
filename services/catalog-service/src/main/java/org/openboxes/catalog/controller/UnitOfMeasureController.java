package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.UnitOfMeasureService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit. Handles BOTH UoM and UoMClass paths (no separate UoMClassController per plan Step 5).
@RestController
public class UnitOfMeasureController {
    private final UnitOfMeasureService service;

    public UnitOfMeasureController(UnitOfMeasureService service) {
        this.service = service;
    }

    @GetMapping("/api/unitOfMeasure")
    public Map<String, Object> listUoms() {
        return Map.of("data", service.listUoms());
    }

    @GetMapping("/api/unitOfMeasure/{id}")
    public ResponseEntity<Map<String, Object>> getUom(@PathVariable String id) {
        return service.getUom(id)
            .<ResponseEntity<Map<String, Object>>>map(u -> ResponseEntity.ok(Map.of("data", u)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/unitOfMeasureClass")
    public Map<String, Object> listUomClasses() {
        return Map.of("data", service.listUomClasses());
    }

    @GetMapping("/api/unitOfMeasureClass/{id}")
    public ResponseEntity<Map<String, Object>> getUomClass(@PathVariable String id) {
        return service.getUomClass(id)
            .<ResponseEntity<Map<String, Object>>>map(c -> ResponseEntity.ok(Map.of("data", c)))
            .orElse(ResponseEntity.notFound().build());
    }
}
