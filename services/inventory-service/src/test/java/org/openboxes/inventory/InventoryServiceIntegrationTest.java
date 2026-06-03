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
import org.openboxes.auth.common.test.JwtTestFixtures;
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
import static org.openboxes.auth.common.test.JwtTestFixtures.authCookie;
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
        r.add("openboxes.jwt.secret", () -> JwtTestFixtures.TEST_SECRET);
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
    // self-contained. Stubbed in @BeforeEach to return {B, D}; the facility scope adds InventoryLevel rows.
    // {B, D} is chosen (rather than already-sorted {A, B}) so the union's sorted order differs from any
    // insertion order — this is what makes list_validFacility actually prove the TreeSet SORT.
    @MockBean CatalogReadClient catalogClient;

    @BeforeEach
    void stubCatalog() {
        when(catalogClient.distinctAbcClasses(any())).thenReturn(List.of("B", "D"));
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
            // Assert a distinctive non-id getter on each repo-less entity (not just isNotNull) so a wrong
            // @Column(name=...) on a nullable field is caught — a NOT-NULL-only insert would not reveal it.
            InventoryItem ii = em.find(InventoryItem.class, "ii-1");
            assertThat(ii).isNotNull();
            assertThat(ii.getLotNumber()).isEqualTo("LOT9");

            ProductAvailability pa = em.find(ProductAvailability.class, "pa-1");
            assertThat(pa).isNotNull();
            assertThat(pa.getProductCode()).isEqualTo("PC1");
            assertThat(pa.getQuantityOnHand()).isEqualTo(100);
            // @Formula quantity_not_picked = quantity_on_hand(100) - quantity_allocated(30) = 70.
            assertThat(pa.getQuantityNotPicked()).isEqualTo(70);

            Transaction tx = em.find(Transaction.class, "tx-1");
            assertThat(tx).isNotNull();
            assertThat(tx.getTransactionNumber()).isEqualTo("TXN9");

            TransactionEntry te = em.find(TransactionEntry.class, "te-1");
            assertThat(te).isNotNull();
            assertThat(te.getQuantity()).isEqualTo(5);

            TransactionSource ts = em.find(TransactionSource.class, "ts-1");
            assertThat(ts).isNotNull();
            assertThat(ts.getTransactionAction()).isEqualTo("DEBIT");

            TransactionType tt = em.find(TransactionType.class, "tt-1");
            assertThat(tt).isNotNull();
            assertThat(tt.getName()).isEqualTo("Adjustment");
            assertThat(tt.getTransactionCode()).isEqualTo("ADJ");
        } finally {
            em.close();
        }
    }

    // ---------------------------------------------------------------
    // RC-16 contract (ported from the two Grails ProductClassificationService specs).
    // ---------------------------------------------------------------

    @Test void list_validFacility_returnsSortedUniqueUnion() throws Exception {
        // mock global {B, D} UNION F1-scoped {A, D} ('' filtered out; F2's 'C' is facility-scoped -> excluded)
        // => deduped (D once) + alphabetically sorted = [A, B, D]. The ordered contains(...) matcher proves
        // SORT: insertion order would be catalog-first [B, D, A], which differs from the sorted [A, B, D].
        mvc.perform(get("/api/facilities/F1/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[*].name", contains("A", "B", "D")));
    }

    @Test void list_excludesEmptyString() throws Exception {
        // il-F1-empty has abc_class='' which the JPQL filters (abcClass <> '') -> never in the result.
        mvc.perform(get("/api/facilities/F1/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[*].name", everyItem(not(equalTo("")))));
    }

    @Test void list_facilityWithNullInventory_returnsGlobalOnly() throws Exception {
        // F3-noinv has NULL inventory_id -> the InventoryLevel query is skipped (NOT an error) -> global {B, D}.
        mvc.perform(get("/api/facilities/F3-noinv/products/classifications").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[*].name", contains("B", "D")));
    }

    @Test void list_invalidFacility_throwsAtServiceLevel() {
        // Service-level guard: no `location` row -> countLocationById == 0 -> IllegalArgumentException.
        // The guard MUST run before the catalog call; asserting the message contains the facility id pins this
        // to the facility guard (not some unrelated IllegalArgumentException). Asserted at the service (not via
        // MockMvc 500) — the real-container error dispatch is proven in T7/T9 (synthetic-payload blind spot).
        IllegalArgumentException ex =
            assertThrows(IllegalArgumentException.class, () -> service.list("NOPE", "any-token"));
        assertThat(ex.getMessage()).contains("NOPE");
    }

    @Test void list_noCookie_returns401() throws Exception {
        mvc.perform(get("/api/facilities/F1/products/classifications"))
            .andExpect(status().isUnauthorized());
    }

    @Test void list_invalidJwt_returns401() throws Exception {
        // Exercises JwtCookieAuthFilter's JwtException branch (malformed token) — distinct from the no-cookie
        // path, which skips parsing entirely. Mirrors CatalogServiceIntegrationTest.auth_returns401WithInvalidJwt.
        mvc.perform(get("/api/facilities/F1/products/classifications")
                .cookie(new jakarta.servlet.http.Cookie("obx_token", "not-a-valid-jwt")))
            .andExpect(status().isUnauthorized());
    }
}
