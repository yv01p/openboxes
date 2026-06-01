package org.openboxes.catalog;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.openboxes.catalog.cache.AttributeCache;
import org.openboxes.catalog.cache.ProductCatalogCache;
import org.openboxes.catalog.cache.ProductCatalogItemCache;
import org.openboxes.catalog.cache.ProductTypeCache;
import org.openboxes.catalog.cache.UnitOfMeasureCache;
import org.openboxes.catalog.cache.UnitOfMeasureConversionCache;
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
        // T8 N+1 proof: enable Hibernate statistics so productAttributeList_noN1_boundedQueryCount can
        // assert the JDBC statement count for findAll(). Benign for all other tests.
        r.add("spring.jpa.properties.hibernate.generate_statistics", () -> "true");
    }

    @Autowired MockMvc mvc;
    @Autowired UnitOfMeasureCache uomCache;
    @Autowired ProductTypeCache productTypeCache;
    @Autowired AttributeCache attributeCache;
    @Autowired ProductCatalogCache productCatalogCache;
    @Autowired ProductCatalogItemCache productCatalogItemCache;
    // T11 UnitOfMeasureConversion is CACHE-backed (heuristic cache per T1 audit §5/§8) — cache cleared
    // per-test for isolation; the service is injected for the ported conversionRateLookup finder tests.
    @Autowired UnitOfMeasureConversionCache unitOfMeasureConversionCache;
    @Autowired org.openboxes.catalog.service.UnitOfMeasureConversionService unitOfMeasureConversionService;
    // T8 ProductAttribute is repo-backed (no cache) — service + EMF injected for the N+1 query-count proof.
    @Autowired org.openboxes.catalog.service.ProductAttributeService productAttributeService;
    @Autowired jakarta.persistence.EntityManagerFactory emf;
    // T9 ProductAssociation is repo-backed (no cache) — service injected for the N+1 query-count proof
    // (reuses the EMF field above). generate_statistics is already enabled in @DynamicPropertySource.
    @Autowired org.openboxes.catalog.service.ProductAssociationService productAssociationService;
    // T10 ProductComponent is repo-backed (no cache) — service injected for the N+1 query-count proof
    // (reuses the EMF field above). generate_statistics is already enabled in @DynamicPropertySource.
    @Autowired org.openboxes.catalog.service.ProductComponentService productComponentService;

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
        productCatalogCache.clear();
        productCatalogItemCache.clear();
        unitOfMeasureConversionCache.clear();
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
    // ProductCatalog (T6) — GET-only cache-with-refresh read entity (zero React callers).
    // NOT @Transactional: commits to the shared DB, but product_catalog has no sibling writers
    // (GET-only), so the count-of-2 assertions are deterministic.
    // ---------------------------------------------------------------

    @Test void productCatalogList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/productCatalogs").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2))
            .andExpect(jsonPath("$.data[?(@.id == 'pc-essential')]").exists());
    }

    @Test void productCatalogGet_flatDto() throws Exception {
        mvc.perform(get("/api/productCatalogs/pc-essential").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pc-essential"))
            .andExpect(jsonPath("$.data.code").value("ESSENTIAL"))
            .andExpect(jsonPath("$.data.name").value("Essential Medicines"))
            .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test void productCatalogGet_falseActiveAndNullFieldsRoundTrip() throws Exception {
        // pc-trauma seeds active=0 + NULL description/color: proves the bit(1)->Boolean read returns
        // false (not the constructor default true), and that nullable columns surface as JSON null.
        mvc.perform(get("/api/productCatalogs/pc-trauma").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false))
            .andExpect(jsonPath("$.data.code").value("TRAUMA"))
            .andExpect(jsonPath("$.data.description").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.color").value(org.hamcrest.Matchers.nullValue()));
    }

    @Test void productCatalogGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productCatalogs/pc-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productCatalogGet_dtoFlatness_noNestedCollection() throws Exception {
        // T7 forward-decl: productCatalogItems inverse collection is not mapped, so it must not appear.
        mvc.perform(get("/api/productCatalogs/pc-essential").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productCatalogItems").doesNotExist());
    }

    @Test void productCatalogCache_refreshOnEmptyServesFromSeed() throws Exception {
        // Cache cleared in @BeforeEach for test isolation; first call populates + serves.
        mvc.perform(get("/api/productCatalogs").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(2));
        assertThat(productCatalogCache.getAll()).hasSize(2);
    }

    // ---------------------------------------------------------------
    // ProductCatalogItem (T7) — GET-only cache-with-refresh read entity (zero React callers).
    // NOT @Transactional: commits to the shared DB, but product_catalog_item has no sibling writers
    // (GET-only), so the count-of-3 assertions are deterministic.
    // ---------------------------------------------------------------

    @Test void productCatalogItemList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/productCatalogItems").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.id == 'pci-ess-bandage')]").exists());
    }

    @Test void productCatalogItemGet_flatDto() throws Exception {
        mvc.perform(get("/api/productCatalogItems/pci-ess-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pci-ess-bandage"))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.productCatalogId").value("pc-essential"))
            .andExpect(jsonPath("$.data.active").value(true));
    }

    @Test void productCatalogItemGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productCatalogItems/pci-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productCatalogItemGet_dtoFlatness_noNestedFKEntities() throws Exception {
        // FD#2/FD#3: flat FK strings only — no nested {product:{...}} / {productCatalog:{...}}.
        mvc.perform(get("/api/productCatalogItems/pci-ess-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.productCatalog").doesNotExist());
    }

    @Test void productCatalogItemGet_falseActiveRoundTrip() throws Exception {
        // pci-trauma-bandage seeds active=0: proves the bit(1)->Boolean read returns false, not the
        // constructor default true.
        mvc.perform(get("/api/productCatalogItems/pci-trauma-bandage").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test void productCatalogItemCache_refreshOnEmptyServesFromSeed() throws Exception {
        // Cache cleared in @BeforeEach for test isolation; first call populates + serves.
        mvc.perform(get("/api/productCatalogItems").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3));
        assertThat(productCatalogItemCache.getAll()).hasSize(3);
    }

    // ---------------------------------------------------------------
    // ProductAttribute (T8) — GET-only repo-backed read entity (zero React callers). NO cache
    // (Grails domain has no `cache true`), NO audit columns (live table + Grails domain have none).
    // No sibling writers (GET-only) → count-of-3 assertions are deterministic. Plus the N+1 proof.
    // ---------------------------------------------------------------

    @Test void productAttributeList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/productAttributes").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.id == 'pa-bandage-color')]").exists());
    }

    @Test void productAttributeGet_flatDto() throws Exception {
        mvc.perform(get("/api/productAttributes/pa-bandage-size").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pa-bandage-size"))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.attributeId").value("attr-size"))
            .andExpect(jsonPath("$.data.value").value("Large"))
            .andExpect(jsonPath("$.data.unitOfMeasureId").value("uom-pc"))
            .andExpect(jsonPath("$.data.productSupplierId").value("ps-bandage-acme"));
    }

    @Test void productAttributeGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productAttributes/pa-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productAttributeGet_dtoFlatness_noNestedFKEntities() throws Exception {
        // FD#2/FD#3: flat FK strings only — no nested {product:{...}} / {attribute:{...}} /
        // {unitOfMeasure:{...}} / {productSupplier:{...}}.
        mvc.perform(get("/api/productAttributes/pa-bandage-size").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.attribute").doesNotExist())
            .andExpect(jsonPath("$.data.unitOfMeasure").doesNotExist())
            .andExpect(jsonPath("$.data.productSupplier").doesNotExist());
    }

    @Test void productAttributeGet_nullableFksOmittedAsNull() throws Exception {
        // pa-bandage-color seeds unit_of_measure_id + product_supplier_id NULL: proves the null-FK
        // proxy maps to a null id string (all 4 FKs are DB-nullable in the live schema). value is read.
        mvc.perform(get("/api/productAttributes/pa-bandage-color").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.unitOfMeasureId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.productSupplierId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.value").value("Blue"));
    }

    @Test void productAttributeList_noN1_boundedQueryCount() {
        // N+1 proof: LAZY FKs + a DTO that reads only .getId() (the proxy id is populated without
        // initialization → no DB hit) means findAll() emits a SINGLE SELECT regardless of row count.
        // The assertion is that the JDBC statement count does NOT scale with rows (1, not 1+N). We
        // deliberately did NOT use @EntityGraph — it would add 4 LEFT JOINs we don't need (we read ids
        // only). Hibernate statistics are enabled via the @DynamicPropertySource generate_statistics flag.
        // NOTE: this reads the process-global SessionFactory statistics counter, so it is correct ONLY
        // while the suite runs single-threaded/sequentially (no parallel test execution is configured).
        // If @Execution(CONCURRENT)/maxParallelForks is ever enabled, scope the count to one session
        // (e.g. a StatementInspector) instead of the global counter, or this assertion will go flaky.
        org.hibernate.stat.Statistics stats =
            emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        var result = productAttributeService.list();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);   // ≥2 rows so a single query proves no fan-out
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);   // exactly ONE JDBC statement for N rows → no N+1
    }

    // ---------------------------------------------------------------
    // ProductAssociation (T9) — GET-only repo-backed read entity (zero React callers; FD#1). NO cache
    // (Grails domain has no `cache true`). Instant timestamp-only audit (date_created/last_updated NOT
    // NULL). SELF-FK mutualAssociation read as a proxy id only. Writes (validator + beforeDelete) stay
    // Grails-side. No sibling writers (GET-only) → count-of-3 assertions are deterministic. Plus N+1 proof.
    // ---------------------------------------------------------------

    @Test void productAssociationList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/productAssociations").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.id == 'pa-band-syr')]").exists());
    }

    @Test void productAssociationGet_flatDto() throws Exception {
        mvc.perform(get("/api/productAssociations/pa-band-syr").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pa-band-syr"))
            .andExpect(jsonPath("$.data.code").value("SUBSTITUTE"))
            .andExpect(jsonPath("$.data.productId").value("p-bandage"))
            .andExpect(jsonPath("$.data.associatedProductId").value("p-syringe"))
            .andExpect(jsonPath("$.data.quantity").value(1.00))
            .andExpect(jsonPath("$.data.comments").value("Bandage substitutes Syringe"))
            // SELF-FK: mutual_association_id was UPDATEd to point at pa-syr-band — proxy id read only.
            .andExpect(jsonPath("$.data.mutualAssociationId").value("pa-syr-band"));
    }

    @Test void productAssociationGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productAssociations/pa-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productAssociationGet_dtoFlatness_noNestedFKEntities() throws Exception {
        // FD#2/FD#3: flat FK strings only — no nested {product:{...}} / {associatedProduct:{...}} /
        // {mutualAssociation:{...}} (the self-FK must also stay flat).
        mvc.perform(get("/api/productAssociations/pa-band-syr").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.product").doesNotExist())
            .andExpect(jsonPath("$.data.associatedProduct").doesNotExist())
            .andExpect(jsonPath("$.data.mutualAssociation").doesNotExist());
    }

    @Test void productAssociationGet_nullMutualAssociation() throws Exception {
        // pa-band-iv seeds mutual_association_id NULL + comments NULL: proves the null self-FK proxy maps
        // to a null id string and a null scalar comments. quantity 2.50 read directly.
        mvc.perform(get("/api/productAssociations/pa-band-iv").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.mutualAssociationId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.comments").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.data.quantity").value(2.50));
    }

    @Test void productAssociationList_noN1_boundedQueryCount() {
        // N+1 proof: LAZY FKs + a DTO that reads only .getId() (the proxy id is populated without
        // initialization → no DB hit) means findAll() emits a SINGLE SELECT regardless of row count —
        // and this holds even for the SELF-FK mutualAssociation (its id is read off the proxy, no init).
        // We deliberately did NOT use @EntityGraph. Hibernate statistics enabled via @DynamicPropertySource.
        // NOTE: reads the process-global SessionFactory statistics counter, correct ONLY while the suite
        // runs single-threaded/sequentially (no parallel test execution is configured). If concurrency is
        // ever enabled, scope the count to one session instead of the global counter, or this goes flaky.
        org.hibernate.stat.Statistics stats =
            emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        var result = productAssociationService.list();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);   // ≥2 rows so a single query proves no fan-out
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);   // exactly ONE JDBC statement for N rows → no N+1
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
        // Task LQ: a no-param GET under the new default pagination (max=10) could drop ps-bandage-acme
        // off page 0 once sibling POST tests add rows, so pin the seeded row with a real-shaped filter
        // (supplier=org-acme-placeholder, which only ps-bandage-acme uses). Assert the {data, totalCount}
        // envelope: the seeded row is present and totalCount >= 1.
        mvc.perform(get("/api/productSuppliers?supplier=org-acme-placeholder").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.data[?(@.id == 'ps-bandage-acme')]").exists())
            .andExpect(jsonPath("$.totalCount").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)));
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
    // ProductSupplier list-query (Task LQ) — the React "Product Sources" list page (design §5).
    // Tests use the REAL list-page param/response shapes (per the C3 synthetic-payload lesson): the hook
    // sends product/supplier/defaultPreferenceTypes (ids) + offset/max/sort/order and REQUIRES the
    // {data, totalCount} envelope (it reads res.data.data + res.data.totalCount, computes
    // pages = ceil(totalCount / pageSize)). Fixtures use a DEDICATED org (org-lq-globex) and the
    // p-syringe/p-iv-drip products no sibling POST test writes a supplier row against, so every count
    // assertion below is deterministic against the shared committed test DB.
    // ---------------------------------------------------------------

    @Test void productSupplierList_envelopeIsExactlyDataAndTotalCount() throws Exception {
        // The envelope MUST be exactly {data, totalCount}. If totalCount is missing the hook's
        // pages = ceil(totalCount/pageSize) becomes NaN — the bug this task fixes.
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data").isArray())
            .andExpect(jsonPath("$.totalCount").isNumber())
            .andExpect(jsonPath("$.pages").doesNotExist())
            .andExpect(jsonPath("$.content").doesNotExist());
    }

    @Test void productSupplierList_filterByProduct() throws Exception {
        // product = product id. p-syringe has exactly one LQ fixture supplier (and no sibling POSTs).
        mvc.perform(get("/api/productSuppliers?product=p-syringe").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("ps-lq-syringe-globex"))
            .andExpect(jsonPath("$.data[0].productId").value("p-syringe"));
    }

    @Test void productSupplierList_filterBySupplier() throws Exception {
        // supplier = org id. org-lq-globex has exactly the 3 LQ fixture rows.
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.id == 'ps-lq-syringe-globex')]").exists())
            .andExpect(jsonPath("$.data[?(@.id == 'ps-lq-iv-multi')]").exists())
            .andExpect(jsonPath("$.data[?(@.id == 'ps-lq-iv-extra')]").exists());
    }

    @Test void productSupplierList_filterByDefaultPreferenceTypes_existsCountsSupplierOnce() throws Exception {
        // ps-lq-iv-multi has TWO preferences (pref-type-default AND pref-type-backup). Filtering by BOTH
        // types must return that supplier exactly ONCE with totalCount = 1 — a plain JOIN would
        // duplicate it (2 matching prefs → 2 rows) and inflate totalCount to 2. This guards the
        // EXISTS-not-JOIN choice. Scope to product=p-iv-drip so ps-lq-iv-extra (no prefs) is excluded
        // and only ps-lq-iv-multi can match.
        mvc.perform(get("/api/productSuppliers?product=p-iv-drip"
                + "&defaultPreferenceTypes=pref-type-default&defaultPreferenceTypes=pref-type-backup")
                .cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(1))
            .andExpect(jsonPath("$.data.length()").value(1))
            .andExpect(jsonPath("$.data[0].id").value("ps-lq-iv-multi"));
    }

    @Test void productSupplierList_totalCountIsFullFilteredCount_notPageSize() throws Exception {
        // With 3 org-lq-globex rows and max=2, the page carries 2 rows but totalCount is the full
        // filtered count (3), and data.length <= max. Proves totalCount != page size.
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex&max=2&offset=0").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test void productSupplierList_sortByNameAsc_ordersRows() throws Exception {
        // sort=name, order=asc over the 3 org-lq-globex rows:
        //   "IV Drip Extra from Globex" < "IV Drip from Globex" < "Syringe from Globex".
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex&sort=name&order=asc").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].id").value("ps-lq-iv-extra"))
            .andExpect(jsonPath("$.data[1].id").value("ps-lq-iv-multi"))
            .andExpect(jsonPath("$.data[2].id").value("ps-lq-syringe-globex"));
    }

    @Test void productSupplierList_sortByDateCreatedDesc_isHookDefault() throws Exception {
        // The hook's default sort: {sort: 'dateCreated', order: 'desc'}. The 3 fixtures have staggered
        // date_created (iv-extra newest → syringe oldest), so desc order is extra > multi > syringe.
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex&sort=dateCreated&order=desc").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[0].id").value("ps-lq-iv-extra"))
            .andExpect(jsonPath("$.data[1].id").value("ps-lq-iv-multi"))
            .andExpect(jsonPath("$.data[2].id").value("ps-lq-syringe-globex"));
    }

    @Test void productSupplierList_outOfAllowlistSort_fallsBackToDateCreated_not500() throws Exception {
        // A client-supplied sort outside the allowlist (here a real association nav that would raise
        // PropertyReferenceException → 500 if passed straight through) must fall back to dateCreated, not 500.
        mvc.perform(get("/api/productSuppliers?supplier=org-lq-globex&sort=product.name.bogus&order=asc").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalCount").value(3))
            // sort falls back to dateCreated; direction still honors the supplied order=asc → oldest first (syringe, multi, extra).
            .andExpect(jsonPath("$.data[0].id").value("ps-lq-syringe-globex"))
            .andExpect(jsonPath("$.data[1].id").value("ps-lq-iv-multi"))
            .andExpect(jsonPath("$.data[2].id").value("ps-lq-iv-extra"));
    }

    @Test void productSupplierList_rowsCarryProductName() throws Exception {
        // The list page shows a Product-Name column: rows must carry productName (read-only/derived,
        // fetched via the @EntityGraph — no N+1). It is a flat String, not a nested product object.
        mvc.perform(get("/api/productSuppliers?product=p-syringe").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].productName").value("Syringe"))
            .andExpect(jsonPath("$.data[0].product").doesNotExist());
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

    // ---------------------------------------------------------------
    // ProductPrice (T5) — backend + integration tests only.
    // Verb scope: GET /api/productPrices/{id} (cutover load read). Prices are WRITTEN through the
    // package POST (embedded productPackagePrice / contractPricePrice), not directly.
    // Deviation #1: ProductPrice has NO productPackage/productSupplier columns (currency is its only FK).
    // Deviation #2: no valid_until column — contractPriceValidUntil maps to to_date.
    // ---------------------------------------------------------------

    @Autowired org.openboxes.catalog.repository.ProductPriceRepository productPriceRepo;

    @Test void productPriceGet_returnsSeededRow_flat() throws Exception {
        // Flat DTO: price/currencyId/type/fromDate/toDate as scalars (currencyId raw id, no nested currency).
        mvc.perform(get("/api/productPrices/pp-price-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pp-price-acme"))
            .andExpect(jsonPath("$.data.price").value(9.99))
            .andExpect(jsonPath("$.data.type").value("DEFAULT_PRICE"))
            .andExpect(jsonPath("$.data.currencyId").value("uom-pc"));
    }

    @Test void productPriceGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productPrices/nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productPriceDto_isFlat_noNestedCurrency() throws Exception {
        // FD#2: DTO is flat — currencyId is a raw String id, there is NO nested `currency` entity.
        mvc.perform(get("/api/productPrices/pp-price-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.currency").doesNotExist())
            .andExpect(jsonPath("$.data.currencyId").isString());
    }

    @Test void productPackagePost_withEmbeddedPrices_persistsPackageAndContractPrice() throws Exception {
        // Real flat payload (per C3 lesson) — the cutover form's buildPackagePayload shape: the package
        // scalars PLUS the embedded price VALUES (productPackagePrice, contractPricePrice,
        // contractPriceValidUntil). The service materializes both ProductPrice rows.
        //
        // Test isolation: this test creates its OWN throwaway supplier (NOT the shared seeded
        // ps-bandage-acme). The contract-price branch reuses-or-creates the supplier's contractPrice,
        // so running it against ps-bandage-acme could update whatever price that supplier currently
        // points at (e.g. the seeded pp-price-acme, if productSupplierPut_setsContractPriceId ran
        // first), mutating its price out from under productPriceGet_returnsSeededRow_flat. A dedicated
        // supplier keeps this test order-independent against the shared committed test DB.
        String supplierJson = "{\"name\":\"Embedded Price Supplier\",\"productId\":\"p-bandage\"}";
        String supplierCreated = mvc.perform(post("/api/productSuppliers")
                .content(supplierJson).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        String supplierId = com.jayway.jsonpath.JsonPath.read(supplierCreated, "$.data.id");

        String json = "{\"productId\":\"p-bandage\",\"productSupplierId\":\"" + supplierId + "\"," +
            "\"uomId\":\"uom-pc\",\"quantity\":36,\"name\":\"Priced Pack\"," +
            "\"productPackagePrice\":5.50,\"contractPricePrice\":4.25," +
            "\"contractPriceValidUntil\":\"2027-01-01T00:00:00Z\"}";
        String body = mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            // (a) the returned package has a non-null productPriceId (its own price was created).
            .andExpect(jsonPath("$.data.productPriceId").isString())
            .andReturn().getResponse().getContentAsString();

        String productPriceId = com.jayway.jsonpath.JsonPath.read(body, "$.data.productPriceId");

        // (b) GET that price → price == productPackagePrice.
        mvc.perform(get("/api/productPrices/" + productPriceId).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.price").value(5.50));

        // (c) the supplier now exposes a non-null contractPriceId (its contract price was created).
        String supplierBody = mvc.perform(get("/api/productSuppliers/" + supplierId).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractPriceId").isString())
            .andReturn().getResponse().getContentAsString();
        String contractPriceId = com.jayway.jsonpath.JsonPath.read(supplierBody, "$.data.contractPriceId");

        // (d) GET the contract price → price == contractPricePrice AND toDate is set.
        mvc.perform(get("/api/productPrices/" + contractPriceId).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.price").value(4.25))
            .andExpect(jsonPath("$.data.toDate").exists());
    }

    @Test void productPackagePost_withoutPriceFields_stillSucceeds_noPrice() throws Exception {
        // T4 regression guard: a package POST WITHOUT any price fields must still 200 with a null
        // productPriceId (the price branches are conditional on non-null inputs).
        String json = "{\"productId\":\"p-bandage\",\"productSupplierId\":\"ps-bandage-acme\"," +
            "\"uomId\":\"uom-pc\",\"quantity\":99,\"name\":\"Unpriced Pack\"}";
        mvc.perform(post("/api/productPackages")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productPriceId").doesNotExist());
    }

    @Test void productSupplierPut_setsContractPriceId_exposedOnGet() throws Exception {
        // T5 forward-decl split (symmetric to the T4 defaultProductPackageId test): ProductSupplier.
        // contractPrice is now mapped. PUT the supplier with contractPriceId set to the seeded price,
        // then GET → the flat id is present.
        String json = "{\"name\":\"Bandage from Acme\",\"productId\":\"p-bandage\"," +
            "\"contractPriceId\":\"pp-price-acme\"}";
        mvc.perform(put("/api/productSuppliers/ps-bandage-acme")
                .content(json).contentType(MediaType.APPLICATION_JSON).cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractPriceId").value("pp-price-acme"));
        mvc.perform(get("/api/productSuppliers/ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.contractPriceId").value("pp-price-acme"));
    }

    @Test void productPackageDto_exposesProductPriceId_fromSeededLink() throws Exception {
        // Focused read assertion: link the seeded package to the seeded price via the repository, then
        // GET the package → productPriceId is exposed (the T5 read-side field on ProductPackageDto).
        var pkg = productPackageRepo.findById("pp-bandage-box").orElseThrow();
        pkg.setProductPrice(productPriceRepo.findById("pp-price-acme").orElseThrow());
        productPackageRepo.save(pkg);
        mvc.perform(get("/api/productPackages/pp-bandage-box").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.productPriceId").value("pp-price-acme"));
    }

    @Test void productPrice_orphanDeletes_withoutCascadeInterference() throws Exception {
        // Deviation #1 / no-cascade: a standalone ProductPrice (linked to no package/supplier) can be
        // created and deleted via the repository with no effect on seeded packages/suppliers.
        var orphan = new org.openboxes.catalog.entity.ProductPrice();
        orphan.setId("pp-price-orphan");
        orphan.setPrice(new java.math.BigDecimal("1.0000"));
        productPriceRepo.save(orphan);
        assertThat(productPriceRepo.findById("pp-price-orphan")).isPresent();

        productPriceRepo.deleteById("pp-price-orphan");
        assertThat(productPriceRepo.findById("pp-price-orphan")).isEmpty();

        // No cascade interference: the seeded package + supplier are untouched.
        mvc.perform(get("/api/productPackages/pp-bandage-box").cookie(authCookie()))
            .andExpect(status().isOk());
        mvc.perform(get("/api/productSuppliers/ps-bandage-acme").cookie(authCookie()))
            .andExpect(status().isOk());
    }

    // Test-only repository wiring for the seeded-link / orphan-delete repo-level assertions (mirrors the
    // cache-inject convention). Kept in the test to avoid widening the production API for a test.
    @Autowired org.openboxes.catalog.repository.ProductPackageRepository productPackageRepo;

    // ---------------------------------------------------------------
    // ProductComponent (T10) — GET-only repo-backed read entity (zero React callers; FD#1). NO cache
    // (Grails domain has no `cache true`). Instant timestamp-only audit (date_created/last_updated NOT
    // NULL). BOM line: assemblyProduct/componentProduct/unitOfMeasure all NOT NULL (no Grails-vs-live
    // deviation). Writes stay Grails-side (GSP ProductController; no validator to port). No sibling
    // writers (GET-only) → count-of-3 assertions are deterministic. Plus the N+1 proof.
    // ---------------------------------------------------------------

    @Test void productComponentList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/productComponents").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(3))
            .andExpect(jsonPath("$.data[?(@.id == 'pcomp-band-syr')]").exists());
    }

    @Test void productComponentGet_flatDto() throws Exception {
        mvc.perform(get("/api/productComponents/pcomp-band-syr").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("pcomp-band-syr"))
            .andExpect(jsonPath("$.data.assemblyProductId").value("p-bandage"))
            .andExpect(jsonPath("$.data.componentProductId").value("p-syringe"))
            .andExpect(jsonPath("$.data.quantity").value(2.00))
            .andExpect(jsonPath("$.data.unitOfMeasureId").value("uom-pc"));
    }

    @Test void productComponentGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/productComponents/pcomp-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void productComponentGet_dtoFlatness_noNestedFKEntities() throws Exception {
        // FD#2/FD#3: flat FK strings only — no nested {assemblyProduct:{...}} / {componentProduct:{...}} /
        // {unitOfMeasure:{...}}.
        mvc.perform(get("/api/productComponents/pcomp-band-syr").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.assemblyProduct").doesNotExist())
            .andExpect(jsonPath("$.data.componentProduct").doesNotExist())
            .andExpect(jsonPath("$.data.unitOfMeasure").doesNotExist());
    }

    @Test void productComponentList_noN1_boundedQueryCount() {
        // N+1 proof: LAZY FKs + a DTO that reads only .getId() (the proxy id is populated without
        // initialization → no DB hit) means findAll() emits a SINGLE SELECT regardless of row count —
        // all 3 LAZY FKs (assemblyProduct/componentProduct/unitOfMeasure) are read as proxy ids only.
        // We deliberately did NOT use @EntityGraph. Hibernate statistics enabled via @DynamicPropertySource.
        // NOTE: reads the process-global SessionFactory statistics counter, correct ONLY while the suite
        // runs single-threaded/sequentially (no parallel test execution is configured). If concurrency is
        // ever enabled, scope the count to one session instead of the global counter, or this goes flaky.
        org.hibernate.stat.Statistics stats =
            emf.unwrap(org.hibernate.SessionFactory.class).getStatistics();
        stats.clear();
        var result = productComponentService.list();
        assertThat(result).hasSizeGreaterThanOrEqualTo(2);   // ≥2 rows so a single query proves no fan-out
        assertThat(stats.getPrepareStatementCount()).isEqualTo(1L);   // exactly ONE JDBC statement for N rows → no N+1
    }

    // ---------------------------------------------------------------
    // UnitOfMeasureConversion (T11) — GET-only CACHE-backed read entity (zero React callers; FD#1).
    // Heuristic cache per the T1 audit §5/§8 (low churn, paired with UnitOfMeasureCache) — mirrors the
    // T6/T7 cache pattern (NOT the T8/T9/T10 N+1-statistics pattern). Instant timestamp-only audit
    // (date_created/last_updated NOT NULL); active bit(1) NOT NULL; both UoM FKs NOT NULL. Writes stay
    // Grails-side (GSP UnitOfMeasureConversionController). No sibling writers (GET-only) → count-of-4 is
    // deterministic. Plus the ported conversionRateLookup finder (service+repo only, NO REST endpoint).
    // ---------------------------------------------------------------

    @Test void unitOfMeasureConversionList_returnsSeeded() throws Exception {
        mvc.perform(get("/api/unitOfMeasureConversions").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4))
            .andExpect(jsonPath("$.data[?(@.id == 'uconv-kg-g-new')]").exists());
    }

    @Test void unitOfMeasureConversionGet_flatDto() throws Exception {
        mvc.perform(get("/api/unitOfMeasureConversions/uconv-kg-g-new").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").value("uconv-kg-g-new"))
            .andExpect(jsonPath("$.data.active").value(true))
            .andExpect(jsonPath("$.data.fromUnitOfMeasureId").value("uom-kg"))
            .andExpect(jsonPath("$.data.toUnitOfMeasureId").value("uom-g"))
            .andExpect(jsonPath("$.data.conversionRate").value(1000.50));
    }

    @Test void unitOfMeasureConversionGet_404OnMissing() throws Exception {
        mvc.perform(get("/api/unitOfMeasureConversions/uconv-nonexistent").cookie(authCookie()))
            .andExpect(status().isNotFound());
    }

    @Test void unitOfMeasureConversionGet_dtoFlatness_noNestedFKEntities() throws Exception {
        // FD#2/FD#3: flat FK strings only — no nested {fromUnitOfMeasure:{...}} / {toUnitOfMeasure:{...}}.
        mvc.perform(get("/api/unitOfMeasureConversions/uconv-kg-g-new").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.fromUnitOfMeasure").doesNotExist())
            .andExpect(jsonPath("$.data.toUnitOfMeasure").doesNotExist());
    }

    @Test void unitOfMeasureConversionGet_inactiveRoundTrip() throws Exception {
        // uconv-kg-g-inactive seeds active=0: proves the bit(1)->Boolean read returns false, not the
        // constructor default true.
        mvc.perform(get("/api/unitOfMeasureConversions/uconv-kg-g-inactive").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.active").value(false));
    }

    @Test void unitOfMeasureConversionCache_refreshOnEmptyServesFromSeed() throws Exception {
        // Cache cleared in @BeforeEach for test isolation; first call populates + serves.
        mvc.perform(get("/api/unitOfMeasureConversions").cookie(authCookie()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(4));
        assertThat(unitOfMeasureConversionCache.getAll()).hasSize(4);
    }

    @Test void findConversionRate_returnsMostRecentActiveRate() {
        // Ported conversionRateLookup: kg->g has two ACTIVE rows (1000.00 older, 1000.50 newer) plus an
        // INACTIVE 999 row with the LATEST last_updated. The finder must (a) exclude the inactive row via
        // the active filter, and (b) pick 1000.50 over 1000.00 via order-by-last_updated-desc. compareTo
        // (not equals) avoids decimal(19,8) scale brittleness.
        var rate = unitOfMeasureConversionService.findConversionRate("kg", "g");
        assertThat(rate).isPresent();
        assertThat(rate.get().compareTo(new java.math.BigDecimal("1000.50"))).isEqualTo(0);
    }

    @Test void findConversionRate_emptyWhenNoMatch() {
        // No kg->dz conversion seeded → Optional.empty.
        assertThat(unitOfMeasureConversionService.findConversionRate("kg", "dz")).isEmpty();
    }
}
