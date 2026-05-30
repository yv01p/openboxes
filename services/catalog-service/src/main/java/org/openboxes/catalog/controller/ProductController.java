package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.ProductDto;
import org.openboxes.catalog.service.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// FD#12: Phase 5 catalog-service exposes basic Product reads only (list + get).
// Grails ProductApiController retains cross-context actions (lotNumbersWithExpirationDate,
// availableItems, etc.) per T1 audit STAY-Grails disposition.
@RestController
@RequestMapping("/api/product")
public class ProductController {
    private final ProductService service;

    public ProductController(ProductService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        List<ProductDto> data = service.list();
        return Map.of("data", data);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(p -> ResponseEntity.ok(Map.of("data", p)))
            .orElse(ResponseEntity.notFound().build());
    }
}
