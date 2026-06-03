package org.openboxes.inventory;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openboxes.inventory.client.CatalogReadClient;
import org.openboxes.inventory.entity.InventoryItem;
import org.openboxes.inventory.entity.ProductAvailability;
import org.openboxes.inventory.entity.Transaction;
import org.openboxes.inventory.entity.TransactionEntry;
import org.openboxes.inventory.entity.TransactionSource;
import org.openboxes.inventory.entity.TransactionType;
import org.openboxes.inventory.repository.InventoryLevelRepository;
import org.openboxes.inventory.repository.InventoryRepository;
import org.openboxes.inventory.service.ProductClassificationService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InventoryServiceIntegrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mariadb::getJdbcUrl);
        r.add("spring.datasource.username", mariadb::getUsername);
        r.add("spring.datasource.password", mariadb::getPassword);
        r.add("openboxes.jwt.secret", () -> "test-secret-32-chars-minimum-for-hs256-key");
        // TestContainers gives empty DB; Liquibase shadow changelogs MARK_RAN on missing tables, so skip Liquibase and let JPA create.
        r.add("spring.liquibase.enabled", () -> "false");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
        // create runs BEFORE data.sql by default; defer keeps the seed load until after Hibernate has emitted the schema.
        r.add("spring.jpa.defer-datasource-initialization", () -> "true");
        r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
        r.add("spring.sql.init.mode", () -> "always");  // override "embedded" default; runs against TestContainers MariaDB
        // CatalogReadClient is @MockBean'd below, but its constructor still resolves this @Value at context
        // startup — supply a harmless base-url so the bean definition can be built before Mockito replaces it.
        r.add("openboxes.services.catalog.base-url", () -> "http://localhost");
    }

    @Autowired MockMvc mvc;
    @Autowired ProductClassificationService service;
    @Autowired InventoryRepository inventoryRepo;
    @Autowired InventoryLevelRepository levelRepo;
    @Autowired EntityManagerFactory emf;

    // The service calls catalog-service over HTTP for the global abc classes; mock it so the test is
    // self-contained. Stubbed in @BeforeEach to return {A, B}; the facility scope adds InventoryLevel rows.
    @MockBean CatalogReadClient catalogClient;

    private static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    @BeforeEach
    void stubCatalog() {
        when(catalogClient.distinctAbcClasses(any())).thenReturn(List.of("A", "B"));
    }

    private String validToken() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
            .subject("test-user")
            .claim("roles", java.util.List.of("ROLE_BROWSER"))
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000L))
            .signWith(key).compact();
    }

    private jakarta.servlet.http.Cookie authCookie() {
        return new jakarta.servlet.http.Cookie("obx_token", validToken());
    }

    // ---------------------------------------------------------------
    // 8-entity seed-and-read round-trip (entities are getters-only, so this proves the JPA mappings
    // by reading what seed.sql inserted, not by save()).
    // ---------------------------------------------------------------

    @Test void entities_roundTripFromSeed() {
        assertThat(inventoryRepo.findById("inv-F1")).isPresent();
        assertThat(inventoryRepo.findById("inv-F2")).isPresent();

        var levelF1A = levelRepo.findById("il-F1-A");
        assertThat(levelF1A).isPresent();
        assertThat(levelF1A.get().getAbcClass()).isEqualTo("A");
        assertThat(levelF1A.get().getInventoryId()).isEqualTo("inv-F1");

        EntityManager em = emf.createEntityManager();
        try {
            assertThat(em.find(InventoryItem.class, "ii-1")).isNotNull();
            ProductAvailability pa = em.find(ProductAvailability.class, "pa-1");
            assertThat(pa).isNotNull();
            // @Formula quantity_not_picked = quantity_on_hand(100) - quantity_allocated(30) = 70.
            assertThat(pa.getQuantityNotPicked()).isEqualTo(70);
            assertThat(em.find(Transaction.class, "tx-1")).isNotNull();
            assertThat(em.find(TransactionEntry.class, "te-1")).isNotNull();
            assertThat(em.find(TransactionSource.class, "ts-1")).isNotNull();
            assertThat(em.find(TransactionType.class, "tt-1")).isNotNull();
        } finally {
            em.close();
        }
    }

    // ---------------------------------------------------------------
    // RC-16 contract (ported from the two Grails ProductClassificationService specs).
    // ---------------------------------------------------------------

    @Test void list_validFacility_returnsSortedUniqueUnion() throws Exception {
        // mock global {A, B} UNION F1-scoped {A, C} (D is F2-scoped -> excluded; '' filtered out)
        // => deduped + alphabetically sorted = [A, B, C].
        mvc.perform(get("/api/facilities/F1/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[*].name", contains("A", "B", "C")));
    }

    @Test void list_excludesEmptyString() throws Exception {
        // il-F1-empty has abc_class='' which the JPQL filters (abcClass <> '') -> never in the result.
        mvc.perform(get("/api/facilities/F1/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].name", everyItem(not(equalTo("")))));
    }

    @Test void list_facilityWithNullInventory_returnsGlobalOnly() throws Exception {
        // F3-noinv has NULL inventory_id -> the InventoryLevel query is skipped (NOT an error) -> global {A, B}.
        mvc.perform(get("/api/facilities/F3-noinv/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[*].name", contains("A", "B")));
    }

    @Test void list_invalidFacility_throwsAtServiceLevel() {
        // Service-level guard: no `location` row -> countLocationById == 0 -> IllegalArgumentException.
        // Asserted at the service (not via MockMvc 500) — the real-container error dispatch is proven in T7/T9.
        assertThrows(IllegalArgumentException.class, () -> service.list("NOPE", "any-token"));
    }

    @Test void list_noCookie_returns401() throws Exception {
        mvc.perform(get("/api/facilities/F1/products/classifications"))
            .andExpect(status().isUnauthorized());
    }
}
