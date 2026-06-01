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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

// Full CRUD per T1 (React callers: list table, getById, save POST, update PUT, delete).
@RestController
@RequestMapping("/api/productSuppliers")
public class ProductSupplierController {
    private final ProductSupplierService service;

    public ProductSupplierController(ProductSupplierService service) {
        this.service = service;
    }

    // Task LQ: the Product Sources list page. All params are optional (the React hook strips empty
    // values). The request param is named `defaultPreferenceTypes` (what the hook sends) while the
    // service param is `preferenceTypes`. Assembles the {data, totalCount} envelope the table hook reads.
    @GetMapping
    public Map<String, Object> list(
        @RequestParam(required = false) String product,
        @RequestParam(required = false) String supplier,
        @RequestParam(required = false) List<String> defaultPreferenceTypes,
        @RequestParam(required = false) Integer offset,
        @RequestParam(required = false) Integer max,
        @RequestParam(required = false) String sort,
        @RequestParam(required = false) String order
    ) {
        var result = service.list(product, supplier, defaultPreferenceTypes, offset, max, sort, order);
        return Map.of("data", result.data(), "totalCount", result.totalCount());
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
