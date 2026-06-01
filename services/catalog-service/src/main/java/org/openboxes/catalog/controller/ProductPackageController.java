package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.ProductPackageDto;
import org.openboxes.catalog.service.ProductPackageService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

// T4 ProductPackage controller: POST (create — React ProductPackageApi.js save) + GET (cutover load
// read, by id and by ?productSupplier). No PUT/DELETE per YAGNI (React only calls save).
// Backend-only per design §4 — NO nginx repoint, NO React change, NO through-nginx e2e until CUT.
@RestController
@RequestMapping("/api/productPackages")
public class ProductPackageController {
    private final ProductPackageService service;

    public ProductPackageController(ProductPackageService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) String productSupplier) {
        return Map.of("data", service.list(productSupplier));
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(pp -> ResponseEntity.ok(Map.of("data", pp)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ProductPackageDto dto) {
        ProductPackageDto created = service.save(dto);
        return ResponseEntity.ok(Map.of("data", created));
    }
}
