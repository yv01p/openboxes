package org.openboxes.inventory.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.util.List;

@Component
public class CatalogReadClient {
    private final RestClient http;
    public CatalogReadClient(@Value("${openboxes.services.catalog.base-url}") String baseUrl) {
        this.http = RestClient.builder().baseUrl(baseUrl).build();
    }
    public List<String> distinctAbcClasses(String obxToken) {
        AbcClassesResponse resp = http.get()
            .uri("/api/products/abcClasses")
            .header("Cookie", "obx_token=" + obxToken)
            .retrieve()
            .body(AbcClassesResponse.class);
        return (resp == null || resp.data() == null) ? List.of() : resp.data();
    }
    public record AbcClassesResponse(List<String> data) {}
}
