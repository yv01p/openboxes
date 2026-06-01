package org.openboxes.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openboxes.catalog.cache.AttributeCache;
import org.openboxes.catalog.cache.ProductTypeCache;
import org.openboxes.catalog.cache.UnitOfMeasureCache;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    // ---------------------------------------------------------------
    // ProductSupplier CRUD (T2) — first write path + first JPA audit infra (FD#8 Option-A)
    // ---------------------------------------------------------------

    @Test void productSupplierList_returnsSeededRow() throws Exception {
        mvc.perform(get("/api/productSuppliers").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data[?(@.id == 'ps-bandage-acme')]").exists());
    }

    @Test void productSupplierGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productSuppliers/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productSupplierGet_dtoIsFlat_noNestedEntities() throws Exception {
        mvc.perform(get("/api/productSuppliers/ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("ps-bandage-acme"))
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.supplier").doesNotExist())
            .andExpect(jsonPath("$.data.manufacturer").doesNotExist())
            .andExpect(jsonPath("$.data.createdBy").doesNotExist())
            .andExpect(jsonPath("$.data.updatedBy").doesNotExist())
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.supplierId").value("org-acme-placeholder"));
    }

    @Test void productSupplierPost_createsRow_withGeneratedId() throws Exception {
        String json = "{\"name\":\"Bandage from Globex\",\"productId\":\"p-bandage\"," +
            "\"supplierId\":\"org-globex-placeholder\",\"code\":\"PS-BND-GLX\",\"active\":true}";
        mvc.perform(post("/api/productSuppliers")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.name").value("Bandage from Globex"))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.supplierId").value("org-globex-placeholder"))
            // tiered_pricing is NOT NULL: omitted in the request, defaults to false.
            .andExpect(jsonPath("$.data.tieredPricing").value(false));
    }

    @Test void productSupplierPost_populatesCreatedByIdFromJwt() throws Exception {
        // FD#8 Option-A end-to-end proof: JwtAuditorAware maps the JWT subject ("test-user") into
        // created_by_id via @CreatedBy. POST, then GET the created row and assert the audit field.
        String json = "{\"name\":\"Bandage from Initech\",\"productId\":\"p-bandage\"," +
            "\"supplierId\":\"org-initech-placeholder\"}";
        var result = mvc.perform(post("/api/productSuppliers")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createdById").value("test-user"))
            .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(get("/api/productSuppliers/" + id).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.createdById").value("test-user"))
            .andExpect(jsonPath("$.data.updatedById").value("test-user"));
    }

    @Test void productSupplierPut_updatesRow() throws Exception {
        String json = "{\"name\":\"Bandage Updated\",\"productId\":\"p-bandage\"," +
            "\"supplierId\":\"org-acme-placeholder\",\"active\":false}";
        mvc.perform(put("/api/productSuppliers/ps-bandage-acme")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("ps-bandage-acme"))
            .andExpect(jsonPath("$.data.name").value("Bandage Updated"))
            .andExpect(jsonPath("$.data.active").value(false));
        mvc.perform(get("/api/productSuppliers/ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("Bandage Updated"));
    }

    @Test void productSupplierPut_404OnMissing() throws Exception {
        String json = "{\"name\":\"Nope\",\"productId\":\"p-bandage\"}";
        mvc.perform(put("/api/productSuppliers/nonexistent")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productSupplierDelete_thenGet404() throws Exception {
        // Create a throwaway row to delete (keeps the seeded row available for other tests).
        String json = "{\"name\":\"Bandage Disposable\",\"productId\":\"p-bandage\"}";
        var result = mvc.perform(post("/api/productSuppliers")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data.id");
        mvc.perform(delete("/api/productSuppliers/" + id).cookie(authCookie()))
            .andExpect(status().isNoContent());
        mvc.perform(get("/api/productSuppliers/" + id).cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productSupplierDelete_404OnMissing() throws Exception {
        mvc.perform(delete("/api/productSuppliers/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------
    // C2: @RestControllerAdvice — honest 4xx (design §6)
    // ---------------------------------------------------------------

    @Test void productSupplierPost_missingNotNullProductId_returns409() throws Exception {
        // Proves @RestControllerAdvice handles DataIntegrityViolationException IN-DISPATCHER,
        // so the request never forwards to /error (which would re-enter security chain → spurious 401).
        String json = "{\"name\":\"No Product\",\"supplierId\":\"org-acme-placeholder\"}";
        mvc.perform(post("/api/productSuppliers")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());  // 409, not 401/500
    }

    @Test void productSupplierPost_malformedJson_returns400() throws Exception {
        // Genuinely unparseable JSON → HttpMessageNotReadableException → @RestControllerAdvice → 400.
        mvc.perform(post("/api/productSuppliers")
                .content("{not valid json").contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isBadRequest());  // 400
    }

    // ---------------------------------------------------------------
    // ProductSupplierPreference batch/CRUD (T3) — backend + integration tests only, reshaped per write-contract design §4
    // ---------------------------------------------------------------

    @Test void productSupplierPreferenceBatchPost_createsRows_populatesCreatedByIdFromJwt() throws Exception {
        // Batch POST creates new rows; JWT subject ("test-user") populates createdById via JwtAuditorAware.
        String json = "[" +
            "{\"productSupplierId\":\"ps-bandage-acme\",\"destinationPartyId\":\"org-nyc-placeholder\"," +
            "\"preferenceTypeId\":\"pref-type-preferred\",\"comments\":\"NYC preference\"}," +
            "{\"productSupplierId\":\"ps-bandage-acme\",\"destinationPartyId\":\"org-sf-placeholder\"," +
            "\"preferenceTypeId\":\"pref-type-preferred\",\"comments\":\"SF preference\"}" +
            "]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[0].id").isString())
            .andExpect(jsonPath("$.data[0].createdById").value("test-user"))
            .andExpect(jsonPath("$.data[1].createdById").value("test-user"));
    }

    @Test void productSupplierPreferenceBatchPost_upsertUpdatesExistingPairWithoutDuplicating() throws Exception {
        // Create a preference, then batch-upsert with the same id + modified comments → update, not duplicate.
        String createJson = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":\"org-la-placeholder\",\"preferenceTypeId\":\"pref-type-preferred\"," +
            "\"comments\":\"LA original\"}]";
        var result = mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(createJson).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data[0].id");

        // Count existing preferences for ps-bandage-acme BEFORE upsert.
        var beforeResult = mvc.perform(get("/api/productSupplierPreferences?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andReturn();
        int countBefore = com.jayway.jsonpath.JsonPath.read(beforeResult.getResponse().getContentAsString(), "$.data.length()");

        // Upsert: same id, different comments.
        String upsertJson = "[{\"id\":\"" + id + "\",\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":\"org-la-placeholder\",\"preferenceTypeId\":\"pref-type-preferred\"," +
            "\"comments\":\"LA updated\"}]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(upsertJson).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value(id))
            .andExpect(jsonPath("$.data[0].comments").value("LA updated"));

        // Verify count didn't increase (upsert updated, didn't duplicate).
        mvc.perform(get("/api/productSupplierPreferences?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(countBefore));
    }

    @Test void productSupplierPreferenceBatchPost_duplicatePairReturns409() throws Exception {
        // Create one preference, then try to create a second with same (productSupplier, destinationParty) pair → 409.
        String json1 = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":\"org-duplicate-test\",\"preferenceTypeId\":\"pref-type-preferred\"}]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json1).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk());

        // Attempt duplicate pair (different id, same pair).
        String json2 = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":\"org-duplicate-test\",\"preferenceTypeId\":\"pref-type-other\"}]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json2).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());  // 409 via DuplicatePreferenceException → GlobalExceptionHandler
    }

    @Test void productSupplierPreferenceGet_filterByProductSupplier_returnsSupplierPreferences() throws Exception {
        // GET ?productSupplier=<id> returns only that supplier's preferences (cutover read-GET).
        mvc.perform(get("/api/productSupplierPreferences?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == 'psp-bandage-acme-boston')]").exists())
            .andExpect(jsonPath("$.data[?(@.productSupplierId == 'ps-bandage-acme')]").exists());
    }

    @Test void productSupplierPreferenceBatchPost_nullDestinationPartyDuplicateRejected() throws Exception {
        // A9: pair-uniqueness includes the null-destinationParty case. Create one with null, try to create another → 409.
        String json1 = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":null,\"preferenceTypeId\":\"pref-type-preferred\"}]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json1).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk());

        // Attempt duplicate null-destinationParty pair.
        String json2 = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":null,\"preferenceTypeId\":\"pref-type-other\"}]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json2).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());
    }

    @Test void productSupplierPreferenceBatchPost_twoItemsSamePairInBatchRejected() throws Exception {
        // In-batch dedup: two items sharing a pair within one batch → 409 (the per-item DB check can't see not-yet-flushed siblings).
        String json = "[" +
            "{\"productSupplierId\":\"ps-bandage-acme\",\"destinationPartyId\":\"org-inbatch-dup\"," +
            "\"preferenceTypeId\":\"pref-type-preferred\"}," +
            "{\"productSupplierId\":\"ps-bandage-acme\",\"destinationPartyId\":\"org-inbatch-dup\"," +
            "\"preferenceTypeId\":\"pref-type-other\"}" +
            "]";
        mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());
    }

    @Test void productSupplierPreferenceDelete_then404() throws Exception {
        // Create a throwaway preference, DELETE → 204, then GET → excludes it.
        String json = "[{\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"destinationPartyId\":\"org-delete-test\",\"preferenceTypeId\":\"pref-type-preferred\"}]";
        var result = mvc.perform(post("/api/productSupplierPreferences/batch")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.data[0].id");

        mvc.perform(delete("/api/productSupplierPreferences/" + id).cookie(authCookie()))
            .andExpect(status().isNoContent());

        // Verify excluded from list.
        mvc.perform(get("/api/productSupplierPreferences?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == '" + id + "')]").doesNotExist());
    }

    @Test void productSupplierPreferenceDelete_404OnMissing() throws Exception {
        mvc.perform(delete("/api/productSupplierPreferences/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productSupplierPreferenceDto_isFlat_noNestedEntities() throws Exception {
        // FD#2: DTO is flat (productSupplierId, destinationPartyId, preferenceTypeId as raw String ids, no nested entities).
        // Pin assertions to the seeded row (psp-bandage-acme-boston, which has a non-null
        // destinationPartyId) rather than the positional [0]: findAll() ordering is non-deterministic
        // and other tests in the suite insert null-destinationParty rows, so [0] can be a
        // null-destination row (which fails the destinationPartyId isString check). Extracting the
        // known seeded row by id makes the test order-independent.
        var result = mvc.perform(get("/api/productSupplierPreferences?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            // No nested entity inflation: flat FK strings only, asserted positionally (true for every row).
            .andExpect(jsonPath("$.data[0].productSupplier").doesNotExist())
            .andExpect(jsonPath("$.data[0].destinationParty").doesNotExist())
            .andExpect(jsonPath("$.data[0].preferenceType").doesNotExist())
            .andReturn();
        String body = result.getResponse().getContentAsString();
        java.util.List<Object> boston = com.jayway.jsonpath.JsonPath.read(
            body, "$.data[?(@.id == 'psp-bandage-acme-boston')]");
        assertThat(boston).hasSize(1);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> row = (java.util.Map<String, Object>) boston.get(0);
        assertThat(row.get("productSupplierId")).isEqualTo("ps-bandage-acme");
        assertThat(row.get("destinationPartyId")).isEqualTo("org-boston-placeholder");
        assertThat(row.get("preferenceTypeId")).isEqualTo("pref-type-default");
    }

    // ---------------------------------------------------------------
    // ProductPackage POST/GET (T4) — backend + integration tests only.
    // Verb scope: POST (create — React ProductPackageApi.js save) + GET (cutover load read). No PUT/DELETE.
    // ---------------------------------------------------------------

    @Test void productPackagePost_createsRow_withGeneratedId_andFlatIds() throws Exception {
        // Real flat payload (per C3 lesson): product + productSupplier + uom + quantity.
        // JWT subject ("test-user") populates createdById via JwtAuditorAware (FD#8 Option-A audit proof).
        String json = "{\"productId\":\"p-bandage\",\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"uomId\":\"uom-pc\",\"quantity\":24,\"name\":\"Bandage Case\",\"gtin\":\"GTIN-BND-CASE\"}";
        mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isString())
            .andExpect(jsonPath("$.data.name").value("Bandage Case"))
            .andExpect(jsonPath("$.data.quantity").value(24))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.productSupplierId").value("ps-bandage-acme"))
            .andExpect(jsonPath("$.data.uomId").value("uom-pc"))
            .andExpect(jsonPath("$.data.createdById").value("test-user"));
    }

    @Test void productPackageGet_returnsSeededRow() throws Exception {
        mvc.perform(get("/api/productPackages/pp-bandage-box").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pp-bandage-box"))
            .andExpect(jsonPath("$.data.name").value("Bandage Box"))
            .andExpect(jsonPath("$.data.quantity").value(12))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.uomId").value("uom-pc"))
            .andExpect(jsonPath("$.data.productSupplierId").value("ps-bandage-acme"));
    }

    @Test void productPackageGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productPackages/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productPackageGet_filterByProductSupplier_returnsSupplierPackages() throws Exception {
        // GET ?productSupplier=<id> returns only that supplier's packages (cutover read-GET).
        mvc.perform(get("/api/productPackages?productSupplier=ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[?(@.id == 'pp-bandage-box')]").exists())
            .andExpect(jsonPath("$.data[?(@.productSupplierId == 'ps-bandage-acme')]").exists());
    }

    @Test void productPackagePost_duplicateTupleReturns409() throws Exception {
        // Friendly pre-check (ports the Grails findWhere validator): same
        // (product, productSupplier, uom, quantity) tuple twice → second is 409 via DuplicatePackageException.
        String json = "{\"productId\":\"p-bandage\",\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"uomId\":\"uom-pc\",\"quantity\":48,\"name\":\"Dup Pack\"}";
        mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk());
        mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());  // 409 via DuplicatePackageException → GlobalExceptionHandler
    }

    @Test void productPackagePost_missingNotNullQuantity_returns409() throws Exception {
        // DB backstop via C2: quantity is the genuinely NOT-NULL column (product is NULLABLE — see
        // ProductPackage header deviation note). Omitting quantity → DataIntegrityViolationException → 409.
        String json = "{\"productId\":\"p-bandage\",\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"uomId\":\"uom-pc\",\"name\":\"No Quantity\"}";
        mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isConflict());  // 409, not 401/500
    }

    @Test void productPackageDto_isFlat_noNestedEntities() throws Exception {
        // FD#2: DTO is flat (productId, uomId, productSupplierId as raw String ids, no nested entities).
        mvc.perform(get("/api/productPackages/pp-bandage-box").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.uom").doesNotExist())
            .andExpect(jsonPath("$.data.productSupplier").doesNotExist())
            .andExpect(jsonPath("$.data.productId").isString())
            .andExpect(jsonPath("$.data.uomId").isString())
            .andExpect(jsonPath("$.data.productSupplierId").isString());
    }

    @Test void productSupplierPut_setsDefaultProductPackageId_exposedOnGet() throws Exception {
        // T4 forward-decl split: ProductSupplier.defaultProductPackage is now mapped. PUT the supplier
        // with defaultProductPackageId set to the seeded package, then GET → the flat id is present.
        String json = "{\"name\":\"Bandage from Acme\",\"productId\":\"p-bandage\"," +
            "\"defaultProductPackageId\":\"pp-bandage-box\"}";
        mvc.perform(put("/api/productSuppliers/ps-bandage-acme")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.defaultProductPackageId").value("pp-bandage-box"));
        mvc.perform(get("/api/productSuppliers/ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.defaultProductPackageId").value("pp-bandage-box"));
    }
}
