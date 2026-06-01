package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.ProductCatalogItemService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit (ProductCatalogItem has zero React callers; FD#1 default GET-only). Its own
// dedicated controller, endpoint /api/productCatalogItems (plural, per the T1 audit endpoint). NO
// POST/PUT/DELETE.
@RestController
@RequestMapping("/api/productCatalogItems")
public class ProductCatalogItemController {
    private final ProductCatalogItemService service;

    public ProductCatalogItemController(ProductCatalogItemService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(pci -> ResponseEntity.ok(Map.of("data", pci)))
            .orElse(ResponseEntity.notFound().build());
    }
}
