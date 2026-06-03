package org.openboxes.organization;

import org.junit.jupiter.api.Test;
import org.openboxes.auth.common.test.JwtTestFixtures;
import org.openboxes.organization.service.PartyTypeCache;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.openboxes.auth.common.test.JwtTestFixtures.authCookie;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class OrganizationServiceIntegrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mariadb::getJdbcUrl);
        r.add("spring.datasource.username", mariadb::getUsername);
        r.add("spring.datasource.password", mariadb::getPassword);
        r.add("openboxes.jwt.secret", () -> JwtTestFixtures.TEST_SECRET);
        r.add("openboxes.identifier.organization.minSize", () -> "2");
        r.add("openboxes.identifier.organization.maxSize", () -> "3");
        r.add("spring.liquibase.enabled", () -> "false");  // Disable Liquibase to avoid circular dependency with entityManagerFactory
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");  // TestContainers gives empty DB; let JPA create schema
        // create runs BEFORE data.sql by default; defer keeps the seed load until after Hibernate has emitted the schema.
        r.add("spring.jpa.defer-datasource-initialization", () -> "true");
        r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
        r.add("spring.sql.init.mode", () -> "always");  // override "embedded" default; runs against TestContainers MariaDB
    }

    @Autowired MockMvc mvc;
    @Autowired PartyTypeCache cache;

    @Test void readById_returns200() throws Exception {
        mvc.perform(get("/api/organization/org-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("org-acme"))
            .andExpect(jsonPath("$.data.code").value("ACM"))
            .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test void readById_returns404ForMissing() throws Exception {
        mvc.perform(get("/api/organization/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void list_returnsAll() throws Exception {
        mvc.perform(get("/api/organization").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test void list_filtersByQ() throws Exception {
        mvc.perform(get("/api/organization?q=Acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].name").value("Acme Inc"));
    }

    @Test void list_filtersBySingleRoleType() throws Exception {
        mvc.perform(get("/api/organization?roleType=ROLE_SUPPLIER").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("org-acme"));
    }

    @Test void list_filtersByMultiRoleType() throws Exception {
        mvc.perform(get("/api/organization")
            .param("roleType", "ROLE_SUPPLIER", "ROLE_BUYER")
            .cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test void list_filtersByActive() throws Exception {
        mvc.perform(get("/api/organization?active=false").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("org-inactive"));
    }

    @Test void list_paginates() throws Exception {
        mvc.perform(get("/api/organization?max=1&offset=1").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test void readById_returns401WithoutJwt() throws Exception {
        mvc.perform(get("/api/organization/org-acme"))
            .andExpect(status().isUnauthorized());
    }

    @Test void create_returnsCreatedWithGeneratedCode() throws Exception {
        mvc.perform(post("/api/organization")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"NewOrg\"}")
            .cookie(authCookie()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").exists());
    }

    @Test void create_returnsCreatedWithProvidedCode() throws Exception {
        mvc.perform(post("/api/organization")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"name\":\"X\",\"code\":\"XYZ\"}")
            .cookie(authCookie()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.id").exists());
    }

    @Test void create_returns400OnMissingName() throws Exception {
        mvc.perform(post("/api/organization")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}")
            .cookie(authCookie()))
            .andExpect(status().isBadRequest());
    }

    @Test void readPartyById_returnsBaseShapeForOrganization() throws Exception {
        mvc.perform(get("/api/organization/party/org-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("org-acme"))
            .andExpect(jsonPath("$.data.code").doesNotExist());
    }

    @Test void readPartyById_returnsBaseShapeForBareParty() throws Exception {
        mvc.perform(get("/api/organization/party/party-bare").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("party-bare"));
    }

    @Test void partyTypeCache_returnsCachedListOnSecondCall() throws Exception {
        // First call
        mvc.perform(get("/api/organization/partyType").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        // Second call (from cache)
        mvc.perform(get("/api/organization/partyType").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test void partyTypeCache_refreshOnInitiallyEmptyCacheAndCacheResultsOnceSeeded() throws Exception {
        // RC-6 fix verification: manually refresh cache to ensure it's populated from seed.sql
        // since @PostConstruct runs before seed.sql in test context
        cache.refresh();
        mvc.perform(get("/api/organization/partyType").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        // Verify cache has 2 entries after refresh
        assertThat(cache.getAll()).hasSize(2);
    }

    @Test void partyRole_findByPartyAndRoleType() throws Exception {
        mvc.perform(get("/api/organization/partyRole?partyId=org-acme&roleType=ROLE_BUYER")
            .cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].roleType").value("ROLE_BUYER"));
    }

    @Test void partyRole_arbitraryRoleTypeStringWorks() throws Exception {
        // GET /api/organization/party/party-bare → 200
        // The proof here is that the response succeeds (no enum-not-found exception)
        // even though the bare party has 'ROLE_RANDOM_NEW_VALUE' role
        mvc.perform(get("/api/organization/party/party-bare").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("party-bare"))
            .andExpect(jsonPath("$.data.roleTypes[0]").value("ROLE_RANDOM_NEW_VALUE"));
    }
}
