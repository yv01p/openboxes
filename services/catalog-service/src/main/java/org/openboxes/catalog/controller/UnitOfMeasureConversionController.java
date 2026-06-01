package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.UnitOfMeasureConversionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit (UnitOfMeasureConversion has zero React callers; FD#1 default GET-only). Its
// own dedicated controller, endpoint /api/unitOfMeasureConversions (plural, per the T1 audit endpoint).
// NO POST/PUT/DELETE. NO finder endpoint — findConversionRate is a service+repo method only (no
// consumer needs the HTTP surface; the Grails Invoice/Order callers use GORM directly).
@RestController
@RequestMapping("/api/unitOfMeasureConversions")
public class UnitOfMeasureConversionController {
    private final UnitOfMeasureConversionService service;

    public UnitOfMeasureConversionController(UnitOfMeasureConversionService service) {
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
}
