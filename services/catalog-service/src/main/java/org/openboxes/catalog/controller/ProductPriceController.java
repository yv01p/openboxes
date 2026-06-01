package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.ProductPriceDto;
import org.openboxes.catalog.service.ProductPriceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// T5 ProductPrice controller: GET by id only (cutover load read — the React form does
// GET /api/productPrices/{id}). No POST/PUT/DELETE: prices are written through the package endpoint
// (POST /api/productPackages), not directly. Backend-only per design §4 — NO nginx repoint, NO React
// change, NO through-nginx e2e until CUT.
@RestController
@RequestMapping("/api/productPrices")
public class ProductPriceController {
    private final ProductPriceService service;

    public ProductPriceController(ProductPriceService service) {
        this.service = service;
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(pp -> ResponseEntity.ok(Map.of("data", pp)))
            .orElse(ResponseEntity.notFound().build());
    }
}
