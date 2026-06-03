package org.openboxes.inventory.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import org.openboxes.inventory.dto.ProductClassificationDto;
import org.openboxes.inventory.service.ProductClassificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;
import java.util.Map;

@RestController
public class ProductClassificationController {
    private final ProductClassificationService service;
    public ProductClassificationController(ProductClassificationService service) { this.service = service; }

    @GetMapping("/api/facilities/{facilityId}/products/classifications")
    public Map<String, Object> list(@PathVariable String facilityId, HttpServletRequest request) {
        List<ProductClassificationDto> data = service.list(facilityId, readObxToken(request));
        return Map.of("data", data);
    }

    private String readObxToken(HttpServletRequest request) {
        if (request.getCookies() != null) {
            for (Cookie c : request.getCookies()) {
                if ("obx_token".equals(c.getName())) return c.getValue();
            }
        }
        return null;
    }
}
