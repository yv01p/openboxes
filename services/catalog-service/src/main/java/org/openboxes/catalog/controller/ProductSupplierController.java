package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.ProductSupplierDto;
import org.openboxes.catalog.service.ProductSupplierService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// Full CRUD per T1 (React callers: list table, getById, save POST, update PUT, delete).
@RestController
@RequestMapping("/api/productSuppliers")
public class ProductSupplierController {
    private final ProductSupplierService service;

    public ProductSupplierController(ProductSupplierService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(ps -> ResponseEntity.ok(Map.of("data", ps)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody ProductSupplierDto dto) {
        ProductSupplierDto created = service.save(dto);
        return ResponseEntity.ok(Map.of("data", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody ProductSupplierDto dto) {
        return service.update(id, dto)
            .<ResponseEntity<Map<String, Object>>>map(ps -> ResponseEntity.ok(Map.of("data", ps)))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
