package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.AttributeService;
import org.openboxes.catalog.service.ProductTypeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

// GET-only per T1 audit. Bundles reference-data reads (ProductType + Attribute) per plan Step 6.
@RestController
public class ReferenceController {
    private final ProductTypeService productTypeService;
    private final AttributeService attributeService;

    public ReferenceController(ProductTypeService productTypeService, AttributeService attributeService) {
        this.productTypeService = productTypeService;
        this.attributeService = attributeService;
    }

    @GetMapping("/api/productType")
    public Map<String, Object> listProductTypes() {
        return Map.of("data", productTypeService.list());
    }

    @GetMapping("/api/productType/{id}")
    public ResponseEntity<Map<String, Object>> getProductType(@PathVariable String id) {
        return productTypeService.get(id)
            .<ResponseEntity<Map<String, Object>>>map(p -> ResponseEntity.ok(Map.of("data", p)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/api/attribute")
    public Map<String, Object> listAttributes() {
        return Map.of("data", attributeService.list());
    }

    @GetMapping("/api/attribute/{id}")
    public ResponseEntity<Map<String, Object>> getAttribute(@PathVariable String id) {
        return attributeService.get(id)
            .<ResponseEntity<Map<String, Object>>>map(a -> ResponseEntity.ok(Map.of("data", a)))
            .orElse(ResponseEntity.notFound().build());
    }
}
