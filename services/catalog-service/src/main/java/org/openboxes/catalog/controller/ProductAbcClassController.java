package org.openboxes.catalog.controller;

import org.openboxes.catalog.service.ProductService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

// RC-16 (T4): NEW controller, separate from ProductController, whose @RequestMapping("/api/product")
// + @GetMapping("/{id}") would make a sibling /api/product/abcClasses collide with /{id}. Uses the
// PLURAL path /api/products/abcClasses. Returns the {data:[...]} envelope (matches other reads).
@RestController
public class ProductAbcClassController {
    private final ProductService service;
    public ProductAbcClassController(ProductService service) { this.service = service; }

    @GetMapping("/api/products/abcClasses")
    public Map<String, Object> abcClasses() {
        List<String> data = service.distinctAbcClasses();
        return Map.of("data", data);
    }
}
