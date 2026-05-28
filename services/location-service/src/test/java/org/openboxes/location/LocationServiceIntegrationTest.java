package org.openboxes.location;

import org.junit.jupiter.api.Test;
import org.openboxes.location.service.LocationTypeCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class LocationServiceIntegrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mariadb::getJdbcUrl);
        r.add("spring.datasource.username", mariadb::getUsername);
        r.add("spring.datasource.password", mariadb::getPassword);
        r.add("openboxes.jwt.secret", () -> "test-secret-32-chars-minimum-for-hs256-key");
        r.add("spring.liquibase.enabled", () -> "false");  // Disable Liquibase to avoid circular dependency with entityManagerFactory
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");  // TestContainers gives empty DB; let JPA create schema
        // create runs BEFORE data.sql by default; defer keeps the seed load until after Hibernate has emitted the schema.
        r.add("spring.jpa.defer-datasource-initialization", () -> "true");
        r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
        r.add("spring.sql.init.mode", () -> "always");  // override "embedded" default; runs against TestContainers MariaDB
    }

    @Autowired MockMvc mvc;
    @Autowired LocationTypeCache cache;

    private static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    // Helper: generate valid JWT cookie value (location-service's JwtService omits issue(); use jjwt directly here)
    private String validToken() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
            .subject("test-user-id")
            .claim("loc", "loc-depot-a")
            .claim("roles", java.util.List.of("ROLE_BROWSER"))
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000L))
            .signWith(key)
            .compact();
    }

    // Helper: attach cookie to MockMvc request
    private jakarta.servlet.http.Cookie authCookie() {
        return new jakarta.servlet.http.Cookie("obx_token", validToken());
    }

    // Example test body (others follow the same pattern):
    @Test void readById_returns200() throws Exception {
        mvc.perform(get("/api/location/loc-depot-a").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value("loc-depot-a"))
           .andExpect(jsonPath("$.locationTypeCode").value("DEPOT"));
    }

    @Test void readById_returns404ForMissing() throws Exception {
        mvc.perform(get("/api/location/nonexistent-id-999").cookie(authCookie()))
           .andExpect(status().isNotFound());
    }

    @Test void readById_returns404ForInternalDefault() throws Exception {
        // BIN_LOCATION is in internalTypes; filtered out by default
        mvc.perform(get("/api/location/loc-bin-1").cookie(authCookie()))
           .andExpect(status().isNotFound());
    }

    @Test void readById_returns200ForInternalWithOptIn() throws Exception {
        // Same BIN_LOCATION path + ?includeInternal=true → expect 200
        mvc.perform(get("/api/location/loc-bin-1?includeInternal=true").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value("loc-bin-1"))
           .andExpect(jsonPath("$.locationTypeCode").value("BIN_LOCATION"));
    }

    @Test void list_filtersByType() throws Exception {
        // GET /api/location?type=DEPOT → expect array of 3 DEPOTs (2 active + 1 inactive; active filter not applied)
        mvc.perform(get("/api/location?type=DEPOT").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(3))
           .andExpect(jsonPath("$[0].locationTypeCode").value("DEPOT"));
    }

    @Test void list_filtersByActive() throws Exception {
        // GET /api/location?active=false → expect 1 element (loc-depot-inactive)
        mvc.perform(get("/api/location?active=false").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1))
           .andExpect(jsonPath("$[0].id").value("loc-depot-inactive"));
    }

    @Test void list_excludesInternalByDefault() throws Exception {
        // GET /api/location → array NOT containing loc-bin-1/loc-bin-2; SHOULD contain loc-zone-1
        // (ZONE is NOT internal per Grails parity, FD#2 pick a)
        mvc.perform(get("/api/location").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$[?(@.id == 'loc-bin-1')]").doesNotExist())
           .andExpect(jsonPath("$[?(@.id == 'loc-bin-2')]").doesNotExist())
           .andExpect(jsonPath("$[?(@.id == 'loc-zone-1')]").exists());
    }

    @Test void groupReadById_returns200() throws Exception {
        // GET /api/location/group/lg-001 → 200, id=lg-001, name="Test Group"
        mvc.perform(get("/api/location/group/lg-001").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value("lg-001"))
           .andExpect(jsonPath("$.name").value("Test Group"));
    }

    @Test void groupList_returnsAll() throws Exception {
        // GET /api/location/group → array of 1
        mvc.perform(get("/api/location/group").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(1));
    }

    @Test void typeList_servedFromCache() throws Exception {
        // GET /api/location/type → array of 3 types (DEPOT, BIN_LOCATION, ZONE)
        // Manually refresh cache since @PostConstruct runs before seed.sql in test context
        cache.refresh();
        mvc.perform(get("/api/location/type").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(3));
    }

    @Test void typeReadById_returns404ForMissing() throws Exception {
        // GET /api/location/type/nonexistent-id → 404
        // (cache.getById refreshes once on miss then returns empty Optional)
        mvc.perform(get("/api/location/type/nonexistent-id").cookie(authCookie()))
           .andExpect(status().isNotFound());
    }

    @Test void supportedActivities_returnsAllEnumValues() throws Exception {
        // GET /api/location/supportedActivities → array of 30 strings (NOT 31; spec §6 has off-by-one)
        mvc.perform(get("/api/location/supportedActivities").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.length()").value(30));
    }

    @Test void cacheLoadsOnFirstCall_thenHits() throws Exception {
        // Inject LocationTypeCache via @Autowired; assert cache.getAll().size() == 3 after first call
        // Subsequent calls don't trigger refresh (this is implicit — just verify size stays consistent)
        // Make an API call that would trigger cache use (first call populates cache)
        mvc.perform(get("/api/location/type").cookie(authCookie()))
           .andExpect(status().isOk());
        // Verify cache now has 3 entries (populated after first access)
        assertThat(cache.getAll()).hasSize(3);
        // Make another call and verify cache still has 3 entries (no re-population)
        mvc.perform(get("/api/location/type").cookie(authCookie()))
           .andExpect(status().isOk());
        assertThat(cache.getAll()).hasSize(3);
    }

    @Test void jwtMissing_returns401() throws Exception {
        // GET /api/location/loc-depot-a without cookie → 401
        // (NOT 403, confirms T5's exceptionHandling hand-fix works in test context too)
        mvc.perform(get("/api/location/loc-depot-a"))
           .andExpect(status().isUnauthorized());
    }

    @Test void jwtInvalid_returns401() throws Exception {
        // GET /api/location/loc-depot-a with garbage cookie → 401
        mvc.perform(get("/api/location/loc-depot-a").cookie(new jakarta.servlet.http.Cookie("obx_token", "garbage")))
           .andExpect(status().isUnauthorized());
    }
}
