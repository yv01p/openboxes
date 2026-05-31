package org.openboxes.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openboxes.catalog.cache.AttributeCache;
import org.openboxes.catalog.cache.ProductTypeCache;
import org.openboxes.catalog.cache.UnitOfMeasureCache;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class CatalogServiceIntegrationTest {

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
    }

    @Autowired MockMvc mvc;
    @Autowired UnitOfMeasureCache uomCache;
    @Autowired ProductTypeCache productTypeCache;
    @Autowired AttributeCache attributeCache;

    private static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    // Test-isolation only: caches are @Component singletons shared across tests in this Spring
    // context; clearing them per-test gives each test deterministic state regardless of execution
    // order. Production is NOT affected: OSIV (spring.jpa.open-in-view=true, Spring Boot 3.x
    // default) + @Transactional(readOnly=true) on ProductTypeService/AttributeService re-attaches
    // the cached entity to the current request's EntityManager, so lazy @ElementCollection fields
    // load fine across requests. Verified empirically: 5 sequential calls to /api/productType
    // via real nginx → Tomcat after a catalog-service restart all return populated supportedActivities.
    // (Retracts the misleading "production caching limitation" framing in commit 36c25a4d0's body.)
    @BeforeEach
    void clearCaches() {
        productTypeCache.clear();
        attributeCache.clear();
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
    // Auth (3): valid JWT 200, no cookie 401, invalid JWT 401
    // ---------------------------------------------------------------

    @Test void auth_returns200WithValidJwt() throws Exception {
        mvc.perform(get("/api/product").cookie(authCookie()))
            .andExpect(status().isOk());
    }

    @Test void auth_returns401WithoutCookie() throws Exception {
        mvc.perform(get("/api/product"))
            .andExpect(status().isUnauthorized());
    }

    @Test void auth_returns401WithInvalidJwt() throws Exception {
        var bad = new jakarta.servlet.http.Cookie("obx_token", "not-a-valid-jwt");
        mvc.perform(get("/api/product").cookie(bad))
            .andExpect(status().isUnauthorized());
    }

    // ---------------------------------------------------------------
    // Product reads (4)
    // ---------------------------------------------------------------

    @Test void productList_returns3() throws Exception {
        mvc.perform(get("/api/product").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test void productGet_flatDto() throws Exception {
        mvc.perform(get("/api/product/p-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("p-bandage"))
            .andExpect(jsonPath("$.data.name").value("Bandage"))
            .andExpect(jsonPath("$.data.productCode").value("BND001"))
            .andExpect(jsonPath("$.data.productTypeId").value("pt-good"))
            .andExpect(jsonPath("$.data.categoryId").value("cat-medical"))
            .andExpect(jsonPath("$.data.unitOfMeasureId").value("uom-pc"))
            .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test void productGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/product/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productGet_dtoFlatness_noNestedEntities() throws Exception {
        // FD#3: response body has flat FK strings, no nested {productType:{...}}, {category:{...}}, etc.
        mvc.perform(get("/api/product/p-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productType").doesNotExist())
            .andExpect(jsonPath("$.data.category").doesNotExist())
            .andExpect(jsonPath("$.data.unitOfMeasure").doesNotExist())
            .andExpect(jsonPath("$.data.tags").doesNotExist())
            .andExpect(jsonPath("$.data.productGroups").doesNotExist())
            .andExpect(jsonPath("$.data.synonyms").doesNotExist());
    }

    // ---------------------------------------------------------------
    // Category tree (3)
    // ---------------------------------------------------------------

    @Test void categoryList_returns3() throws Exception {
        mvc.perform(get("/api/category").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
    }

    @Test void categoryGet_returnsParentCategoryId() throws Exception {
        mvc.perform(get("/api/category/cat-medical").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("cat-medical"))
            .andExpect(jsonPath("$.data.parentCategoryId").value("cat-root"))
            .andExpect(jsonPath("$.data.isRoot").value(false));
    }

    @Test void categoryGet_rootHasIsRootTrue() throws Exception {
        mvc.perform(get("/api/category/cat-root").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("cat-root"))
            .andExpect(jsonPath("$.data.isRoot").value(true))
            .andExpect(jsonPath("$.data.parentCategoryId").doesNotExist());
    }

    // ---------------------------------------------------------------
    // UoM <-> UoMClass bidirectional (3)
    // ---------------------------------------------------------------

    @Test void uomList_returns4() throws Exception {
        mvc.perform(get("/api/unitOfMeasure").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4));
    }

    @Test void uomClassGet_returnsBaseUomId() throws Exception {
        mvc.perform(get("/api/unitOfMeasureClass/uomc-mass").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("uomc-mass"))
            .andExpect(jsonPath("$.data.baseUomId").value("uom-kg"));
    }

    @Test void uomGet_returnsUomClassId() throws Exception {
        mvc.perform(get("/api/unitOfMeasure/uom-kg").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("uom-kg"))
            .andExpect(jsonPath("$.data.uomClassId").value("uomc-mass"));
    }

    // ---------------------------------------------------------------
    // Tag <-> Product M:N reads (2)
    // ---------------------------------------------------------------

    @Test void productGet_returnsTagIdsSet() throws Exception {
        mvc.perform(get("/api/product/p-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.tagIds.length()").value(2))
            .andExpect(jsonPath("$.data.tagIds[?(@ == 'tag-essential')]").exists())
            .andExpect(jsonPath("$.data.tagIds[?(@ == 'tag-trauma')]").exists());
    }

    @Test void tagGet_returnsProductIdsSet() throws Exception {
        mvc.perform(get("/api/tag/tag-essential").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("tag-essential"))
            .andExpect(jsonPath("$.data.productIds.length()").value(2))
            .andExpect(jsonPath("$.data.productIds[?(@ == 'p-bandage')]").exists())
            .andExpect(jsonPath("$.data.productIds[?(@ == 'p-syringe')]").exists());
    }

    // ---------------------------------------------------------------
    // ProductGroup <-> Product M:N (2)
    // ---------------------------------------------------------------

    @Test void productGroupGet_returnsProductIdsSet() throws Exception {
        mvc.perform(get("/api/productGroup/pg-medical").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pg-medical"))
            .andExpect(jsonPath("$.data.productIds.length()").value(2))
            .andExpect(jsonPath("$.data.productIds[?(@ == 'p-bandage')]").exists())
            .andExpect(jsonPath("$.data.productIds[?(@ == 'p-syringe')]").exists());
    }

    @Test void productGet_returnsProductGroupIdsSet() throws Exception {
        mvc.perform(get("/api/product/p-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productGroupIds.length()").value(1))
            .andExpect(jsonPath("$.data.productGroupIds[0]").value("pg-medical"));
    }

    // ---------------------------------------------------------------
    // Reference data caches per FD#7 (3) — refresh-on-miss then serve from cache
    // ---------------------------------------------------------------

    @Test void uomCache_refreshAndServeFromCache() throws Exception {
        // First call triggers refresh() via getAll()'s isEmpty check (RC-6 fix). UoM DTOs
        // access only scalar fields + uomClass.getId() (proxy id reads safely off-session),
        // so the cache→second-request handoff works for UoM.
        mvc.perform(get("/api/unitOfMeasure").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4));
        // Second call (from cache)
        mvc.perform(get("/api/unitOfMeasure").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4));
        assertThat(uomCache.getAll()).hasSize(4);
    }

    @Test void productTypeCache_refreshOnEmptyServesFromSeed() throws Exception {
        // Cache cleared in @BeforeEach for test isolation; first call populates + serves.
        mvc.perform(get("/api/productType").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        assertThat(productTypeCache.getAll()).hasSize(2);
    }

    @Test void attributeCache_refreshOnEmptyServesFromSeed() throws Exception {
        // Cache cleared in @BeforeEach for test isolation; first call populates + serves.
        mvc.perform(get("/api/attribute").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        assertThat(attributeCache.getAll()).hasSize(2);
    }

    // ---------------------------------------------------------------
    // DTO flatness per FD#3, one assertion per entity (9 tests)
    // Each confirms no nested entity inflation — only flat FK strings / id strings exposed.
    // ---------------------------------------------------------------

    @Test void productDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/product/p-syringe").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productType").doesNotExist())
            .andExpect(jsonPath("$.data.category").doesNotExist())
            .andExpect(jsonPath("$.data.unitOfMeasure").doesNotExist())
            .andExpect(jsonPath("$.data.tags").doesNotExist())
            .andExpect(jsonPath("$.data.synonyms").doesNotExist())
            .andExpect(jsonPath("$.data.productGroups").doesNotExist())
            .andExpect(jsonPath("$.data.productFamily").doesNotExist())
            .andExpect(jsonPath("$.data.tagIds").isArray())
            .andExpect(jsonPath("$.data.productGroupIds").isArray())
            .andExpect(jsonPath("$.data.synonymIds").isArray());
    }

    @Test void categoryDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/category/cat-medical").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.parentCategory").doesNotExist())
            .andExpect(jsonPath("$.data.categories").doesNotExist())
            .andExpect(jsonPath("$.data.parentCategoryId").isString());
    }

    @Test void unitOfMeasureDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/unitOfMeasure/uom-kg").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.uomClass").doesNotExist())
            .andExpect(jsonPath("$.data.uomClassId").isString());
    }

    @Test void unitOfMeasureClassDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/unitOfMeasureClass/uomc-mass").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.baseUom").doesNotExist())
            .andExpect(jsonPath("$.data.uoms").doesNotExist())
            .andExpect(jsonPath("$.data.baseUomId").isString());
    }

    @Test void tagDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/tag/tag-essential").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.products").doesNotExist())
            .andExpect(jsonPath("$.data.productIds").isArray());
    }

    @Test void productGroupDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/productGroup/pg-medical").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.products").doesNotExist())
            .andExpect(jsonPath("$.data.siblings").doesNotExist())
            .andExpect(jsonPath("$.data.category").doesNotExist())
            .andExpect(jsonPath("$.data.productIds").isArray())
            .andExpect(jsonPath("$.data.siblingIds").isArray());
    }

    @Test void productTypeDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/productType/pt-good").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pt-good"))
            .andExpect(jsonPath("$.data.supportedActivities").isArray())
            .andExpect(jsonPath("$.data.requiredFields").isArray())
            .andExpect(jsonPath("$.data.displayedFields").isArray());
    }

    @Test void attributeDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/attribute/attr-color").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("attr-color"))
            .andExpect(jsonPath("$.data.unitOfMeasureClass").doesNotExist())
            .andExpect(jsonPath("$.data.options").isArray())
            .andExpect(jsonPath("$.data.entityTypeCodes").isArray());
    }

    @Test void synonymDto_isFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/synonym/syn-bandage-fr").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("syn-bandage-fr"))
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.name").value("pansement"));
    }
}
