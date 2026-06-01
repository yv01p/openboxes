package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.ProductSupplierPreferenceDto;
import org.openboxes.catalog.service.ProductSupplierPreferenceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// T3 ProductSupplierPreference controller: batch POST (form's saveOrUpdateBatch, routed to
// catalog at CUT) + GET with ?productSupplier filter (cutover load read) + DELETE.
// Backend-only per design §4 — NO nginx repoint, NO React change, NO through-nginx e2e until CUT.
@RestController
@RequestMapping("/api/productSupplierPreferences")
public class ProductSupplierPreferenceController {
    private final ProductSupplierPreferenceService service;

    public ProductSupplierPreferenceController(ProductSupplierPreferenceService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String productSupplier) {
        return Map.of("data", service.list(productSupplier));
    }

    @PostMapping("/batch")
    public ResponseEntity<Map<String, Object>> batch(@RequestBody List<ProductSupplierPreferenceDto> dtos) {
        List<ProductSupplierPreferenceDto> saved = service.saveOrUpdateBatch(dtos);
        return ResponseEntity.ok(Map.of("data", saved));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
