package org.openboxes.catalog.controller;

import org.openboxes.catalog.dto.CategoryDto;
import org.openboxes.catalog.service.CategoryService;
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

// T12: full CRUD (the only write task in this phase per FD#3).
@RestController
@RequestMapping("/api/category")
public class CategoryController {
    private final CategoryService service;

    public CategoryController(CategoryService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list() {
        return Map.of("data", service.list());
    }

    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return service.get(id)
            .<ResponseEntity<Map<String, Object>>>map(c -> ResponseEntity.ok(Map.of("data", c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody CategoryDto dto) {
        CategoryDto created = service.save(dto);
        return ResponseEntity.ok(Map.of("data", created));
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable String id, @RequestBody CategoryDto dto) {
        return service.update(id, dto)
            .<ResponseEntity<Map<String, Object>>>map(c -> ResponseEntity.ok(Map.of("data", c)))
            .orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        return service.delete(id)
            ? ResponseEntity.noContent().build()
            : ResponseEntity.notFound().build();
    }
}
