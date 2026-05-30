# Phase 5 Catalog-Service Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking. Each task is one git commit unless a step explicitly says otherwise. Light SDD calibration carried forward from Phase 4 T11 lesson + Phase 4.1 process discipline: Direct apply when (plan-pre-approved verbatim AND no business logic AND <20 LOC); full subagent cycle otherwise. Per-task gate cadence (Phase 3+4+4.1 retro lesson): stop after each task's two-stage review for user disposition.

**Source spec:** `docs/specs/2026-05-29-phase-5-catalog-service-design.md` (commit SHA: `c9bfaa829`)

**Goal:** Stand up `catalog-service` as the 8th Spring Boot container at port 8085 owning the React-facing HTTP surface for 9 catalog entities (Product R/O + 8 reference entities: Category, UnitOfMeasure, UnitOfMeasureClass, Tag, Synonym, ProductType, Attribute, ProductGroup), with per-entity write scope T1-determined, leaving the heaviest catalog write paths (ProductMergeService, InventoryService bulk import, GSP admin, CSV importers) in Grails until Phase 6/7/12.

**Architecture:** Partial-strangler per Phase 4 FD#1 pattern; flat FK-only DTOs per FD#3; Tag↔Product M:N via `product_tag` join table owned by catalog-service per FD#9; Synonym validator moves from GORM cross-instance to service-layer per FD#10; 5th copy of JWT triple (`JwtCookieAuthFilter`, `JwtService`, `SecurityConfig`) from organization-service per FD#6 (no `jwt-auth-common` extraction — deferred to Phase 5.1); `ProductApiController.groovy` stays alive (god-controller with cross-context queries) plus `ProductClassificationApiController` (cross-context Location+InventoryLevel+Product) and all 4 ProductPackage/ProductSupplier-family controllers (deferred entities) — per-controller delete decisions in T1 audit.

**Tech stack:** Spring Boot 3.3.5 (via root `services/build.gradle` plugin), Java 21 toolchain (via `subprojects` block), Hibernate 6 / JPA 3.0, Liquibase 4.20+ (shadow changelogs with `tableExists` precondition + empty body), Spring Data JPA, Spring Security 6 (HMAC HS256 JWT cookie auth), springdoc-openapi 2.5.0, jjwt 0.12.5, MariaDB JDBC 3.4.1, TestContainers 1.21.3 (MariaDB 10), JUnit 5, MockMvc; Playwright (browsers: chromium); Gradle multi-module build at `services/`; Docker Compose v2; nginx 1.27 (reverse proxy at `docker/nginx/conf.d/app.conf`); GitHub Actions CI at `.github/workflows/e2e-tests.yml`.

---

## File Structure

### Create (new files for `catalog-service` module — ~50 source files)

- `services/catalog-service/build.gradle` — Gradle module config; inherits Spring Boot 3.3.5 + Java 21 from `services/build.gradle` `subprojects` block; depends on starter-web, starter-data-jpa, starter-security, starter-validation, starter-actuator, liquibase-core, springdoc-openapi-starter-webmvc-ui, jjwt (api+impl+jackson), mariadb-java-client; test deps: starter-test, spring-security-test, testcontainers-junit-jupiter, testcontainers-mariadb (T2)
- `services/catalog-service/src/main/java/org/openboxes/catalog/CatalogServiceApplication.java` — `@SpringBootApplication` main class (T2)
- `services/catalog-service/src/main/java/org/openboxes/catalog/security/JwtCookieAuthFilter.java` — 5th copy from organization-service per FD#6 (T5)
- `services/catalog-service/src/main/java/org/openboxes/catalog/security/JwtService.java` — ditto (T5)
- `services/catalog-service/src/main/java/org/openboxes/catalog/security/SecurityConfig.java` — ditto (T5)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Product.java` — `@Entity` (R/O per FD#1 — enforced via `@Transactional(readOnly = true)` on ProductService, NOT `@Immutable` annotation, per CIR R1 §2.1); FKs to UnitOfMeasure, ProductType, Category; M:N to Tag via `product_tag` (Product is owning side per FD#9); `@OneToMany(mappedBy="product") List<Synonym> synonyms`; M:N to ProductGroup via `product_group_product`; `productFamily` FK to ProductGroup (per ProductGroup `siblings` mappedBy) (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Category.java` — self-FK tree (`@ManyToOne parentCategory`, `@OneToMany(mappedBy="parentCategory") List<Category> categories`); GlAccount FK; `@Cacheable` for L2 cache per FD#7 (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/UnitOfMeasure.java` — `@ManyToOne uomClass` per FD#11 (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/UnitOfMeasureClass.java` — bidirectional `@ManyToOne baseUom` + `@OneToMany(mappedBy="uomClass") List<UnitOfMeasure> uoms`; both FKs nullable per FD#11 (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Tag.java` — `@ManyToMany(mappedBy="tags") Set<Product> products` (inverse side per FD#9; Product owns) (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Synonym.java` — `@ManyToOne product`; cross-instance validator NOT in entity per FD#10 (moves to service in T6) (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/ProductType.java` — flat reference; `@ElementCollection` for `supportedActivities`, `requiredFields`, `displayedFields` enum lists (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Attribute.java` — FK to UnitOfMeasureClass; `@ElementCollection List<String> options`; `@ElementCollection List<EntityTypeCode> entityTypeCodes` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/entity/ProductGroup.java` — `@ManyToOne category`; `@ManyToMany(mappedBy="productGroups") Set<Product> products` via `product_group_product`; `@OneToMany(mappedBy="productFamily") Set<Product> siblings` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/ProductRepository.java` × 1 — `extends JpaRepository<Product, String>` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/CategoryRepository.java` — including `findByParentCategoryIsNull()` for root nodes (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/UnitOfMeasureRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/UnitOfMeasureClassRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/TagRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/SynonymRepository.java` — including `countByProductIdAndLocaleAndSynonymTypeCode(...)` for FD#10 validator (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/ProductTypeRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/AttributeRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/repository/ProductGroupRepository.java` (T4)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/ProductDto.java` — flat FK-only per FD#3: `id, name, description, productCode, productTypeId, categoryId, unitOfMeasureId, pricePerUnit, costPerUnit, active, tagIds (Set<String>), synonymIds (Set<String>)` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/CategoryDto.java` — `id, name, description, parentCategoryId, sortOrder, isRoot, glAccountId` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/UnitOfMeasureDto.java` — `id, name, code, description, uomClassId` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/UnitOfMeasureClassDto.java` — `id, name, code, description, active, type, baseUomId` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/TagDto.java` — `id, tag, isActive, productIds (Set<String>)` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/SynonymDto.java` — `id, productId, name, locale, synonymTypeCode` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/ProductTypeDto.java` — flat per ProductType fields (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/AttributeDto.java` — flat per Attribute fields (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/dto/ProductGroupDto.java` — `id, name, description, categoryId, productIds (Set<String>), siblingIds (Set<String>)` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/ProductService.java` — R/O per FD#1 (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/CategoryService.java` — per T1 (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/TagService.java` — includes M:N write protocol per T1+FD#9 forced decision (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/SynonymService.java` — service-layer validator per FD#10 (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/UnitOfMeasureService.java` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/ProductTypeService.java` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/AttributeService.java` (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/service/ProductGroupService.java` — per T1 (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/cache/UnitOfMeasureCache.java` — refresh-on-miss per FD#7 + Phase 4 PartyTypeCache pattern + Phase 3 RC-6 fix (`getAll()`-refresh-on-empty) (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/cache/ProductTypeCache.java` — refresh-on-miss (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/cache/AttributeCache.java` — refresh-on-miss (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/cache/CategoryCache.java` — refresh-on-write (mid-churn) (T6)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/ProductController.java` — R/O endpoints: `GET /api/product/{id}`, `GET /api/product`, plus subset of basic Product reads per FD#12 + T1 (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/CategoryController.java` — default GET-only; POST/PUT/DELETE per T1 (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/TagController.java` — default GET-only; POST/PUT/DELETE per T1; M:N writes invoke FD#9 forced-decision-determined protocol (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/SynonymController.java` — default GET-only; POST/PUT/DELETE per T1 (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/UnitOfMeasureController.java` — `GET /api/unitOfMeasure{,/{id}}` + `GET /api/unitOfMeasureClass{,/{id}}` (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/ReferenceController.java` — bundle `GET /api/productType{,/{id}}` + `GET /api/attribute{,/{id}}` (T7)
- `services/catalog-service/src/main/java/org/openboxes/catalog/controller/ProductGroupController.java` — default GET-only; POST/PUT/DELETE per T1 (T7)
- `services/catalog-service/src/main/resources/application.yml` — port 8085, datasource (env vars), JPA validate, Liquibase enabled, JWT secret env (T2 creates with Liquibase disabled; T3 enables; T7 adds management.endpoints.web.exposure.include=health,info)
- `services/catalog-service/src/main/resources/db/changelog/db.changelog-master.xml` — includes 9-11 shadow changelog files (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-product.xml` — `phase5-shadow-create-product` changeSet; `tableExists tableName="product"` precondition; empty body (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-category.xml` — `tableExists tableName="category"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-unit-of-measure.xml` — `tableExists tableName="unit_of_measure"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-unit-of-measure-class.xml` — `tableExists tableName="unit_of_measure_class"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-tag.xml` — `tableExists tableName="tag"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-synonym.xml` — `tableExists tableName="synonym"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-product-type.xml` — `tableExists tableName="product_type"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-attribute.xml` — `tableExists tableName="attribute"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-product-group.xml` — `tableExists tableName="product_group"` (T3)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-product-tag.xml` — `tableExists tableName="product_tag"` (T3; M:N join table per FD#9)
- `services/catalog-service/src/main/resources/db/changelog/changelog-shadow-create-product-group-product.xml` — `tableExists tableName="product_group_product"` (T3; ProductGroup M:N join table per F11 finding)
- `services/catalog-service/src/test/java/org/openboxes/catalog/CatalogServiceIntegrationTest.java` — TestContainers MariaDB; MockMvc; ~30-40 tests; authCookie helper; per Phase 4 OrganizationServiceIntegrationTest pattern (T10)
- `services/catalog-service/src/test/resources/seed.sql` — fixtures for 9 entities + product_tag M:N rows + product_group_product M:N + Category tree + UoM↔UoMClass bidir (T10)
- `services/catalog-service/Dockerfile` — Java 21 base; copy bootJar; expose 8085; ENTRYPOINT java -jar; per organization-service Dockerfile pattern (T2)
- `e2e/tests/catalog-product-readonly.spec.ts` — Product R/O Playwright spec (T11)
- `e2e/tests/catalog-category.spec.ts` — Category GET (+ writes if T1 in scope) (T11)
- `e2e/tests/catalog-tag.spec.ts` — Tag GET (+ writes if T1 in scope) (T11)
- `e2e/tests/catalog-synonym.spec.ts` — Synonym GET (+ writes if T1 in scope) (T11)
- `e2e/tests/catalog-uom.spec.ts` — UoM + UoMClass GET-only (T11)
- `e2e/tests/catalog-reference.spec.ts` — ProductType + Attribute GET-only (T11)
- `e2e/tests/catalog-product-group.spec.ts` — ProductGroup GET (+ writes if T1 in scope) (T11)
- `e2e/tests/catalog-options-regression.spec.ts` — assert `/api/categoryOptions` + `/api/tagOptions` + `/api/productGroupOptions` continue to work (post-T9 React migration; ensures dropdowns don't break) (T11)
- `docs/retrospectives/2026-MM-DD-phase-5-catalog-retrospective.md` — Phase 5 retro with A-F triage; Phase 5.1 forward pointer (T13)

### Modify (existing files)

- `services/settings.gradle` — add `include 'catalog-service'` (T2; verified path NOT root `settings.gradle` which is for Grails)
- `docker/docker-compose-base.yml` — add catalog-service entry as 8th container (port 8085 expose; healthcheck; DATASOURCE_* + OPENBOXES_JWT_SECRET env vars per organization-service pattern) (T2; verified path NOT `docker/openboxes/docker-compose-base.yml`)
- `docker/nginx/conf.d/app.conf` — append catalog `/api/<entity>` blocks (per FD#4 + Phase 4.1 RC-10 insertion order + RC-11 `include /etc/nginx/conf.d/proxy_params;`; placed after organization blocks, before `/api/` catch-all) (T8; verified path NOT `docker/openboxes/nginx/app.conf`)
- `.github/workflows/e2e-tests.yml` — extend "Build identity-service + document-service jars" step to include `:catalog-service:bootJar`; extend health probe loop to check `openboxes-catalog-service curl localhost:8085/actuator/health`; extend diagnostic dump-on-failure to include catalog-service logs (T2; bundled with compose per RC-2)
- `src/js/api/urls.js` — migrate URL constants for moved endpoints per T1 audit; PLURAL→SINGULAR per FD#4 (e.g., `/api/attributes`→`/api/attribute`, `/api/unitOfMeasures`→`/api/unitOfMeasure`); add new constants for `/api/category`, `/api/tag`, etc.; existing PRODUCT_GROUP_OPTION (line 96 `/api/productGroupOptions`) stays as-is (option endpoint, not entity CRUD) (T9)
- `src/js/utils/option-utils.jsx` — migrate inline `/api/categoryOptions` at line 281 + inline `/api/tagOptions` at line 291 to use new catalog-service routes if T1 audit confirms move; **EXCLUDE line 286 `/api/catalogOptions`** (hits DEFERRED ProductCatalog entity — Phase 5.5 scope) (T9)
- `grails-app/controllers/org/pih/warehouse/api/<DeletableController>.groovy` — DELETE files per T1 audit; candidate list per F10 finding: `AttributeApiController.groovy`, `CategoryApiController.groovy`, `UnitOfMeasureApiController.groovy` (DEFINITIVELY NOT deletable per spec: `ProductApiController.groovy` per FD#12, `ProductClassificationApiController.groovy` per CDR R1 §3.2, `ProductPackageApiController.groovy` per FD#5 deferral, `ProductSupplier*ApiController.groovy` ×3 per FD#5 deferral, `ProductsConfigurationApiController.groovy` probably stays per spec FD#4) (T9)

### Test (under `services/catalog-service/src/test/`)

- `CatalogServiceIntegrationTest.java` — ~30-40 tests covering: GET/POST per entity, Tag↔Product M:N writes (if in scope post-T1), Synonym validator-as-service rejection, Category tree walk, UoM↔UoMClass bidirectional reads, ProductGroup ↔ Product M:N + siblings, JWT auth (200 valid / 401 missing / 401 invalid), polymorphic / nullable-FK edges, FD#9 forced-decision-driven behavior (if Tag writes in scope, test the post-T1 chosen resolution)
- `e2e/tests/catalog-*.spec.ts` — 6-10 Playwright specs per migrated React surface; include `catalog-options-regression.spec.ts` for option-endpoint dropdown post-migration sanity

---

## Inherited from spec

The following assumptions were verified by `thorough-brainstorming` at spec-write time and are NOT re-verified here. Trusted as ground truth (verbatim from spec §8):

| # | Assumption | Result | Key evidence |
|---|---|---|---|
| A1 | Product entity exists; FK list known | ⚠️ Heavy cross-context | `grails-app/domain/.../product/Product.groovy:53`; lines 25-38 import Inventory/InventoryItem/ShipmentItem/TransactionEntry; lines ~67-78 have `publishPersistenceEvent` lifecycle hook → handled by FD#8 |
| A2 | Category exists with self-FK parent | ✅ | `Category.groovy:20` `Category parentCategory`; line 29 `hasMany categories: Category`; line 37 `cache true` |
| A3 | UnitOfMeasure FK to UoMClass | ✅ | `UnitOfMeasure.groovy:29` `UnitOfMeasureClass uomClass` |
| A4 | UnitOfMeasureClass exists | ⚠️ Circular FK | `UoMClass.groovy:32` `UnitOfMeasure baseUom` back-references UoM → FD#11 standard JPA bidir |
| A5 | Tag exists | ⚠️ M:N to Product | `Tag.groovy:35,39,42` `belongsTo = Product` + `hasMany products` + `joinTable: product_tag` → FD#9 |
| A6 | Synonym FK to Product | ⚠️ GORM validator | `Synonym.groovy:37` `belongsTo = [product: Product]` ✓; lines 45-53 cross-instance validator → FD#10 service-layer port |
| A7 | ProductAttribute + Attribute | ⚠️ Separate Attribute entity missed | `Attribute.groovy:22` is a distinct entity; ProductAttribute has 4 FKs (Attribute, Product, ProductSupplier, UoM) → ProductAttribute DEFERRED, Attribute IN SCOPE per FD#5 |
| A8 | ProductPackage exists | ⚠️ + ProductPrice missed | imports `ProductPrice` from `core/`; ProductPackage + ProductPrice DEFERRED per FD#5 |
| A9 | ProductCatalog exists | ✅ + ProductCatalogItem paired | `ProductCatalog.groovy:3` hasMany ProductCatalogItem; both DEFERRED per FD#5 |
| A10 | ProductSupplier React surface | ⚠️ React has CRUD | `ProductSupplierApi.js:14-19` `deleteProductSupplier`, `saveDetails` POST, `updateDetails` PUT — DEFERRED to Phase 5.5 per FD#5 |
| A11 | No JPA inheritance in catalog | ✅ | `grep -l '@Inheritance\|class.*extends' grails-app/domain/.../product/ + .../core/{UoM,Tag,Synonym}*.groovy` returned ZERO → FD#2 |
| A12 | Reference-data row counts | ⚠️ All ZERO except UoM(8)/UoMClass(2) | Dev DB queries via `sudo docker exec openboxes-db mariadb ... SELECT COUNT(*) FROM ...` → FD#7 falls back to churn-frequency heuristic |
| A13 | ProductApiController exists | ⚠️ God-controller | `ProductApiController.groovy` has list, demand, demandSummary, productSummary, productAvailability, search + injects productService/inventoryService/forecastingService/productAvailabilityService → FD#4 stays alive, FD#12 thin migration |
| A14 | Grails URL mappings exist | ⚠️ Huge surface | `UrlMappings.groovy` lines 45-148, 504-509 enumerate 20+ /api/products/* and /api/categories/* paths INCLUDING cross-context endpoints → FD#4 per-URL decisions |
| A15 | React URL constants location | ⚠️ | `src/js/api/urls.js` is the primary URL constants file BUT some catalog URLs are inline strings (verified: `src/js/utils/option-utils.jsx:281` `/api/categoryOptions`, `:291` `/api/tagOptions`); T1 audit must grep all `src/js/**/*.{js,jsx,ts,tsx}` for inline `/api/*` strings, not just `urls.js`. `src/js/api/services/ProductApi.js` confirmed READ-ONLY (no apiClient.post/put/delete for Product) → FD#1 Product R/O still stands |
| A16 | Cross-context atomic-write audit | ⚠️ Many findings | greps surfaced: `new Product(` in InventoryService; `new Tag(` in TagImportDataService; `new Category(` in CategoryImportDataService; cross-package productService injections in LoadDataService, StockMovementService, ProductAvailabilityService, ShipmentService, ProductSynonymImportDataService → all handled per parent §4.3 coverage policy + FD#1 (stay Grails) |
| A17 | ProductMergeService stays Grails | ✅ | `ProductMergeService.groovy:44` `@Transactional def mergeProduct` has 15+ `.save(flush:true)` across Transaction/InventoryItem/RequisitionItem/ShipmentItem/OrderItem/ReceiptItem/InvoiceItem — confirms parent §6 Phase 6 deferral |
| A18 | InventoryService.processData creates Products | ✅ | `grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy` — Phase 6 per parent §4.3 row 7 |
| A19 | ProductSummary is SQL view | ✅ | `grails-app/migrations/views/product-summary.sql` is `CREATE OR REPLACE VIEW product_summary AS ... FROM product_availability JOIN product ... on_order_*_summary` — DEFERRED to Phase 11 (or never) |
| A20 | ProductDimension in reporting/ | ✅ | `grails-app/domain/org/pih/warehouse/reporting/ProductDimension.groovy` — Phase 11 scope |
| A21 | ProductAvailability is inventory cross-cut | ✅ | `ProductAvailability.groovy:14` imports `InventoryItem` — Phase 6 scope |
| A22 | ProductMergeLogger is merge audit | ✅ | `ProductMergeLogger.groovy` package `product/`, imports User; defers with ProductMergeService → Phase 6 |
| A23 | JWT triple portable | ✅ | `services/organization-service/src/main/java/org/openboxes/organization/security/{JwtCookieAuthFilter,JwtService,SecurityConfig}.java` confirmed — 3 files ready for 5th copy + package rename |
| A24 | Port 8085 unused | ✅ | `docker-compose-base.yml` lines 32-105: expose 8081 (document) / 8082 (identity) / 8083 (location) / 8084 (organization); 8085 free |
| A25 | Phase 4.1 docs/process/ + nginx infra applies | ✅ | docs/process/{sdd-reviewer-checklist,plan-template-defects,plan-ordering-rules}.md exist (committed at Phase 4.1); nginx app.conf has RC-10 block-order comment + 8 include /etc/nginx/conf.d/proxy_params lines (committed at Phase 4.1) |
| A26 | Catalog entity inventory complete | ⚠️ 17+ entities, not 6 | `ls grails-app/domain/.../product/` + `core/{UoM,Tag,Synonym}*.groovy` surfaced 17 candidates; Attribute, ProductPrice, ProductCatalogItem, UoMConversion missed in initial scoping → FD#5 9-IN-13-DEFER table reflects empirical reality |

---

## Verified plan-level assumptions

The following 80 plan-level assumptions were enumerated cold against the draft plan and verified at plan-write time. Findings marked **F#** are mechanical corrections (silently absorbed in plan body per `thorough-writing-plans` Step 7 mechanical-fix protocol) or informational nuances (folded into task descriptions).

| # | Category | Assumption | Evidence | Status |
|---|---|---|---|---|
| P1 | File path | `services/catalog-service/` does NOT yet exist | `ls services/` returned 4 services (document, identity, location, organization) + build.gradle/settings.gradle/gradle/gradlew; no catalog-service | ✅ |
| P2 | File path | `services/settings.gradle` lists 4 service includes (NOT root `settings.gradle`) | `cat services/settings.gradle`: `rootProject.name = 'openboxes-services'` + 4 `include` lines | ✅ **F5: T2 modifies `services/settings.gradle`, NOT root `settings.gradle`** |
| P3 | File path | `docker/docker-compose-base.yml` exists with 7-service entries; port 8085 free | `find docker -name "docker-compose*.yml"` returned `docker/docker-compose-base.yml` (NOT `docker/openboxes/...`); `grep expose docker/docker-compose-base.yml` showed exposes for 8081/8082/8083/8084 only | ✅ **F1: path is `docker/docker-compose-base.yml` not `docker/openboxes/`** |
| P4 | File path | nginx config exists with Phase 4.1 RC-10/RC-11 in place | `find docker -name "*.conf"` returned `docker/nginx/conf.d/app.conf`; `grep RC` returned line 13 RC-10 insertion-order comment + RC-11 proxy_params reference; 8 `include /etc/nginx/conf.d/proxy_params;` lines; 9 location blocks | ✅ **F2: path is `docker/nginx/conf.d/app.conf` not `docker/openboxes/nginx/app.conf`** |
| P5 | File path | `.github/workflows/e2e-tests.yml` has Phase 4 build/probe pattern | `head -80 .github/workflows/e2e-tests.yml` shows JDK 8 + JDK 21 setup, `services` working-dir gradle build with explicit jar list (`:identity-service:bootJar :document-service:bootJar :location-service:bootJar :organization-service:bootJar`), 60-iteration curl loop probing each service's /actuator/health | ✅ |
| P6 | File path | organization-service security triple exists | `ls services/organization-service/src/main/java/org/openboxes/organization/security/` returned JwtCookieAuthFilter.java, JwtService.java, SecurityConfig.java | ✅ |
| P7 | File path | organization-service template files exist | `cat services/organization-service/build.gradle` (deps list); application.yml (port 8084, datasource env, JPA validate, Liquibase enabled); db.changelog-master.xml; OrganizationServiceIntegrationTest.java; seed.sql | ✅ |
| P8 | File path | `src/js/api/urls.js` exists | `ls src/js/api/urls.js` returned path | ✅ |
| P9 | File path | `src/js/utils/option-utils.jsx` has inline /api/* at :281 + :291 | `grep -n "/api/categoryOptions\|/api/tagOptions\|/api/catalogOptions" src/js/utils/option-utils.jsx`: :281 `/api/categoryOptions`, :286 `/api/catalogOptions`, :291 `/api/tagOptions` | ✅ **F8: :286 `/api/catalogOptions` hits DEFERRED ProductCatalog → exclude from T9** |
| P10 | File path | `Product.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/product/Product.groovy` returned 31670-byte file | ✅ |
| P11 | File path | `Category.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/product/Category.groovy` returned 5167-byte file | ✅ |
| P12 | File path | `UnitOfMeasure.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/core/UnitOfMeasure.groovy` returned 1760-byte file | ✅ |
| P13 | File path | `UnitOfMeasureClass.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/core/UnitOfMeasureClass.groovy` returned 1446-byte file | ✅ |
| P14 | File path | `Tag.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/core/Tag.groovy` returned 1471-byte file | ✅ |
| P15 | File path | `Synonym.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/core/Synonym.groovy` returned 2161-byte file | ✅ |
| P16 | File path | `ProductType.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/product/ProductType.groovy` returned 2436-byte file | ✅ |
| P17 | File path | `Attribute.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/product/Attribute.groovy` returned 2611-byte file | ✅ |
| P18 | File path | `ProductGroup.groovy` at expected path | `ls -la grails-app/domain/org/pih/warehouse/product/ProductGroup.groovy` returned 1736-byte file | ✅ |
| P19 | File path | `ProductApiController.groovy` exists | `ls grails-app/controllers/org/pih/warehouse/api/ProductApiController.groovy` returned path | ✅ |
| P20 | File path | `ProductClassificationApiController.groovy` exists | `ls grails-app/controllers/org/pih/warehouse/api/ProductClassificationApiController.groovy` returned path | ✅ |
| P21 | File path | `ProductPackageApiController.groovy` exists | `ls grails-app/controllers/org/pih/warehouse/api/ProductPackageApiController.groovy` returned path | ✅ |
| P22 | File path + signature | `ProductClassificationService.list(facilityId)` reads Location + InventoryLevel + Product.abcClass | `sed -n '30,60p' grails-app/services/org/pih/warehouse/product/ProductClassificationService.groovy` confirmed: `List<ProductClassificationDto> list(String facilityId)` → `Location.read(facilityId)` (line 37) + InventoryLevel criteria filtered by `facility.inventory` (line 50) + Product.abcClass via groupProperty (line 42) | ✅ |
| P23 | File path | Phase 4 plan template exists | `ls docs/plans/2026-05-28-phase-4-organization-service-implementation-plan.md` returned 1958-line file | ✅ |
| P24 | File path | Phase 4.1 plan template exists | `ls docs/plans/2026-05-29-phase-4.1-cleanup-implementation-plan.md` returned 698-line file | ✅ |
| P25 | File path | docs/process/ rules all exist | `ls docs/process/{sdd-reviewer-checklist,plan-ordering-rules,plan-template-defects}.md` returned 3 paths | ✅ |
| P26 | File path | `e2e/` directory exists | `ls e2e/` returned: fixtures, node_modules, package-lock.json, package.json, playwright.config.ts, test-results, tests | ✅ **F4: Playwright spec dir is `e2e/tests/` not `e2e/specs/`; per `playwright.config.ts: testDir: './tests'`** |
| P27 | File path | `e2e/fixtures/auth.ts` exists | `ls e2e/fixtures/auth.ts` returned path | ✅ |
| P28 | File path | `docs/retrospectives/` directory exists | `ls docs/retrospectives/` returned 5 phase retro files | ✅ |
| P29 | File path | UrlMappings.groovy at correct path | `find grails-app -name "UrlMappings.groovy"` returned `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` | ✅ **F3: path is `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` not `grails-app/conf/`** |
| P30 | Signature | `JwtCookieAuthFilter extends OncePerRequestFilter` with `doFilterInternal(HttpServletRequest, HttpServletResponse, FilterChain)` | `head -30 services/organization-service/src/main/java/org/openboxes/organization/security/JwtCookieAuthFilter.java`: `public class JwtCookieAuthFilter extends OncePerRequestFilter` + `protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain) throws ServletException, IOException` | ✅ |
| P31 | Signature | `JwtService.validate(String)` returns `Map<String, Object>` | `head -40 services/organization-service/src/main/java/org/openboxes/organization/security/JwtService.java`: `public Map<String, Object> validate(String token)` returns claims or null on failure (NOT `Optional<Authentication>` as P31 originally hypothesized; doesn't matter since 5th copy is verbatim) | ✅ |
| P32 | Signature | `SecurityConfig` provides `SecurityFilterChain` `@Bean` | `head -50 services/organization-service/src/main/java/org/openboxes/organization/security/SecurityConfig.java`: `@Bean public SecurityFilterChain filterChain(HttpSecurity http)` with `.addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)` | ✅ |
| P33 | Signature | `ProductClassificationService.list(facilityId)` reads Location + InventoryLevel + Product.abcClass | (See P22) | ✅ |
| P34 | Signature | UrlMappings has explicit option-endpoint mappings | `grep -n "categoryOptions\|tagOptions\|productGroupOptions\|catalogOptions" grails-app/controllers/org/pih/warehouse/UrlMappings.groovy`: line 55 categoryOptions, 61 catalogOptions, 67 productGroupOptions, 73 tagOptions — **all EXPLICIT mappings, not via generic /api/${resource}s** | ✅ **F7: option endpoints have explicit URL mappings; T1 audit grep must include `*ApiController.options` actions enumeration** |
| P35 | Signature | spec assumptions A2-A11 entity FK details | Spec-inherited per skill (NOT re-verified at plan-level) | ✅ Inherited |
| P36 | Code-in-plan | Spring Boot @RestController + @RequestMapping standard usage | Phase 4 organization-service controllers verified pattern; standard Spring Web 6.x | ✅ |
| P37 | Code-in-plan | Liquibase tableExists precondition + empty body pattern valid | `cat services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-party.xml`: `<preConditions onFail="MARK_RAN" onFailMessage="..."><tableExists tableName="party"/></preConditions>` + `<comment>` + no body. Phase 5 uses `phase5-shadow-create-<entity>` changeSet id (vs Phase 4's `phase4-shadow-create-<entity>`) | ✅ |
| P38 | Code-in-plan | JPA `@ManyToMany @JoinTable(name="product_tag", ...)` valid form | Standard JPA 3.0; matches FD#9 spec; Tag entity has `belongsTo = Product` + `joinTable: product_tag` mapping in Grails → JPA equivalent verified by spec | ✅ |
| P39 | Code-in-plan | `interface XRepository extends JpaRepository<X, String>` valid (ID type is String per `id generator: 'uuid'` in all Grails mappings) | Phase 4 organization-service PartyRepository.java verified pattern | ✅ |
| P40 | Command | Gradle build: `./gradlew :catalog-service:build` runs from `services/` (NOT root); root has Grails wrapper at `./gradlew` for Grails build | `services/build.gradle` exists; per CI workflow's `working-directory: services` + `./gradlew :identity-service:bootJar ...` pattern → catalog-service builds via `(cd services && ./gradlew :catalog-service:build)` | ✅ |
| P41 | Command | JUnit run: `(cd services && ./gradlew :catalog-service:test)` | Per Phase 4 T9 pattern + services/build.gradle subprojects apply java + test {} block in organization-service build.gradle | ✅ |
| P42 | Command | Playwright: `(cd e2e && npm test)` invokes `playwright test` | `grep -A 10 "scripts" e2e/package.json`: `"test": "playwright test"` | ✅ |
| P43 | Command | nginx lint: `sudo docker exec openboxes-nginx nginx -t` | Phase 4.1 RC-10 documented pattern; works against running nginx container | ✅ |
| P44 | Command | Compose run: `(cd docker && sudo docker compose up -d)` per CI working-directory pattern | CI workflow uses `working-directory: docker` + `docker compose up --build -d` | ✅ |
| P45 | Command | CI workflow build pattern: gradle wrapper + actions/setup-java@v5 + chained jar builds | `.github/workflows/e2e-tests.yml` lines 17-32 verified | ✅ |
| P46 | Command | Git commit format: A23 convention `phase 5 task N: <description>`, subject-only, no Co-Authored-By body | Per Phase 4.1 T5 noted-and-dropped (handoff §3 documents); recent git log shows: `phase 5 brainstorming: catalog-service slice design spec...`, `applied 3 fixes from ...`, `phase 4.1 task 6: update Phase 4 retro RC table dispositions...` — all subject-only | ✅ |
| P47 | Command | Spotless/checkstyle/similar lint hook for services/* | `cat services/build.gradle` shows no spotless/checkstyle plugin block; no `apply plugin: 'checkstyle'` in subprojects; `cat services/organization-service/build.gradle` has no lint plugin either → no lint hook to satisfy; plan does NOT need spotless step | ✅ |
| P48 | Ordering | T1 gates T2-T11 per spec | Spec §5 row T1: "User approval gate before T2" | ✅ |
| P49 | Ordering | T2 bundles module + compose + CI + healthcheck per RC-2 | Phase 4.1 RC-2 codified rule: "compose-modifying task MUST be same commit or strictly prior to CI workflow update"; bundling at T2 satisfies same-commit | ✅ |
| P50 | Ordering | T3 (Liquibase shadow + tableExists precondition + empty body) does NOT require entities | Phase 4 pattern verified: shadow changelog has only the precondition + comment; no entity dependency | ✅ |
| P51 | Ordering | T4 (entities + repos) requires T3 changelogs (Liquibase runs at Boot startup with `ddl-auto=validate`) | Per organization-service application.yml: `liquibase.enabled: true` + `jpa.hibernate.ddl-auto: validate` — entities boot-validate against Liquibase-asserted schema | ✅ |
| P52 | Ordering | T5 (security) independent of T3+T4; runs after T2 | Security infra has no JPA dependency; can boot standalone with controllers from T7 | ✅ |
| P53 | Ordering | T6 (services + DTOs + caches + Synonym validator) depends on T4 (entities + repos) | Services invoke repository methods that return entities → DTO mapping requires entity classes | ✅ |
| P54 | Ordering | T7 (controllers + OpenAPI + JWT wiring) depends on T5 (security) + T6 (services/DTOs) | Controllers inject services; JWT filter wired via SecurityConfig from T5 | ✅ |
| P55 | Ordering | T8 (nginx routes) depends on T7 (endpoints live behind nginx) | Per Phase 4 nginx convention: routes added after controllers serve them | ✅ |
| P56 | Ordering | T9 (delete Grails + React URL migration) depends on T7+T8 (replacement endpoints live before deletion) | Per Phase 4 T8 pattern: delete only after replacements smoke-pass | ✅ |
| P57 | Ordering | T10 (TestContainers) depends on T7 (endpoints exist) + T3 (schema) | Integration test boots full Spring context including controllers + DB | ✅ |
| P58 | Ordering | T11 (Playwright) depends on T9 (React URL migration) + T8 (nginx routing) | Playwright hits nginx-proxied React-served URLs | ✅ |
| P59 | Ordering | T12 (done-gate) depends on T10+T11 (test re-runs) | Per Phase 4 T12 pattern | ✅ |
| P60 | Ordering | T13 (retro) is last | Standard | ✅ |
| P61 | Ordering | RC-2 satisfied via T2 bundling; T8 nginx-only is RC-2 N/A | Per `docs/process/plan-ordering-rules.md` Rule 1 + Rule 2 | ✅ |
| P62 | Ordering | T6 internal: caches created before services that use them | DI order at Spring context refresh; @Service classes depend on @Component cache beans | ✅ |
| P63 | Code-in-plan | Spring Boot 3.3.5 in `services/build.gradle` | `cat services/build.gradle`: `id 'org.springframework.boot' version '3.3.5' apply false` | ✅ |
| P64 | Code-in-plan | Java 21 toolchain via subprojects block | `cat services/build.gradle`: `subprojects { java { toolchain { languageVersion = JavaLanguageVersion.of(21) } } }` | ✅ |
| P65 | Code-in-plan | Liquibase XML schema 4.20 with `<changeSet id author>` + `<preConditions onFail="MARK_RAN" onFailMessage>` + `<tableExists tableName>` + `<comment>` valid | `cat services/organization-service/src/main/resources/db/changelog/changelog-shadow-create-party.xml` verified pattern | ✅ |
| P66 | Code-in-plan | Spring Security 6 `SecurityFilterChain` bean pattern with `.csrf().disable().sessionManagement(STATELESS).authorizeHttpRequests(...).addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class).exceptionHandling(401)` | `cat services/organization-service/src/main/java/.../security/SecurityConfig.java` verified pattern | ✅ |
| P67 | Code-in-plan | Spring Web `@RestController @RequestMapping @GetMapping @PostMapping @PathVariable @RequestParam` standard | Phase 4 organization-service controllers verified | ✅ |
| P68 | Code-in-plan | Spring Boot Actuator `/actuator/health` enabled via `management.endpoints.web.exposure.include=health,info` | `cat services/organization-service/src/main/resources/application.yml`: `management.endpoints.web.exposure.include: health,info` | ✅ |
| P69 | Code-in-plan | Docker compose v2 `expose: ["8085"]` + `healthcheck: {test, interval, timeout, retries, start_period}` syntax | `sed -n '60,100p' docker/docker-compose-base.yml` shows organization-service entry with this exact shape | ✅ |
| P70 | Code-in-plan | Playwright `test('...', async ({ request, page }) => {...})` syntax | `head -40 e2e/tests/organization-service.spec.ts` verified pattern | ✅ |
| P71 | Code-in-plan | TestContainers JUnit 5 `@Testcontainers @SpringBootTest @AutoConfigureMockMvc` with `MariaDBContainer<>("mariadb:10")` + `@DynamicPropertySource` + MockMvc | `head -100 services/organization-service/src/test/java/.../OrganizationServiceIntegrationTest.java` verified pattern | ✅ |
| P72 | Code-in-plan | JPA self-reference `@OneToMany(mappedBy="parentCategory") List<Category> categories` valid | Standard JPA 3.0 | ✅ |
| P73 | Code-in-plan | FD#7 caches: refresh-on-miss pattern per Phase 4 PartyTypeCache + Phase 3 RC-6 `getAll()`-refresh-on-empty fix | Phase 4 services/organization-service/src/main/java/.../service/PartyTypeCache.java pattern (referenced in spec FD#7 + Phase 4 retro RC-6 fix) | ✅ |
| P74 | Consumer impact | nginx app.conf appending new /api/<entity> blocks before /api/ catch-all does not break existing 8 blocks | `cat docker/nginx/conf.d/app.conf` shows clear block boundaries; insertion before /api/ catch-all preserves existing routing precedence | ✅ |
| P75 | Consumer impact | `services/settings.gradle` adding `include 'catalog-service'` does not break existing 4-service builds | Gradle multi-module: includes are additive; no shared state with other includes | ✅ |
| P76 | Consumer impact | `docker/docker-compose-base.yml` adding catalog-service entry does not break existing 7-service startup | Compose entries additive; no port collision (8085 free per P3) | ✅ |
| P77 | Consumer impact | `.github/workflows/e2e-tests.yml` extending jar list + probe loop does not break existing 5-service CI | CI changes additive; jar build step takes `:catalog-service:bootJar` added to existing list; probe loop adds one curl check | ✅ |
| P78 | Consumer impact | `src/js/api/urls.js` URL constant value change auto-propagates to importers (no API surface change) | React modules import constants by name; value changes pick up automatically; NEW constants get NEW importers in T9-touched files only | ✅ |
| P79 | Consumer impact | `src/js/utils/option-utils.jsx:281, :291` inline /api/* migration: callers fetch via this helper file → behavior unchanged at caller layer | `grep -rn "fetchProductsCategories\|fetchProductsTags" src/js/` would show callers; migration is endpoint-URL-only (no API contract change) | ✅ (caller-grep deferred to T9 execution; mechanical migration) |
| P80 | Consumer impact | Delete Grails *ApiController per T1 — Grails compiles + starts without deleted files | Grails artifact-discovery model: controllers register via Spring component scan + UrlMappings static routes; if no `*ApiController` reference in other Groovy + UrlMappings deletes matching routes, deletion is clean. Phase 4 T8 verified this pattern for OrganizationApiController deletion | ✅ |
| F11 | New finding (informational) | ProductGroup ↔ Product M:N via `product_group_product` (NOT product_tag); ProductGroup also has `siblings` mappedBy `productFamily` (Product has productFamily FK to ProductGroup) | `cat grails-app/domain/.../product/ProductGroup.groovy`: `hasMany = [products: Product, siblings: Product]`; `mappedBy = [products: "productGroups", siblings: "productFamily"]`; `joinTable: [name: 'product_group_product', column: 'product_id', key: 'product_group_id']` | ⚠️ Folded into T4 entity definition |
| F16 | Re-verification | FD#9 product_tag schema: NO UNIQUE KEY on (product_id, tag_id); tag_id NULLABLE — R3 fix STANDS | `sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW CREATE TABLE product_tag;"`: `product_id char(38) NOT NULL`, `tag_id char(38) DEFAULT NULL`, `KEY FKA71CAC4A9740C85F (tag_id)`, `KEY FKA71CAC4ADED5FAE7 (product_id)`, two FK CONSTRAINTs only, ENGINE=InnoDB. Empirical finding unchanged since CDR R3 | ✅ Spec FD#9 R3 fix unchanged |
| F20 | Investigation note | Category has `Boolean deleted = false` field declared but listed in `static transients = ["parents", "children", "deleted", "products"]` — appears to be a transient field, NOT persisted | `cat grails-app/domain/.../product/Category.groovy`: field at line 18, transients at line 26 | ⚠️ T4 entity port: do NOT include `deleted` as persisted JPA field (transient → omit) |

---

## Tasks

### Task 1: Empirical audit

**Files:**
- Read-only: `src/js/**/*.{js,jsx,ts,tsx}`, `grails-app/controllers/org/pih/warehouse/api/<catalog-area>*ApiController.groovy`, `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy`, `grails-app/services/org/pih/warehouse/`
- Output: `docs/audits/2026-MM-DD-phase-5-t1-audit-output.md` (committed to git; load-bearing for T2-T11)

This task is non-coding empirical research. Output is a structured audit doc that pins down per-entity write scope, per-controller delete/keep, ProductApiController action-level split, React URL migration list, cross-context findings, and FD#9 forced-decision-if-Tag-writes disposition. **User approval gate after Step 10 before T2 begins.**

- [ ] **Step 1: Expanded React enumeration — grep all `src/js/**/*.{js,jsx,ts,tsx}` for inline `/api/*` URL strings**

  ```bash
  grep -rnE "/api/[a-zA-Z][a-zA-Z0-9/_-]*" src/js/ --include="*.js" --include="*.jsx" --include="*.ts" --include="*.tsx" | grep -v node_modules
  ```

  Output table (one row per match):
  - File:line, inline URL, called from function/component, owning service (existing or to-migrate)

  Cross-reference each match against:
  - `src/js/api/urls.js` constants table (Step 2) — to detect duplication / inline-bypass cases
  - Catalog-area `*ApiController.groovy` action enumeration (Step 3) — to map URL → controller action

  Document explicitly in output: every catalog-related inline `/api/*` string + its current Grails controller action.

- [ ] **Step 2: Enumerate `src/js/api/urls.js` constants and their consumers**

  ```bash
  cat src/js/api/urls.js | grep -nE "^export const" | head -200
  grep -rln "CATEGORY\|TAG\|SYNONYM\|PRODUCT_TYPE\|ATTRIBUTE\|PRODUCT_GROUP\|UNIT_OF_MEASURE\|PRODUCT_API" src/js/ --include="*.js*" --include="*.ts*"
  ```

  Output table (one row per catalog-area constant):
  - Constant name, current URL value (PLURAL/SINGULAR per A14/F9), importers (file:line), target Phase 5 disposition (migrate-to-catalog / stay-Grails / delete)

- [ ] **Step 3: Enumerate every action of each catalog-area `*ApiController.groovy`**

  Inventory of catalog-area files (verified F10 finding):
  ```bash
  ls grails-app/controllers/org/pih/warehouse/api/ | grep -iE "product|category|tag|synonym|unitofmeasure|attribute|productgroup"
  ```
  Expected (10 files): `AttributeApiController.groovy`, `CategoryApiController.groovy`, `ProductApiController.groovy`, `ProductClassificationApiController.groovy`, `ProductPackageApiController.groovy`, `ProductSupplierApiController.groovy`, `ProductSupplierAttributeApiController.groovy`, `ProductSupplierPreferenceApiController.groovy`, `ProductsConfigurationApiController.groovy`, `UnitOfMeasureApiController.groovy`. (NB: NO TagApiController, SynonymApiController, ProductTypeApiController, ProductGroupApiController — their options routes are served by other controllers or via different action declarations.)

  For each `*ApiController.groovy`, enumerate every `def actionName(...)` method + every UrlMappings route hitting it. Output table:
  - Controller file, action name, HTTP method, URL pattern (from UrlMappings), reads-from (entity list), writes-to (entity list), cross-context dependencies (e.g., depends on InventoryLevel)

- [ ] **Step 4: Per-entity write-scope finalization**

  For each of the 4 default-GET-only entities (Category, Tag, Synonym, ProductGroup) and each variable-scope entity from Step 1+2+3, determine final write scope:
  - **GET-only**: no React POST/PUT/DELETE found via Step 1+2 enumeration
  - **POST+PUT+DELETE per verb**: React calls this verb for this entity (Step 2 constants + Step 1 inline confirms)

  Output table:
  - Entity, GET (always), POST, PUT, DELETE (each Y/N), evidence (caller file:line OR "no callers found")

- [ ] **Step 5: FD#12 ProductApiController action-level migration split**

  ProductApiController.groovy actions:
  - **Migrate to catalog-service**: basic Product reads not depending on inventory data
  - **Stay in Grails**: cross-context (demand, productSummary, productAvailability, search dependent on inventoryService)

  Read every action body in `grails-app/controllers/org/pih/warehouse/api/ProductApiController.groovy`:
  ```bash
  grep -nE "^\s*def\s+\w+\s*\(" grails-app/controllers/org/pih/warehouse/api/ProductApiController.groovy
  ```
  For each, classify by injected-service dependency (productService → migrate; inventoryService → stay; forecastingService → stay; productAvailabilityService → stay).

  Output table:
  - Action name, current URL, dependencies, disposition (catalog-service / Grails), target catalog-service `ProductController.<method>()` (if migrating)

- [ ] **Step 6: Per-controller delete/keep decision matrix**

  For each catalog-area `*ApiController.groovy` from Step 3, decide delete-or-keep based on Steps 4+5 + spec FDs:
  - **DEFINITIVELY KEEP** (spec-mandated): `ProductApiController` (FD#12), `ProductClassificationApiController` (CDR R1 §3.2), `ProductPackageApiController` (FD#5 deferred), `ProductSupplierApiController` + `ProductSupplierAttributeApiController` + `ProductSupplierPreferenceApiController` (FD#5 deferred), probably `ProductsConfigurationApiController` (cross-context configuration)
  - **DELETE candidate**: `AttributeApiController`, `CategoryApiController`, `UnitOfMeasureApiController` — IF Step 4 confirms 100% coverage by catalog-service + Step 3 enumeration confirms no cross-context callers

  Output table:
  - Controller file, decision (DELETE / KEEP), rationale, T9 disposition (delete-in-T9 / no-action)

- [ ] **Step 7: FD#9 forced-decision-if-Tag-writes protocol**

  IF Step 4 finds Tag has React POST/PUT/DELETE callers (i.e., write scope > GET-only):
  - Surface forced decision to user with 3 options per spec FD#9 (post-CDR R3):
    - (a) **Accept silent duplicates** — `product_tag` empirically has no UNIQUE constraint; concurrent Grails + catalog-service writes produce silent duplicates; lowest implementation cost
    - (b) **Escalate to Phase 5.5 or Phase 6** — defer Tag writes to a later phase where M:N concurrent-write coordination can be addressed (e.g., via saga, distributed lock, schema migration to add UNIQUE)
    - (c) **App-layer pair-uniqueness check in TagService pre-insert** — read existing pairs before insert; reject duplicate; race-y but reduces (does not eliminate) silent duplicate risk
  - Document user's choice in T1 output; T6 TagService implementation follows the chosen resolution; T10 integration test exercises the chosen behavior

  IF Step 4 confirms Tag is GET-only (default per spec FD#5): no forced decision; race cannot manifest in Phase 5; document explicitly in T1 output that this decision defers to whichever later phase first introduces catalog-side Tag writes.

- [ ] **Step 8: Cross-context atomic-write audit per parent §8 Step 1**

  ```bash
  grep -rnE "new (Product|Category|Tag|Synonym|UnitOfMeasure|ProductType|Attribute|ProductGroup)\s*\(" grails-app/services/ grails-app/controllers/
  grep -rln "productService\." grails-app/services/ grails-app/controllers/
  ```

  Document any cross-context Grails caller that creates catalog entities AS PART OF an atomic write that includes non-catalog entities (e.g., InventoryService.processData creating Product + InventoryItem in one transaction). These stay in Grails per FD#1; document explicit list in T1 output.

- [ ] **Step 9: Produce final outputs**

  Audit doc structure (one file):
  ```
  # Phase 5 T1 Audit Output

  ## Entity write-scope table
  [9 rows; columns: Entity, GET, POST, PUT, DELETE, Evidence]

  ## URL surface table
  [N rows; columns: URL pattern, HTTP method, Disposition, Target controller/action]

  ## React URL migration list
  [N rows; columns: src/js/... file:line, Current URL/import, New URL/import, T9 step]

  ## Cross-context findings
  [Bullets of cross-context atomic writes that stay Grails]

  ## FD#9 disposition
  [Either "Tag GET-only confirmed; no forced decision" OR "User chose option X: <description>"]

  ## Per-controller delete/keep decisions
  [10 rows; columns: Controller, DELETE/KEEP, Rationale, T9 action]
  ```

- [ ] **Step 10: User approval gate**

  Present T1 output to user. User must approve before T2 begins. Iterate on findings if user requests refinement.

- [ ] **Step 11: Commit T1 audit output**

  ```bash
  mkdir -p docs/audits
  git add docs/audits/2026-MM-DD-phase-5-t1-audit-output.md
  git commit -m "phase 5 task 1: empirical audit output (entity write scope, URL surface, FD#9 disposition, per-controller delete/keep)"
  ```

---

### Task 2: Module bootstrap + compose + CI + healthcheck (bundled per RC-2)

**Files:**
- Create: `services/catalog-service/build.gradle`
- Create: `services/catalog-service/src/main/java/org/openboxes/catalog/CatalogServiceApplication.java`
- Create: `services/catalog-service/src/main/resources/application.yml`
- Create: `services/catalog-service/Dockerfile`
- Modify: `services/settings.gradle` (add `include 'catalog-service'`)
- Modify: `docker/docker-compose-base.yml` (add catalog-service entry as 8th container)
- Modify: `.github/workflows/e2e-tests.yml` (add catalog-service jar build + probe + diagnostic dump)

Bundles compose change + CI workflow update in one commit per Phase 4.1 RC-2 codified rule (same-commit-as-compose option). Liquibase intentionally disabled at this stage (T3 enables).

- [ ] **Step 1: Create `services/catalog-service/build.gradle`** (per organization-service template)

  ```gradle
  ext['testcontainers.version'] = '1.21.3'

  dependencies {
      implementation 'org.springframework.boot:spring-boot-starter-web'
      implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
      implementation 'org.springframework.boot:spring-boot-starter-security'
      implementation 'org.springframework.boot:spring-boot-starter-validation'
      implementation 'org.springframework.boot:spring-boot-starter-actuator'
      implementation 'org.liquibase:liquibase-core'
      implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'
      implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
      runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
      runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
      runtimeOnly 'org.mariadb.jdbc:mariadb-java-client:3.4.1'

      testImplementation 'org.springframework.boot:spring-boot-starter-test'
      testImplementation 'org.springframework.security:spring-security-test'
      testImplementation 'org.testcontainers:junit-jupiter:1.21.3'
      testImplementation 'org.testcontainers:mariadb:1.21.3'
  }

  test {
      useJUnitPlatform()
      systemProperty 'api.version', '1.44'
      systemProperty 'testcontainers.ryuk.disabled', 'true'
  }
  ```

- [ ] **Step 2: Add `include 'catalog-service'` to `services/settings.gradle`**

  Final content:
  ```gradle
  rootProject.name = 'openboxes-services'
  include 'document-service'
  include 'identity-service'
  include 'location-service'
  include 'organization-service'
  include 'catalog-service'
  ```

- [ ] **Step 3: Create main class `CatalogServiceApplication.java`**

  ```java
  package org.openboxes.catalog;

  import org.springframework.boot.SpringApplication;
  import org.springframework.boot.autoconfigure.SpringBootApplication;

  @SpringBootApplication
  public class CatalogServiceApplication {
      public static void main(String[] args) {
          SpringApplication.run(CatalogServiceApplication.class, args);
      }
  }
  ```

- [ ] **Step 4: Create `application.yml`** — Liquibase intentionally disabled (T3 enables); JPA validate

  ```yaml
  server:
    port: 8085
  spring:
    application:
      name: catalog-service
    datasource:
      url: ${DATASOURCE_URL}
      username: ${DATASOURCE_USERNAME}
      password: ${DATASOURCE_PASSWORD}
      driver-class-name: org.mariadb.jdbc.Driver
    jpa:
      hibernate:
        ddl-auto: validate
      properties:
        hibernate.dialect: org.hibernate.dialect.MariaDBDialect
    liquibase:
      enabled: false  # T3 enables once shadow changelogs exist
      change-log: classpath:db/changelog/db.changelog-master.xml
  management:
    endpoints:
      web:
        exposure:
          include: health,info
  openboxes:
    jwt:
      secret: ${OPENBOXES_JWT_SECRET}
  ```

- [ ] **Step 5: Create `Dockerfile`** (per organization-service pattern)

  ```dockerfile
  FROM eclipse-temurin:21-jre-alpine
  RUN apk add --no-cache curl
  WORKDIR /app
  COPY build/libs/catalog-service-*.jar /app/app.jar
  EXPOSE 8085
  ENTRYPOINT ["java", "-jar", "/app/app.jar"]
  ```

- [ ] **Step 6: Add catalog-service entry to `docker/docker-compose-base.yml`**

  Append after the organization-service block (at end of services list, before any volumes/networks sections):

  ```yaml
      catalog-service:
        build:
          context: ../services/catalog-service
          dockerfile: Dockerfile
        container_name: openboxes-catalog-service
        expose:
          - "8085"
        environment:
          DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
          DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
          DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
          OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
        healthcheck:
          test: "curl --fail --silent localhost:8085/actuator/health | grep '\"status\":\"UP\"' || exit 1"
          interval: 10s
          timeout: 5s
          retries: 5
          start_period: 30s
  ```

- [ ] **Step 7: Update `.github/workflows/e2e-tests.yml`** — add `:catalog-service:bootJar` to gradle build; add curl probe; add diagnostic dump

  Modify "Build identity-service + document-service jars" step:
  ```yaml
        run: ./gradlew :identity-service:bootJar :document-service:bootJar :location-service:bootJar :organization-service:bootJar :catalog-service:bootJar
  ```

  Modify "Boot docker compose stack" probe loop — add the catalog-service health check inline with existing chain:
  ```yaml
              && docker exec openboxes-organization-service curl -sf localhost:8084/actuator/health \
              && docker exec openboxes-catalog-service curl -sf localhost:8085/actuator/health \
              && [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost/api/documents)" != "502" ] \
  ```

  Modify "Diagnostic — dump container state + logs" step — add catalog-service to log dump chain:
  ```yaml
            echo "---catalog-service---" && docker logs openboxes-catalog-service 2>&1 | tail -100 || true
  ```

- [ ] **Step 8: Smoke startup** — build and verify health

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  for i in {1..30}; do sudo docker exec openboxes-catalog-service curl -sf localhost:8085/actuator/health && break; sleep 5; done
  sudo docker exec openboxes-catalog-service curl -s localhost:8085/actuator/health | grep '"status":"UP"' && echo "T2 health OK"
  ```

- [ ] **Step 9: Commit (single, bundling all changes per RC-2)**

  ```bash
  git add services/catalog-service/build.gradle services/catalog-service/src/main/java/org/openboxes/catalog/CatalogServiceApplication.java services/catalog-service/src/main/resources/application.yml services/catalog-service/Dockerfile services/settings.gradle docker/docker-compose-base.yml .github/workflows/e2e-tests.yml
  git commit -m "phase 5 task 2: catalog-service module bootstrap + compose entry + CI workflow update + healthcheck (RC-2 bundled)"
  ```

---

### Task 3: Liquibase shadow changelogs

**Files:**
- Create: `services/catalog-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: 11 `db/changelog/changelog-shadow-create-<entity>.xml` files (9 entity tables + 2 join tables: `product_tag`, `product_group_product`)
- Modify: `services/catalog-service/src/main/resources/application.yml` (set `liquibase.enabled: true`)

Phase 4 pattern: each shadow has `tableExists` precondition + `<comment>` + no body. Grails Liquibase owns table creation; catalog-service uses `spring.jpa.hibernate.ddl-auto=validate` to prove entity-mapping correctness at boot.

- [ ] **Step 1: Create `db.changelog-master.xml`**

  ```xml
  <?xml version="1.0" encoding="UTF-8"?>
  <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                         https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
      <include file="db/changelog/changelog-shadow-create-product.xml"/>
      <include file="db/changelog/changelog-shadow-create-category.xml"/>
      <include file="db/changelog/changelog-shadow-create-unit-of-measure.xml"/>
      <include file="db/changelog/changelog-shadow-create-unit-of-measure-class.xml"/>
      <include file="db/changelog/changelog-shadow-create-tag.xml"/>
      <include file="db/changelog/changelog-shadow-create-synonym.xml"/>
      <include file="db/changelog/changelog-shadow-create-product-type.xml"/>
      <include file="db/changelog/changelog-shadow-create-attribute.xml"/>
      <include file="db/changelog/changelog-shadow-create-product-group.xml"/>
      <include file="db/changelog/changelog-shadow-create-product-tag.xml"/>
      <include file="db/changelog/changelog-shadow-create-product-group-product.xml"/>
  </databaseChangeLog>
  ```

- [ ] **Step 2: Create 9 entity-table shadow changelogs** (one file per table; pattern is mechanical)

  Template (substitute `<entity>` and `<table>` per file):
  ```xml
  <?xml version="1.1" encoding="UTF-8"?>
  <databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                     xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                     xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                         https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                     logicalFilePath="services/catalog-service/db/changelog/changelog-shadow-create-<entity>.xml">
      <changeSet id="phase5-shadow-create-<entity>" author="openboxes-catalog">
          <preConditions onFail="MARK_RAN" onFailMessage="<table> table not found — Grails Liquibase must run first">
              <tableExists tableName="<table>"/>
          </preConditions>
          <comment>
              Shadow for <table> table. Grails Liquibase owns table creation.
              catalog-service uses spring.jpa.hibernate.ddl-auto=validate to prove entity-mapping correctness.
          </comment>
          <!-- No body: table already exists per the precondition. -->
      </changeSet>
  </databaseChangeLog>
  ```

  Per-file substitutions:
  - `changelog-shadow-create-product.xml` → entity=`product`, table=`product`
  - `changelog-shadow-create-category.xml` → entity=`category`, table=`category`
  - `changelog-shadow-create-unit-of-measure.xml` → entity=`unit-of-measure`, table=`unit_of_measure`
  - `changelog-shadow-create-unit-of-measure-class.xml` → entity=`unit-of-measure-class`, table=`unit_of_measure_class`
  - `changelog-shadow-create-tag.xml` → entity=`tag`, table=`tag`
  - `changelog-shadow-create-synonym.xml` → entity=`synonym`, table=`synonym`
  - `changelog-shadow-create-product-type.xml` → entity=`product-type`, table=`product_type`
  - `changelog-shadow-create-attribute.xml` → entity=`attribute`, table=`attribute`
  - `changelog-shadow-create-product-group.xml` → entity=`product-group`, table=`product_group`

- [ ] **Step 3: Create 2 join-table shadow changelogs** (`product_tag` + `product_group_product`)

  Use same template, with comment adjusted to note M:N join-table purpose. For `product_tag`:
  ```xml
      <comment>
          Shadow for product_tag M:N join table (Tag↔Product per FD#9).
          Empirically NO unique constraint on (product_id, tag_id) per CDR R3; concurrent writes from Grails + catalog-service produce silent duplicate rows if Tag writes are in scope per T1 audit.
      </comment>
  ```

  For `product_group_product`: standard M:N comment.

- [ ] **Step 4: Enable Liquibase in `application.yml`**

  Change `liquibase.enabled: false` → `liquibase.enabled: true`.

- [ ] **Step 5: Smoke startup** — verify Liquibase changelogs all MARK_RAN (precondition pass)

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  sleep 15
  sudo docker logs openboxes-catalog-service 2>&1 | grep -iE "liquibase|changeset|shadow" | head -30
  # Expect: each shadow changelog MARK_RAN (precondition matched table); no errors
  sudo docker exec openboxes-catalog-service curl -s localhost:8085/actuator/health | grep '"status":"UP"' && echo "T3 health OK"
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add services/catalog-service/src/main/resources/db/changelog/ services/catalog-service/src/main/resources/application.yml
  git commit -m "phase 5 task 3: Liquibase shadow changelogs for 9 catalog tables + 2 join tables (product_tag, product_group_product)"
  ```

---

### Task 4: JPA entities + repositories (9 entities + 9 repos)

**Files:**
- Create: 9 `services/catalog-service/src/main/java/org/openboxes/catalog/entity/<Entity>.java` files
- Create: 9 `services/catalog-service/src/main/java/org/openboxes/catalog/repository/<Entity>Repository.java` files

Flat entities per FD#2 (no @Inheritance); follow Grails domain field shapes verified during plan-write; respect transients per F20 (Category `deleted` is NOT persisted).

- [ ] **Step 1: Create `Product.java`** — R/O per FD#1; FKs to UnitOfMeasure, ProductType, Category; M:N to Tag (owning side per FD#9); @OneToMany Synonym; M:N to ProductGroup; FK productFamily to ProductGroup (per F11)

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.math.BigDecimal;
  import java.time.Instant;
  import java.util.HashSet;
  import java.util.List;
  import java.util.Set;

  // READ-ONLY per FD#1 (no setter methods exposed); R/O enforcement is via ProductService
  // being @Transactional(readOnly = true). @Immutable is intentionally NOT applied — it would
  // suppress owned-collection writes on product_tag (Tag M:N owning side per FD#9), breaking
  // TagService writes when T1 option (c) is selected (CIR R1 §2.1).
  @Entity
  @Table(name = "product")
  public class Product {
      @Id
      @Column(length = 38)
      private String id;

      @Column(nullable = false)
      private String name;
      private String description;
      @Column(name = "product_code")
      private String productCode;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "product_type_id")
      private ProductType productType;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "category_id")
      private Category category;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "unit_of_measure_id")
      private UnitOfMeasure unitOfMeasure;

      @Column(name = "price_per_unit")
      private BigDecimal pricePerUnit;
      @Column(name = "cost_per_unit")
      private BigDecimal costPerUnit;
      private Boolean active;

      // FD#9: Product owns the M:N relationship to Tag via product_tag join table
      @ManyToMany(fetch = FetchType.LAZY)
      @JoinTable(
          name = "product_tag",
          joinColumns = @JoinColumn(name = "product_id"),
          inverseJoinColumns = @JoinColumn(name = "tag_id")
      )
      private Set<Tag> tags = new HashSet<>();

      @OneToMany(mappedBy = "product", fetch = FetchType.LAZY)
      private List<Synonym> synonyms;

      // M:N to ProductGroup via product_group_product (per F11)
      @ManyToMany(fetch = FetchType.LAZY)
      @JoinTable(
          name = "product_group_product",
          joinColumns = @JoinColumn(name = "product_id"),
          inverseJoinColumns = @JoinColumn(name = "product_group_id")
      )
      private Set<ProductGroup> productGroups = new HashSet<>();

      // Product has FK productFamily to ProductGroup (per F11; mappedBy "productFamily" in ProductGroup.siblings)
      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "product_family_id")
      private ProductGroup productFamily;

      @Column(name = "date_created")
      private Instant dateCreated;
      @Column(name = "last_updated")
      private Instant lastUpdated;

      protected Product() {}

      // getters only (R/O entity)
      public String getId() { return id; }
      public String getName() { return name; }
      public String getDescription() { return description; }
      public String getProductCode() { return productCode; }
      public ProductType getProductType() { return productType; }
      public Category getCategory() { return category; }
      public UnitOfMeasure getUnitOfMeasure() { return unitOfMeasure; }
      public BigDecimal getPricePerUnit() { return pricePerUnit; }
      public BigDecimal getCostPerUnit() { return costPerUnit; }
      public Boolean getActive() { return active; }
      public Set<Tag> getTags() { return tags; }
      public List<Synonym> getSynonyms() { return synonyms; }
      public Set<ProductGroup> getProductGroups() { return productGroups; }
      public ProductGroup getProductFamily() { return productFamily; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 2: Create `Category.java`** — self-FK tree; GlAccount FK; @Cacheable for L2 per FD#7; F20: `deleted` field is transient → omit

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import org.hibernate.annotations.Cache;
  import org.hibernate.annotations.CacheConcurrencyStrategy;
  import java.time.Instant;
  import java.util.List;

  @Entity
  @Cacheable
  @Cache(usage = CacheConcurrencyStrategy.READ_WRITE)
  @Table(name = "category")
  public class Category {
      @Id
      @Column(length = 38)
      private String id;

      @Column(nullable = false)
      private String name;
      private String description;
      @Column(name = "sort_order")
      private Integer sortOrder = 0;
      @Column(name = "is_root")
      private Boolean isRoot = false;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "parent_category_id")
      private Category parentCategory;

      @OneToMany(mappedBy = "parentCategory", fetch = FetchType.LAZY)
      private List<Category> categories;

      // Note: GlAccount entity not yet ported (catalog-service does not depend on GlAccount entity);
      // exposed as flat FK string at DTO level. Plan-time decision: store as raw String FK.
      @Column(name = "gl_account_id", length = 38)
      private String glAccountId;

      @Column(name = "date_created")
      private Instant dateCreated;
      @Column(name = "last_updated")
      private Instant lastUpdated;

      // F20: Category.deleted is in Grails transients; NOT persisted; do NOT include here

      protected Category() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public String getDescription() { return description; }
      public Integer getSortOrder() { return sortOrder; }
      public Boolean getIsRoot() { return isRoot; }
      public Category getParentCategory() { return parentCategory; }
      public List<Category> getCategories() { return categories; }
      public String getGlAccountId() { return glAccountId; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
      // setters added when CategoryService write path is implemented per T1 scope
  }
  ```

- [ ] **Step 3: Create `UnitOfMeasure.java`** — FK to UoMClass per FD#11

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;

  @Entity
  @Table(name = "unit_of_measure")
  public class UnitOfMeasure {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false) private String name;
      @Column(nullable = false, unique = true) private String code;
      private String description;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "uom_class_id")  // nullable per FD#11
      private UnitOfMeasureClass uomClass;

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected UnitOfMeasure() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public String getCode() { return code; }
      public String getDescription() { return description; }
      public UnitOfMeasureClass getUomClass() { return uomClass; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 4: Create `UnitOfMeasureClass.java`** — bidirectional FK to UoM per FD#11 (both FKs nullable; cycle in object graph only)

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.List;

  @Entity
  @Table(name = "unit_of_measure_class")
  public class UnitOfMeasureClass {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false) private String name;
      @Column(nullable = false, unique = true) private String code;
      private String description;
      private Boolean active;

      @Column(name = "type", nullable = false)
      private String type;  // UnitOfMeasureType enum stored as String

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "base_uom_id")  // nullable per FD#11
      private UnitOfMeasure baseUom;

      @OneToMany(mappedBy = "uomClass", fetch = FetchType.LAZY)
      private List<UnitOfMeasure> uoms;

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected UnitOfMeasureClass() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public String getCode() { return code; }
      public String getDescription() { return description; }
      public Boolean getActive() { return active; }
      public String getType() { return type; }
      public UnitOfMeasure getBaseUom() { return baseUom; }
      public List<UnitOfMeasure> getUoms() { return uoms; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 5: Create `Tag.java`** — inverse side of M:N per FD#9 (`mappedBy="tags"`)

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.HashSet;
  import java.util.Set;

  @Entity
  @Table(name = "tag")
  public class Tag {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false) private String tag;
      @Column(name = "is_active") private Boolean isActive = true;

      // FD#9: Tag is inverse side; Product owns the M:N
      @ManyToMany(mappedBy = "tags", fetch = FetchType.LAZY)
      private Set<Product> products = new HashSet<>();

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected Tag() {}

      public String getId() { return id; }
      public String getTag() { return tag; }
      public Boolean getIsActive() { return isActive; }
      public Set<Product> getProducts() { return products; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }

      // Setters added if T1 audit confirms Tag write scope > GET-only
  }
  ```

- [ ] **Step 6: Create `Synonym.java`** — FK to Product; validator NOT in entity per FD#10 (moves to service in T6)

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.Locale;

  @Entity
  @Table(name = "synonym")
  public class Synonym {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false) private String name;

      @Column(nullable = false)
      private Locale locale;

      @Column(name = "synonym_type_code", nullable = false)
      private String synonymTypeCode;  // SynonymTypeCode enum stored as String

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "product_id", nullable = false)
      private Product product;

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected Synonym() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public Locale getLocale() { return locale; }
      public String getSynonymTypeCode() { return synonymTypeCode; }
      public Product getProduct() { return product; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 7: Create `ProductType.java`** — flat reference; @ElementCollection for enum lists

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.List;

  @Entity
  @Table(name = "product_type")
  public class ProductType {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false, unique = true) private String name;
      @Column(unique = true) private String code;

      @Column(name = "product_type_code", nullable = false)
      private String productTypeCode;  // ProductTypeCode enum stored as String

      @Column(name = "product_identifier_format")
      private String productIdentifierFormat;
      @Column(name = "sequence_number")
      private Integer sequenceNumber = 0;

      @ElementCollection
      @CollectionTable(name = "product_type_supported_activities", joinColumns = @JoinColumn(name = "product_type_id"))
      @Column(name = "activity_code")
      private List<String> supportedActivities;

      @ElementCollection
      @CollectionTable(name = "product_type_required_fields", joinColumns = @JoinColumn(name = "product_type_id"))
      @Column(name = "field_code")
      private List<String> requiredFields;

      @ElementCollection
      @CollectionTable(name = "product_type_displayed_fields", joinColumns = @JoinColumn(name = "product_type_id"))
      @Column(name = "field_code")
      private List<String> displayedFields;

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected ProductType() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public String getCode() { return code; }
      public String getProductTypeCode() { return productTypeCode; }
      public String getProductIdentifierFormat() { return productIdentifierFormat; }
      public Integer getSequenceNumber() { return sequenceNumber; }
      public List<String> getSupportedActivities() { return supportedActivities; }
      public List<String> getRequiredFields() { return requiredFields; }
      public List<String> getDisplayedFields() { return displayedFields; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

  **Note:** Element-collection table names (`product_type_supported_activities`, etc.) must match Grails' GORM-generated join-table names. If T3 Liquibase boot-validation flags a mismatch, the actual Grails table names need to be queried via `SHOW TABLES LIKE 'product_type_%';` and the entity column-name + table-name attributes adjusted to match.

- [ ] **Step 8: Create `Attribute.java`** — FK to UoMClass; @ElementCollection options + entityTypeCodes

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.List;

  @Entity
  @Table(name = "attribute")
  public class Attribute {
      @Id @Column(length = 38) private String id;
      private String code;
      @Column(nullable = false) private String name;
      private String description;

      private Boolean active = true;
      private Boolean exportable = true;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "unit_of_measure_class_id")  // nullable
      private UnitOfMeasureClass unitOfMeasureClass;

      @ElementCollection
      @CollectionTable(name = "attribute_options", joinColumns = @JoinColumn(name = "attribute_id"))
      @Column(name = "option_value")
      private List<String> options;

      @Column(name = "default_value")
      private String defaultValue;
      private Boolean required = false;
      @Column(name = "allow_other")
      private Boolean allowOther;
      @Column(name = "allow_multiple")
      private Boolean allowMultiple = false;

      @ElementCollection
      @CollectionTable(name = "attribute_entity_type_codes", joinColumns = @JoinColumn(name = "attribute_id"))
      @Column(name = "entity_type_code")
      private List<String> entityTypeCodes;

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected Attribute() {}

      public String getId() { return id; }
      public String getCode() { return code; }
      public String getName() { return name; }
      public String getDescription() { return description; }
      public Boolean getActive() { return active; }
      public Boolean getExportable() { return exportable; }
      public UnitOfMeasureClass getUnitOfMeasureClass() { return unitOfMeasureClass; }
      public List<String> getOptions() { return options; }
      public String getDefaultValue() { return defaultValue; }
      public Boolean getRequired() { return required; }
      public Boolean getAllowOther() { return allowOther; }
      public Boolean getAllowMultiple() { return allowMultiple; }
      public List<String> getEntityTypeCodes() { return entityTypeCodes; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 9: Create `ProductGroup.java`** — FK Category; M:N Products via `product_group_product` + siblings (inverse of Product.productFamily)

  ```java
  package org.openboxes.catalog.entity;

  import jakarta.persistence.*;
  import java.time.Instant;
  import java.util.HashSet;
  import java.util.Set;

  @Entity
  @Table(name = "product_group")
  public class ProductGroup {
      @Id @Column(length = 38) private String id;
      @Column(nullable = false, unique = true) private String name;
      private String description;

      @ManyToOne(fetch = FetchType.LAZY)
      @JoinColumn(name = "category_id")  // nullable per ProductGroup.groovy `ignoreNotFound: true`
      private Category category;

      // M:N inverse side (Product owns via product_group_product)
      @ManyToMany(mappedBy = "productGroups", fetch = FetchType.LAZY)
      private Set<Product> products = new HashSet<>();

      // siblings: inverse of Product.productFamily (per F11)
      @OneToMany(mappedBy = "productFamily", fetch = FetchType.LAZY)
      private Set<Product> siblings = new HashSet<>();

      @Column(name = "date_created") private Instant dateCreated;
      @Column(name = "last_updated") private Instant lastUpdated;

      protected ProductGroup() {}

      public String getId() { return id; }
      public String getName() { return name; }
      public String getDescription() { return description; }
      public Category getCategory() { return category; }
      public Set<Product> getProducts() { return products; }
      public Set<Product> getSiblings() { return siblings; }
      public Instant getDateCreated() { return dateCreated; }
      public Instant getLastUpdated() { return lastUpdated; }
  }
  ```

- [ ] **Step 10: Create 9 `<Entity>Repository.java` files** (mechanical — JpaRepository<Entity, String>)

  Template:
  ```java
  package org.openboxes.catalog.repository;

  import org.openboxes.catalog.entity.<Entity>;
  import org.springframework.data.jpa.repository.JpaRepository;

  public interface <Entity>Repository extends JpaRepository<<Entity>, String> {
      // additional query methods added per service needs in T6
  }
  ```

  Files: `ProductRepository`, `CategoryRepository`, `UnitOfMeasureRepository`, `UnitOfMeasureClassRepository`, `TagRepository`, `SynonymRepository`, `ProductTypeRepository`, `AttributeRepository`, `ProductGroupRepository`.

  Add custom query methods to `SynonymRepository` (needed for FD#10 validator in T6):
  ```java
  public interface SynonymRepository extends JpaRepository<Synonym, String> {
      long countByProductIdAndLocaleAndSynonymTypeCode(String productId, java.util.Locale locale, String synonymTypeCode);
  }
  ```

  Add to `CategoryRepository`:
  ```java
  public interface CategoryRepository extends JpaRepository<Category, String> {
      java.util.List<Category> findByParentCategoryIsNull();  // root categories
  }
  ```

- [ ] **Step 11: Smoke startup with Hibernate ddl-auto=validate**

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  sleep 20
  sudo docker logs openboxes-catalog-service 2>&1 | grep -iE "hibernate|validation|error|exception" | head -50
  sudo docker exec openboxes-catalog-service curl -s localhost:8085/actuator/health | grep '"status":"UP"' && echo "T4 health OK; all 9 entities validated against existing schema"
  ```

  **Critical:** If `ddl-auto=validate` fails for any entity (column mismatch, table mismatch, FK mismatch), fix the entity JPA mapping to match the real schema. Common fixes: column name mismatch (`@Column(name=...)`), nullable mismatch, enum stored as int vs String, element-collection table-name mismatch with Grails-generated names.

- [ ] **Step 12: Commit**

  ```bash
  git add services/catalog-service/src/main/java/org/openboxes/catalog/entity/ services/catalog-service/src/main/java/org/openboxes/catalog/repository/
  git commit -m "phase 5 task 4: 9 JPA entities (flat per FD#2; Tag↔Product M:N owning Product per FD#9; Category self-FK; UoM↔UoMClass bidir per FD#11) + 9 Spring Data repositories"
  ```

---

### Task 5: Security (5th JWT copy)

**Files:**
- Create: `services/catalog-service/src/main/java/org/openboxes/catalog/security/JwtCookieAuthFilter.java`
- Create: `services/catalog-service/src/main/java/org/openboxes/catalog/security/JwtService.java`
- Create: `services/catalog-service/src/main/java/org/openboxes/catalog/security/SecurityConfig.java`

5th verbatim copy of organization-service's security/ trio per FD#6 (no `jwt-auth-common` extraction — Phase 5.1).

- [ ] **Step 1: Copy 3 files from organization-service**

  ```bash
  mkdir -p services/catalog-service/src/main/java/org/openboxes/catalog/security
  cp services/organization-service/src/main/java/org/openboxes/organization/security/JwtCookieAuthFilter.java services/catalog-service/src/main/java/org/openboxes/catalog/security/
  cp services/organization-service/src/main/java/org/openboxes/organization/security/JwtService.java services/catalog-service/src/main/java/org/openboxes/catalog/security/
  cp services/organization-service/src/main/java/org/openboxes/organization/security/SecurityConfig.java services/catalog-service/src/main/java/org/openboxes/catalog/security/
  ```

- [ ] **Step 2: Package rename**

  In each of the 3 files, replace `package org.openboxes.organization.security;` → `package org.openboxes.catalog.security;`. No other source changes required.

- [ ] **Step 3: Smoke startup; verify SecurityFilterChain registered + /actuator/health permitted (per SecurityConfig permitAll list)**

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  sleep 15
  # health endpoint should be accessible (permitAll)
  sudo docker exec openboxes-catalog-service curl -s localhost:8085/actuator/health | grep '"status":"UP"' && echo "T5 health OK"
  # arbitrary endpoint should return 401 without JWT (no controllers exist yet but the filter chain is active)
  curl -s -o /dev/null -w "%{http_code}" http://localhost:8085/anyendpoint
  # expect: 401
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add services/catalog-service/src/main/java/org/openboxes/catalog/security/
  git commit -m "phase 5 task 5: 5th JWT triple copy from organization-service per FD#6 (jwt-auth-common deferred to Phase 5.1)"
  ```

---

### Task 6: Services + DTOs + caches + Synonym validator port

**Files:**
- Create: 9 `<Entity>Dto.java` files (flat FK-only per FD#3)
- Create: 8 `<Entity>Service.java` files (no separate UoMClassService; UoMService handles both)
- Create: 4 `<Entity>Cache.java` files per FD#7

Entity → DTO mapping uses static `from()` methods (no MapStruct/ModelMapper to stay aligned with Phase 4 PartyService pattern + ruthless YAGNI). Synonym validator-as-service per FD#10. T1 audit Step 7 result determines whether TagService implements forced-decision protocol.

- [ ] **Step 1: Create 9 DTO classes** (record classes per Java 21 + Phase 4 pattern; flat FK-only per FD#3)

  Example (others follow the same shape):

  ```java
  package org.openboxes.catalog.dto;

  import java.math.BigDecimal;
  import java.util.Set;

  public record ProductDto(
      String id,
      String name,
      String description,
      String productCode,
      String productTypeId,
      String categoryId,
      String unitOfMeasureId,
      BigDecimal pricePerUnit,
      BigDecimal costPerUnit,
      Boolean active,
      Set<String> tagIds,
      Set<String> synonymIds,
      Set<String> productGroupIds,
      String productFamilyId
  ) {
      public static ProductDto from(org.openboxes.catalog.entity.Product p) {
          return new ProductDto(
              p.getId(),
              p.getName(),
              p.getDescription(),
              p.getProductCode(),
              p.getProductType() == null ? null : p.getProductType().getId(),
              p.getCategory() == null ? null : p.getCategory().getId(),
              p.getUnitOfMeasure() == null ? null : p.getUnitOfMeasure().getId(),
              p.getPricePerUnit(),
              p.getCostPerUnit(),
              p.getActive(),
              p.getTags().stream().map(t -> t.getId()).collect(java.util.stream.Collectors.toSet()),
              p.getSynonyms() == null ? java.util.Set.of() : p.getSynonyms().stream().map(s -> s.getId()).collect(java.util.stream.Collectors.toSet()),
              p.getProductGroups().stream().map(g -> g.getId()).collect(java.util.stream.Collectors.toSet()),
              p.getProductFamily() == null ? null : p.getProductFamily().getId()
          );
      }
  }
  ```

  Remaining 8 DTOs follow same shape per spec FD#3 field list:
  - `CategoryDto(id, name, description, parentCategoryId, sortOrder, isRoot, glAccountId)`
  - `UnitOfMeasureDto(id, name, code, description, uomClassId)`
  - `UnitOfMeasureClassDto(id, name, code, description, active, type, baseUomId)`
  - `TagDto(id, tag, isActive, productIds)`
  - `SynonymDto(id, productId, name, locale, synonymTypeCode)`
  - `ProductTypeDto(id, name, code, productTypeCode, productIdentifierFormat, sequenceNumber, supportedActivities, requiredFields, displayedFields)`
  - `AttributeDto(id, code, name, description, active, exportable, unitOfMeasureClassId, options, defaultValue, required, allowOther, allowMultiple, entityTypeCodes)`
  - `ProductGroupDto(id, name, description, categoryId, productIds, siblingIds)`

- [ ] **Step 2: Create 4 cache classes per FD#7**

  Refresh-on-miss pattern (Phase 4 PartyTypeCache + Phase 3 RC-6 fix). Example for `UnitOfMeasureCache`:

  ```java
  package org.openboxes.catalog.cache;

  import org.openboxes.catalog.entity.UnitOfMeasure;
  import org.openboxes.catalog.repository.UnitOfMeasureRepository;
  import org.springframework.stereotype.Component;

  import java.util.List;
  import java.util.Map;
  import java.util.Optional;
  import java.util.concurrent.ConcurrentHashMap;

  @Component
  public class UnitOfMeasureCache {
      private final UnitOfMeasureRepository repo;
      private final Map<String, UnitOfMeasure> byId = new ConcurrentHashMap<>();
      private volatile boolean loaded = false;

      public UnitOfMeasureCache(UnitOfMeasureRepository repo) {
          this.repo = repo;
      }

      public Optional<UnitOfMeasure> get(String id) {
          if (!loaded) refresh();
          UnitOfMeasure cached = byId.get(id);
          if (cached == null) {
              // refresh-on-miss for individual ID
              repo.findById(id).ifPresent(u -> byId.put(u.getId(), u));
              return Optional.ofNullable(byId.get(id));
          }
          return Optional.of(cached);
      }

      public List<UnitOfMeasure> getAll() {
          if (!loaded || byId.isEmpty()) refresh();  // Phase 3 RC-6 fix: refresh on empty
          return List.copyOf(byId.values());
      }

      private synchronized void refresh() {
          byId.clear();
          repo.findAll().forEach(u -> byId.put(u.getId(), u));
          loaded = true;
      }
  }
  ```

  Same pattern for `ProductTypeCache`, `AttributeCache`. `CategoryCache` uses refresh-on-write (call `refresh()` after CategoryService write methods).

- [ ] **Step 3: Create 8 services**

  **`ProductService.java`** — R/O per FD#1:

  ```java
  package org.openboxes.catalog.service;

  import org.openboxes.catalog.dto.ProductDto;
  import org.openboxes.catalog.repository.ProductRepository;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;

  import java.util.List;
  import java.util.Optional;

  @Service
  @Transactional(readOnly = true)
  public class ProductService {
      private final ProductRepository repo;
      public ProductService(ProductRepository repo) { this.repo = repo; }

      public Optional<ProductDto> get(String id) {
          return repo.findById(id).map(ProductDto::from);
      }

      public List<ProductDto> list() {
          return repo.findAll().stream().map(ProductDto::from).toList();
      }
  }
  ```

  **`SynonymService.java`** — service-layer validator per FD#10:

  ```java
  package org.openboxes.catalog.service;

  import org.openboxes.catalog.dto.SynonymDto;
  import org.openboxes.catalog.entity.Synonym;
  import org.openboxes.catalog.repository.ProductRepository;
  import org.openboxes.catalog.repository.SynonymRepository;
  import org.springframework.stereotype.Service;
  import org.springframework.transaction.annotation.Transactional;
  import org.springframework.web.server.ResponseStatusException;
  import org.springframework.http.HttpStatus;

  import java.util.List;
  import java.util.Optional;

  @Service
  public class SynonymService {
      private final SynonymRepository repo;
      private final ProductRepository productRepo;

      public SynonymService(SynonymRepository repo, ProductRepository productRepo) {
          this.repo = repo;
          this.productRepo = productRepo;
      }

      @Transactional(readOnly = true)
      public Optional<SynonymDto> get(String id) {
          return repo.findById(id).map(SynonymService::toDto);
      }

      @Transactional(readOnly = true)
      public List<SynonymDto> list() {
          return repo.findAll().stream().map(SynonymService::toDto).toList();
      }

      // FD#10: service-layer validator replaces Grails entity-level cross-instance validator
      @Transactional
      public SynonymDto save(SynonymDto dto) {
          if ("DISPLAY_NAME".equals(dto.synonymTypeCode())) {
              long existing = repo.countByProductIdAndLocaleAndSynonymTypeCode(
                  dto.productId(), dto.locale(), "DISPLAY_NAME"
              );
              if (existing > 0) {
                  throw new ResponseStatusException(HttpStatus.CONFLICT, "displayName.unique.message");
              }
          }
          // ... mapping DTO → entity + repo.save(...) — implementation only added if T1 confirms POST in scope
          throw new UnsupportedOperationException("T1 must confirm POST scope before implementing");
      }

      private static SynonymDto toDto(Synonym s) {
          return new SynonymDto(s.getId(), s.getProduct().getId(), s.getName(), s.getLocale(), s.getSynonymTypeCode());
      }
  }
  ```

  **`TagService.java`** — implements FD#9 forced-decision-determined protocol if Tag writes are in scope per T1 Step 7 disposition. If T1 disposition is option (b) escalate, TagService implements GET-only and throws on write. If option (a) accept-silent-duplicates, no special check. If option (c) app-layer pre-insert check, fetch existing pairs before insert and reject duplicates with `409 Conflict`:

  ```java
  @Transactional
  public TagDto addProductToTag(String tagId, String productId) {
      // T1 Step 7 option (c) — app-layer pair-uniqueness check (only if T1 chose this option)
      Tag tag = repo.findById(tagId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      Product product = productRepo.findById(productId).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
      if (tag.getProducts().contains(product)) {
          throw new ResponseStatusException(HttpStatus.CONFLICT, "tag-product pair already exists");
      }
      product.getTags().add(tag);  // Product owns the M:N per FD#9
      productRepo.save(product);
      return TagDto.from(tag);
  }
  ```

  Remaining services (`CategoryService`, `UnitOfMeasureService` (handles both UoM and UoMClass), `ProductTypeService`, `AttributeService`, `ProductGroupService`) follow standard CRUD pattern; write methods only implemented per T1 audit's per-entity write scope.

- [ ] **Step 4: Smoke startup**

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  sleep 15
  sudo docker logs openboxes-catalog-service 2>&1 | grep -iE "error|exception" | head -20
  sudo docker exec openboxes-catalog-service curl -s localhost:8085/actuator/health | grep '"status":"UP"' && echo "T6 health OK; all services + caches wired"
  ```

- [ ] **Step 5: Commit**

  ```bash
  git add services/catalog-service/src/main/java/org/openboxes/catalog/dto/ services/catalog-service/src/main/java/org/openboxes/catalog/service/ services/catalog-service/src/main/java/org/openboxes/catalog/cache/
  git commit -m "phase 5 task 6: 9 DTOs (flat FK-only per FD#3) + 8 services (Synonym validator-as-service per FD#10; TagService per T1 Step 7 disposition) + 4 caches (FD#7)"
  ```

---

### Task 7: Controllers + OpenAPI + JWT wiring

**Files:**
- Create: 7 `<Entity>Controller.java` files (ReferenceController bundles ProductType + Attribute reads)

JWT filter wired via SecurityConfig from T5. OpenAPI annotations per springdoc-openapi 2.5.0.

- [ ] **Step 1: Create `ProductController.java`** (R/O per FD#1 + FD#12)

  ```java
  package org.openboxes.catalog.controller;

  import org.openboxes.catalog.dto.ProductDto;
  import org.openboxes.catalog.service.ProductService;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.*;

  import java.util.List;
  import java.util.Map;

  @RestController
  @RequestMapping("/api/product")
  public class ProductController {
      private final ProductService service;
      public ProductController(ProductService service) { this.service = service; }

      @GetMapping
      public Map<String, Object> list() {
          List<ProductDto> data = service.list();
          return Map.of("data", data);
      }

      @GetMapping("/{id}")
      public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
          return service.get(id)
              .<ResponseEntity<Map<String, Object>>>map(p -> ResponseEntity.ok(Map.of("data", p)))
              .orElse(ResponseEntity.notFound().build());
      }

      // FD#12: subset of basic Product reads only; T1 audit confirms exact set
      // (e.g., /api/product/lotNumbersWithExpirationDate, /api/product/availableItems — added per T1)
  }
  ```

- [ ] **Step 2: Create `CategoryController.java`**

  ```java
  package org.openboxes.catalog.controller;

  import org.openboxes.catalog.dto.CategoryDto;
  import org.openboxes.catalog.service.CategoryService;
  import org.springframework.http.ResponseEntity;
  import org.springframework.web.bind.annotation.*;

  import java.util.List;
  import java.util.Map;

  @RestController
  @RequestMapping("/api/category")
  public class CategoryController {
      private final CategoryService service;
      public CategoryController(CategoryService service) { this.service = service; }

      @GetMapping
      public Map<String, Object> list() {
          return Map.of("data", service.list());
      }

      @GetMapping("/{id}")
      public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
          return service.get(id)
              .<ResponseEntity<Map<String, Object>>>map(c -> ResponseEntity.ok(Map.of("data", c)))
              .orElse(ResponseEntity.notFound().build());
      }

      // POST/PUT/DELETE methods added per T1 audit's per-entity write scope
  }
  ```

- [ ] **Step 3: Create `TagController.java`** (per T1; M:N write per T1 Step 7 disposition)

  Same shape as CategoryController for GET endpoints. If T1 surfaces Tag writes:
  - `POST /api/tag` — create Tag
  - `POST /api/tag/{id}/products/{productId}` — add Product to Tag M:N (invokes `TagService.addProductToTag(...)` which implements the T1-chosen FD#9 protocol)
  - `DELETE /api/tag/{id}/products/{productId}` — remove Product from Tag M:N

- [ ] **Step 4: Create `SynonymController.java`** — per T1; if writes in scope, `POST /api/synonym` invokes `SynonymService.save(...)` which runs the FD#10 service-layer validator

- [ ] **Step 5: Create `UnitOfMeasureController.java`** — `GET /api/unitOfMeasure{,/{id}}` + `GET /api/unitOfMeasureClass{,/{id}}` (per spec FD#5)

  ```java
  @RestController
  public class UnitOfMeasureController {
      private final UnitOfMeasureService service;
      public UnitOfMeasureController(UnitOfMeasureService service) { this.service = service; }

      @GetMapping("/api/unitOfMeasure")
      public Map<String, Object> listUom() { return Map.of("data", service.listUom()); }

      @GetMapping("/api/unitOfMeasure/{id}")
      public ResponseEntity<Map<String, Object>> getUom(@PathVariable String id) { /* ... */ }

      @GetMapping("/api/unitOfMeasureClass")
      public Map<String, Object> listUomClass() { return Map.of("data", service.listUomClass()); }

      @GetMapping("/api/unitOfMeasureClass/{id}")
      public ResponseEntity<Map<String, Object>> getUomClass(@PathVariable String id) { /* ... */ }
  }
  ```

- [ ] **Step 6: Create `ReferenceController.java`** — bundles `/api/productType{,/{id}}` + `/api/attribute{,/{id}}`

  ```java
  @RestController
  public class ReferenceController {
      private final ProductTypeService productTypeService;
      private final AttributeService attributeService;

      public ReferenceController(ProductTypeService pts, AttributeService as) {
          this.productTypeService = pts;
          this.attributeService = as;
      }

      @GetMapping("/api/productType")
      public Map<String, Object> listProductTypes() { return Map.of("data", productTypeService.list()); }

      @GetMapping("/api/productType/{id}")
      public ResponseEntity<Map<String, Object>> getProductType(@PathVariable String id) { /* ... */ }

      @GetMapping("/api/attribute")
      public Map<String, Object> listAttributes() { return Map.of("data", attributeService.list()); }

      @GetMapping("/api/attribute/{id}")
      public ResponseEntity<Map<String, Object>> getAttribute(@PathVariable String id) { /* ... */ }
  }
  ```

- [ ] **Step 7: Create `ProductGroupController.java`** — per T1; default GET-only

  Same shape as CategoryController.

- [ ] **Step 8: Smoke each endpoint via curl with valid JWT**

  ```bash
  (cd services && ./gradlew :catalog-service:bootJar)
  (cd docker && sudo docker compose up -d --build catalog-service)
  sleep 15

  # Generate a valid JWT (use the dev secret from compose)
  JWT=$(python3 -c "
  import jwt, time
  print(jwt.encode({'sub':'test','roles':['ROLE_BROWSER'],'iat':int(time.time()),'exp':int(time.time())+3600}, 'dev-secret-only-for-local-please-rotate-in-prod', algorithm='HS256'))")

  for ENDPOINT in /api/product /api/category /api/unitOfMeasure /api/unitOfMeasureClass /api/productType /api/attribute /api/tag /api/synonym /api/productGroup; do
      STATUS=$(sudo docker exec openboxes-catalog-service curl -s -o /dev/null -w "%{http_code}" -b "obx_token=$JWT" "localhost:8085${ENDPOINT}")
      echo "${ENDPOINT}: ${STATUS}"
      # expect: 200 for each
  done
  ```

- [ ] **Step 9: Commit**

  ```bash
  git add services/catalog-service/src/main/java/org/openboxes/catalog/controller/
  git commit -m "phase 5 task 7: 7 controllers (Product R/O per FD#12; Category/Tag/Synonym/ProductGroup per T1 write scope; UnitOfMeasureController for UoM+UoMClass; ReferenceController for ProductType+Attribute) + JWT wiring via SecurityConfig"
  ```

---

### Task 8: nginx routes

**Files:**
- Modify: `docker/nginx/conf.d/app.conf` — append catalog `/api/<entity>` location blocks per FD#4 + Phase 4.1 RC-10 ordering + Phase 4.1 RC-11 proxy_params include

nginx-only task; no compose change so RC-2 N/A.

- [ ] **Step 1: Append catalog blocks to `docker/nginx/conf.d/app.conf`** — after organization blocks, before `/api/` catch-all

  Per Phase 4.1 RC-10 insertion-order convention (block-ordering comment at line 11-13 of app.conf): new blocks appended at end of catalog stack before `/api/` catch-all. Per RC-11: each block uses `include /etc/nginx/conf.d/proxy_params;`. Per Phase 3+4 exact+prefix pattern: each entity uses exact-match (`location = /api/<entity>`) + prefix-match (`location /api/<entity>/`).

  Insert after the organization `/api/organization/` block (currently line ~47 of app.conf), before the `/api/` catch-all:

  ```nginx
      # Phase 5: catalog-service. Exact-match + prefix per Phase 3 RC-T8/T12 pattern.

      location = /api/product {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/product/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/category {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/category/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/tag {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/tag/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/synonym {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/synonym/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/unitOfMeasure {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/unitOfMeasure/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/unitOfMeasureClass {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/unitOfMeasureClass/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/productType {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/productType/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/attribute {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/attribute/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }

      location = /api/productGroup {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
      location /api/productGroup/ {
          proxy_pass http://catalog-service:8085;
          include /etc/nginx/conf.d/proxy_params;
      }
  ```

  Result: nginx block count grows from 9 → 27 (9 existing + 18 catalog: 9 exact + 9 prefix). Per spec risk "nginx block count growth": still well within nginx scalable range.

- [ ] **Step 2: `nginx -t` test**

  ```bash
  (cd docker && sudo docker compose up -d nginx)
  sudo docker exec openboxes-nginx nginx -t
  # expect: "syntax is ok" + "test is successful"
  sudo docker exec openboxes-nginx nginx -s reload
  ```

- [ ] **Step 3: Smoke each new route via curl through nginx**

  ```bash
  JWT=$(python3 -c "
  import jwt, time
  print(jwt.encode({'sub':'test','roles':['ROLE_BROWSER'],'iat':int(time.time()),'exp':int(time.time())+3600}, 'dev-secret-only-for-local-please-rotate-in-prod', algorithm='HS256'))")

  for ENDPOINT in /api/product /api/category /api/tag /api/synonym /api/unitOfMeasure /api/unitOfMeasureClass /api/productType /api/attribute /api/productGroup; do
      STATUS=$(curl -s -o /dev/null -w "%{http_code}" -b "obx_token=$JWT" "http://localhost${ENDPOINT}")
      echo "${ENDPOINT}: ${STATUS}"
      # expect: 200 for each (nginx proxies to catalog-service:8085)
  done
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add docker/nginx/conf.d/app.conf
  git commit -m "phase 5 task 8: nginx routes for 9 catalog entities (exact+prefix per Phase 3 RC-T8/T12; insertion-order per Phase 4.1 RC-10; proxy_params include per Phase 4.1 RC-11)"
  ```

---

### Task 9: Delete deletable Grails `*ApiController.groovy` + React URL/inline migration

**Files:**
- Delete: candidates per T1 Step 6 decision matrix (per F10 list: `AttributeApiController.groovy`, `CategoryApiController.groovy`, `UnitOfMeasureApiController.groovy` IF T1 confirms 100% coverage)
- Modify: `src/js/api/urls.js` — migrate URL constants per T1 Step 9 React URL migration list (PLURAL→SINGULAR per FD#4)
- Modify: `src/js/utils/option-utils.jsx` — migrate inline `/api/categoryOptions` at line 281 + `/api/tagOptions` at line 291 (EXCLUDE line 286 `/api/catalogOptions` — Phase 5.5)

DEFINITIVELY KEPT (per spec FDs + CDR R1 §3.2): `ProductApiController.groovy`, `ProductClassificationApiController.groovy`, `ProductPackageApiController.groovy`, `ProductSupplier*ApiController.groovy` ×3, probably `ProductsConfigurationApiController.groovy`.

- [ ] **Step 1: Delete deletable Grails controller files per T1 Step 6 matrix**

  Per T1 audit output's per-controller delete/keep decisions. Example (T1 will refine):
  ```bash
  rm -v grails-app/controllers/org/pih/warehouse/api/AttributeApiController.groovy
  rm -v grails-app/controllers/org/pih/warehouse/api/CategoryApiController.groovy
  rm -v grails-app/controllers/org/pih/warehouse/api/UnitOfMeasureApiController.groovy
  ```

  **Critical:** Each delete is conditional on T1 audit confirming (a) catalog-service 100% covers the actions, (b) no cross-context callers within Grails, (c) all React callers migrated in Step 2-3 below. If T1 surfaces a counter-example for any candidate, DO NOT delete that file.

  Also delete matching `UrlMappings.groovy` route entries for the deleted controllers (only if the routes are exclusive to those controllers; the generic `/api/${resource}s` mapping may still route to other actions).

- [ ] **Step 2: Migrate `src/js/api/urls.js` constants per T1 React URL migration list**

  For each constant T1 Step 2 flagged for migration:
  - Change URL form PLURAL→SINGULAR per FD#4 (e.g., `/api/attributes` → `/api/attribute`)
  - Update existing constant value; importers auto-pick up

  Add new constants for catalog-service endpoints not currently in urls.js:
  ```javascript
  // Phase 5: catalog-service endpoints
  export const CATEGORY_API = `${API}/category`;
  export const TAG_API = `${API}/tag`;
  export const SYNONYM_API = `${API}/synonym`;
  export const PRODUCT_TYPE_API = `${API}/productType`;
  export const ATTRIBUTE_API = `${API}/attribute`;       // migrated from /api/attributes (PLURAL→SINGULAR per FD#4)
  export const UNIT_OF_MEASURE_API = `${API}/unitOfMeasure`;  // migrated from /api/unitOfMeasures
  export const UNIT_OF_MEASURE_CLASS_API = `${API}/unitOfMeasureClass`;
  export const PRODUCT_GROUP_API = `${API}/productGroup`;
  ```

  Note: existing `PRODUCT_GROUP_OPTION = '/api/productGroupOptions'` (line 96) stays as-is — option endpoint hits Grails `ProductGroupApi.options()` action which is served by a *kept* controller per T1.

- [ ] **Step 3: Migrate `src/js/utils/option-utils.jsx` inline `/api/*` at lines 281 + 291 (EXCLUDE :286)**

  IF T1 Step 6 confirms catalog-service exposes the option-route equivalents:
  - Line 281: `apiClient.get('/api/categoryOptions')` → import + use `CATEGORY_OPTION` constant pointing to new catalog-service endpoint OR keep inline pointing to new singular URL
  - Line 291: `apiClient.get('/api/tagOptions')` → ditto for Tag

  ELSE (T1 Step 6 keeps `CategoryApiController.options` or `TagApiController.options` action alive in Grails): leave both lines unchanged.

  **DO NOT MODIFY LINE 286** (`apiClient.get('/api/catalogOptions')`) — hits DEFERRED ProductCatalog entity (Phase 5.5 scope per F8).

- [ ] **Step 4: Smoke Grails compiles + starts without deleted controllers**

  ```bash
  ./gradlew prepareDocker -Dgrails.env=prod --console=plain
  (cd docker && sudo docker compose up -d --build app)
  sleep 60  # Grails boot is slow
  curl -s http://localhost/openboxes/health && echo "Grails health OK after controller deletions"
  ```

- [ ] **Step 5: Smoke React URL migrations**

  ```bash
  # Verify migrated React still hits new catalog-service routes (via nginx):
  JWT=$(python3 -c "import jwt, time; print(jwt.encode({'sub':'test','roles':['ROLE_BROWSER'],'iat':int(time.time()),'exp':int(time.time())+3600}, 'dev-secret-only-for-local-please-rotate-in-prod', algorithm='HS256'))")
  for URL in /api/category /api/tag /api/attribute /api/unitOfMeasure; do
      STATUS=$(curl -s -o /dev/null -w "%{http_code}" -b "obx_token=$JWT" "http://localhost${URL}")
      echo "${URL}: ${STATUS}"  # expect 200
  done
  # Sanity: option endpoints (T9 may or may not migrate per T1)
  curl -s -o /dev/null -w "/api/categoryOptions: %{http_code}\n" "http://localhost/api/categoryOptions"
  curl -s -o /dev/null -w "/api/catalogOptions: %{http_code}\n" "http://localhost/api/catalogOptions"  # MUST still return Grails (Phase 5.5 deferred)
  ```

- [ ] **Step 6: Commit**

  ```bash
  git add grails-app/ src/js/api/urls.js src/js/utils/option-utils.jsx
  # delete cmd already removed files; staged via git add for tombstones
  git commit -m "phase 5 task 9: delete deletable Grails *ApiController per T1 (NOT ProductApi/ProductClassificationApi/ProductPackageApi/ProductSupplier*Api/ProductsConfigurationApi); migrate React urls.js constants (PLURAL→SINGULAR per FD#4); migrate option-utils.jsx inline /api/* (lines 281+291; EXCLUDE :286 catalogOptions per Phase 5.5)"
  ```

---

### Task 10: TestContainers JUnit suite

**Files:**
- Create: `services/catalog-service/src/test/java/org/openboxes/catalog/CatalogServiceIntegrationTest.java`
- Create: `services/catalog-service/src/test/resources/seed.sql`

~30-40 tests per Phase 4 `OrganizationServiceIntegrationTest` pattern. Tests assert behavior of T7 controllers + T6 services + T4 entities against TestContainers MariaDB with `seed.sql` fixtures.

- [ ] **Step 1: Create `seed.sql`** — fixtures for 9 entities + product_tag M:N + product_group_product M:N + Category tree + UoM↔UoMClass bidir

  ```sql
  -- 2 UnitOfMeasureClass (mass, count)
  INSERT INTO unit_of_measure_class (id, name, code, type, active) VALUES
      ('uomc-mass', 'Mass', 'M', 'METRIC', 1),
      ('uomc-count', 'Count', 'C', 'METRIC', 1);

  -- 4 UnitOfMeasure (kg, g, pc, dozen) + bidirectional base_uom
  INSERT INTO unit_of_measure (id, name, code, uom_class_id) VALUES
      ('uom-kg', 'Kilogram', 'kg', 'uomc-mass'),
      ('uom-g', 'Gram', 'g', 'uomc-mass'),
      ('uom-pc', 'Piece', 'pc', 'uomc-count'),
      ('uom-dozen', 'Dozen', 'dz', 'uomc-count');
  UPDATE unit_of_measure_class SET base_uom_id = 'uom-kg' WHERE id = 'uomc-mass';
  UPDATE unit_of_measure_class SET base_uom_id = 'uom-pc' WHERE id = 'uomc-count';

  -- Category tree: root + 2 children
  INSERT INTO category (id, name, sort_order, is_root) VALUES
      ('cat-root', 'Root', 0, 1);
  INSERT INTO category (id, name, parent_category_id, sort_order, is_root) VALUES
      ('cat-medical', 'Medical', 'cat-root', 1, 0),
      ('cat-supplies', 'Supplies', 'cat-root', 2, 0);

  -- 2 ProductType
  INSERT INTO product_type (id, name, code, product_type_code) VALUES
      ('pt-good', 'Good', 'GOOD', 'GOOD'),
      ('pt-service', 'Service', 'SVC', 'SERVICE');

  -- 2 Attribute
  INSERT INTO attribute (id, code, name, active, exportable, required, allow_multiple) VALUES
      ('attr-color', 'COL', 'Color', 1, 1, 0, 0),
      ('attr-size', 'SZ', 'Size', 1, 1, 0, 1);

  -- 1 ProductGroup
  INSERT INTO product_group (id, name) VALUES
      ('pg-medical', 'Medical Products');

  -- 3 Products
  INSERT INTO product (id, name, product_code, product_type_id, category_id, unit_of_measure_id, active) VALUES
      ('p-bandage', 'Bandage', 'BND001', 'pt-good', 'cat-medical', 'uom-pc', 1),
      ('p-syringe', 'Syringe', 'SYR001', 'pt-good', 'cat-medical', 'uom-pc', 1),
      ('p-iv-drip', 'IV Drip', 'IVD001', 'pt-good', 'cat-supplies', 'uom-pc', 1);

  -- 2 Tags
  INSERT INTO tag (id, tag, is_active) VALUES
      ('tag-essential', 'essential', 1),
      ('tag-trauma', 'trauma', 1);

  -- product_tag M:N (FD#9 — schema empirically has NO unique constraint)
  INSERT INTO product_tag (product_id, tag_id) VALUES
      ('p-bandage', 'tag-essential'),
      ('p-bandage', 'tag-trauma'),
      ('p-syringe', 'tag-essential');

  -- product_group_product M:N
  INSERT INTO product_group_product (product_id, product_group_id) VALUES
      ('p-bandage', 'pg-medical'),
      ('p-syringe', 'pg-medical');

  -- 2 Synonyms (1 DISPLAY_NAME per product per locale per FD#10)
  INSERT INTO synonym (id, name, locale, synonym_type_code, product_id) VALUES
      ('syn-bandage-fr', 'pansement', 'fr', 'DISPLAY_NAME', 'p-bandage'),
      ('syn-syringe-fr', 'seringue', 'fr', 'DISPLAY_NAME', 'p-syringe');
  ```

- [ ] **Step 2: Create `CatalogServiceIntegrationTest.java`** — TestContainers + MockMvc

  Per Phase 4 OrganizationServiceIntegrationTest pattern. ~30-40 tests covering:

  - **Auth (3 tests)**: 200 with valid JWT cookie, 401 without cookie, 401 with invalid cookie
  - **Product reads (4 tests)**: GET /api/product list returns 3, GET /api/product/{id} returns flat DTO with tagIds, GET /api/product/{nonexistent} returns 404, GET /api/product DTO has no nested entity inflation (FD#3 verification)
  - **Category tree (3 tests)**: GET /api/category list returns 3, GET /api/category/{id} returns flat DTO with parentCategoryId, GET /api/category/cat-root returns isRoot=true
  - **UoM ↔ UoMClass bidirectional (3 tests)**: GET /api/unitOfMeasure list returns 4, GET /api/unitOfMeasureClass/uomc-mass returns baseUomId="uom-kg" (bidirectional FD#11), GET /api/unitOfMeasure/uom-kg returns uomClassId="uomc-mass"
  - **Tag ↔ Product M:N reads (2 tests)**: GET /api/product/p-bandage returns tagIds set containing both essential + trauma, GET /api/tag/tag-essential returns productIds set containing p-bandage + p-syringe
  - **Tag write protocol per T1 disposition (2-4 tests)**: ONLY if T1 Step 7 confirmed Tag writes in scope:
    - IF T1 chose option (a) accept-silent-duplicates: POST same pair twice succeeds; verify DB has 2 rows
    - IF T1 chose option (b) escalate: POST returns 501 Not Implemented (Phase 5.5)
    - IF T1 chose option (c) app-layer dedup: POST same pair twice; second returns 409 Conflict
  - **Synonym validator-as-service (FD#10, 2 tests)**: POST DISPLAY_NAME synonym for same (product, locale) returns 409 Conflict; POST DISPLAY_NAME for different product+locale succeeds
  - **ProductGroup ↔ Product M:N (2 tests)**: GET /api/productGroup/pg-medical returns productIds set containing bandage + syringe; GET /api/product/p-bandage returns productGroupIds set containing pg-medical
  - **Reference data caches per FD#7 (3 tests)**: GET /api/unitOfMeasure twice — second hit served from cache (verifiable by repository-call mock or by cache size assertion); analogous for /api/productType and /api/attribute
  - **DTO flatness per FD#3 (1 test per entity, ~9 tests)**: Each GET /api/<entity>/{id} response body has NO nested entity objects; only flat FK strings

  Reference template — copy `OrganizationServiceIntegrationTest.java` shape (authCookie helper, DynamicPropertySource, MariaDBContainer setup), then write per-test methods per above.

- [ ] **Step 3: Run JUnit**

  ```bash
  (cd services && ./gradlew :catalog-service:test --info | tail -60)
  # expect: all ~30-40 tests pass
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add services/catalog-service/src/test/
  git commit -m "phase 5 task 10: CatalogServiceIntegrationTest (~30-40 tests covering auth, all GETs, M:N reads, FD#10 Synonym validator-as-service, FD#9 Tag write protocol per T1, FD#3 flat DTO assertion) + seed.sql for 9 entities + 2 join tables"
  ```

---

### Task 11: Playwright E2E specs

**Files:**
- Create: 8 `e2e/tests/catalog-*.spec.ts` files

Per Phase 4 `e2e/tests/organization-service.spec.ts` pattern. Tests hit nginx-proxied catalog-service routes; auth via shared `login` fixture from `e2e/fixtures/auth.ts`.

- [ ] **Step 1: Create `e2e/tests/catalog-product-readonly.spec.ts`**

  ```typescript
  import { test, expect } from '@playwright/test';
  import { login } from '../fixtures/auth';

  const BASE = process.env.BASE_URL ?? 'http://localhost';

  test.describe('catalog-service /api/product (R/O per FD#1)', () => {
      test('GET /api/product returns list', async ({ request }) => {
          const cookie = await login(request);
          const res = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
          expect(res.status()).toBe(200);
          const body = await res.json();
          expect(Array.isArray(body.data)).toBeTruthy();
      });

      test('GET /api/product/{id} returns flat DTO', async ({ request }) => {
          const cookie = await login(request);
          const listRes = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
          const list = await listRes.json();
          if (list.data.length === 0) test.skip(true, 'No products');
          const id = list.data[0].id;
          const res = await request.get(`${BASE}/api/product/${id}`, { headers: { Cookie: cookie } });
          expect(res.status()).toBe(200);
          const body = await res.json();
          expect(body.data.id).toBe(id);
          // FD#3: flat DTO — tagIds is an array of strings, not nested objects
          expect(Array.isArray(body.data.tagIds)).toBeTruthy();
          if (body.data.tagIds.length > 0) expect(typeof body.data.tagIds[0]).toBe('string');
      });
  });
  ```

- [ ] **Step 2: Create 7 more spec files** (mechanical — copy pattern):
  - `catalog-category.spec.ts` — GET + (POST/PUT/DELETE if T1)
  - `catalog-tag.spec.ts` — GET + Tag write protocol per T1 Step 7 disposition
  - `catalog-synonym.spec.ts` — GET + Synonym validator (FD#10) if writes in scope
  - `catalog-uom.spec.ts` — UoM + UoMClass GET-only
  - `catalog-reference.spec.ts` — ProductType + Attribute GET-only
  - `catalog-product-group.spec.ts` — ProductGroup GET + writes per T1
  - `catalog-options-regression.spec.ts` — `/api/categoryOptions`, `/api/tagOptions`, `/api/productGroupOptions` post-T9 sanity (each returns 200; verifies dropdowns don't break regardless of whether T9 migrated the URL or kept the Grails route)

- [ ] **Step 3: Run Playwright**

  ```bash
  (cd e2e && npm test 2>&1 | tail -40)
  # expect: all catalog-* specs pass; no regression in existing specs
  ```

- [ ] **Step 4: Commit**

  ```bash
  git add e2e/tests/catalog-*.spec.ts
  git commit -m "phase 5 task 11: 8 Playwright catalog-service E2E specs (R/O Product, Category, Tag w/ FD#9 protocol per T1, Synonym FD#10, UoM/UoMClass, ProductType+Attribute, ProductGroup, options-regression)"
  ```

---

### Task 12: Done-gate (light)

**Files:**
- None (verification-only task; may produce small follow-on fixes)

Per Phase 4 T12 with Phase 4.1 RC-22 plan defects already corrected: NO `down -v` (per RC-22 #1; preserves dev DB), uses `-x generateGitProperties` for local `prepareDocker` (per RC-22 #2; the git-properties task fails outside CI git tree), uses positional-args form for `docker stats` (per RC-22 #3; the `--format` flag spec changed). Per spec §5 carry-forward.

- [ ] **Step 1: `nginx -t` test**

  ```bash
  sudo docker exec openboxes-nginx nginx -t
  # expect: "syntax is ok" + "test is successful"
  ```

- [ ] **Step 2: TestContainers JUnit re-run for all 5 services**

  ```bash
  (cd services && ./gradlew test --info | tail -40)
  # expect: all tests pass across document-service, identity-service, location-service, organization-service, catalog-service
  ```

- [ ] **Step 3: Playwright re-run (full suite)**

  ```bash
  (cd e2e && npm test 2>&1 | tail -40)
  # expect: all specs pass (existing + new catalog-* specs)
  ```

- [ ] **Step 4: Smoke each new endpoint via curl through nginx**

  ```bash
  JWT=$(python3 -c "import jwt, time; print(jwt.encode({'sub':'test','roles':['ROLE_BROWSER'],'iat':int(time.time()),'exp':int(time.time())+3600}, 'dev-secret-only-for-local-please-rotate-in-prod', algorithm='HS256'))")
  for URL in /api/product /api/category /api/tag /api/synonym /api/unitOfMeasure /api/unitOfMeasureClass /api/productType /api/attribute /api/productGroup; do
      STATUS=$(curl -s -o /dev/null -w "%{http_code}" -b "obx_token=$JWT" "http://localhost${URL}")
      echo "${URL}: ${STATUS}"
      # expect: 200 for each
  done
  ```

- [ ] **Step 5: 8+ nginx-route smoke** (verify routing precedence not broken)

  ```bash
  curl -s -o /dev/null -w "/api/identity: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/identity"
  curl -s -o /dev/null -w "/api/documents: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/documents"
  curl -s -o /dev/null -w "/api/location: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/location"
  curl -s -o /dev/null -w "/api/organization: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/organization"
  curl -s -o /dev/null -w "/api/product: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/product"
  curl -s -o /dev/null -w "/api/category: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/category"
  # Sanity: non-migrated Grails routes still alive
  curl -s -o /dev/null -w "/api/products/search: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/products/search?name=test"
  curl -s -o /dev/null -w "/api/productClassifications: %{http_code}\n" -b "obx_token=$JWT" "http://localhost/api/productClassifications"
  # all should be 200, with /api/products/search hitting Grails ProductApiController.search
  ```

- [ ] **Step 6: Local prepareDocker (RC-22 corrected)**

  ```bash
  ./gradlew prepareDocker -Dgrails.env=prod --console=plain -x generateGitProperties
  # expect: clean build; no generateGitProperties failure outside CI
  ```

- [ ] **Step 7: Docker stats (RC-22 corrected positional form)**

  ```bash
  sudo docker stats --no-stream openboxes-catalog-service openboxes-organization-service openboxes-location-service openboxes-identity-service openboxes-document-service openboxes-app openboxes-db openboxes-nginx
  # purely informational; verify catalog-service memory + CPU profile is reasonable (~200-400MB RSS expected for a 9-entity catalog)
  ```

- [ ] **Step 8: Tag and push (only after user disposition)**

  ```bash
  git tag phase-5-catalog
  git push origin main phase-5-catalog
  ```

- [ ] **Step 9: (Optional) commit any plan-driven fixes surfaced during gate**

  If Step 1-7 surface defects, fix them and commit:
  ```bash
  git add <fix-files>
  git commit -m "phase 5 task 12: done-gate fix — <description>"
  ```

  If no fixes: no commit needed for T12 (verification-only).

---

### Task 13: Retro + Phase 5.1 forward pointer

**Files:**
- Create: `docs/retrospectives/2026-MM-DD-phase-5-catalog-retrospective.md`

Per Phase 4 T13 pattern. A-F triage of any backlog items surfaced during Phase 5 execution. Forward pointer to Phase 5.1 (jwt-auth-common extraction + 13 deferred catalog entities).

- [ ] **Step 1: Create retro doc** following Phase 4 retro structure (`docs/retrospectives/2026-05-29-phase-4-organization-retrospective.md`)

  Sections:
  - **What went well** (process discipline holding, RC-2 satisfied, FD#9 R3 fix held up, light SDD calibration carry-forward)
  - **What surfaced** (any T1 audit findings re-shaping scope; any FD revisions; any test failures during execution; FD#9 disposition outcome)
  - **Backlog with A-F triage** (per Phase 4.1 invented framework):
    - A: ship-blocker — none expected
    - B: next-phase candidate — Phase 5.5 entity migration
    - C: codified-rule candidate — any new process discipline lesson
    - D: dev-only fix — local/test improvements
    - E: deferred to Phase X — anything that requires significant infra
    - F: deleted — items considered and rejected
  - **Phase 5.1 forward pointer** — jwt-auth-common extraction; any new RC items from this retro
  - **Phase 5.5 / Phase 6 forward pointer** — 13 deferred entities; FD#9 long-term resolution if Tag writes ever land

- [ ] **Step 2: Commit**

  ```bash
  git add docs/retrospectives/2026-MM-DD-phase-5-catalog-retrospective.md
  git commit -m "phase 5 task 13: retrospective with A-F triage; Phase 5.1 (jwt-auth-common) + Phase 5.5 (13 deferred entities) forward pointers"
  ```

---

## Known issues inherited from spec

The following are inherited verbatim from spec §6 (`docs/specs/2026-05-29-phase-5-catalog-service-design.md`). These exist in the implementation by design — accepted during brainstorming. A new spec → new plan cycle is required to address any of these.

- **ProductMergeService stays Grails** — moves at Phase 6 with catalog/inventory restructure per parent §6 row 6
- **InventoryService bulk import** (`processData()` per-row product create-or-find) — stays Grails per parent §4.3 row 7; restructures at Phase 6
- **GSP-driven Product/Category/UoM admin** — stays Grails until Phase 12 GSP cleanup
- **CSV importer services** (`TagImportDataService`, `CategoryImportDataService`, `ProductSynonymImportDataService`) — stay Grails; out of Phase 5 partial-strangler scope
- **CSV importer services** that create catalog entities (`new Tag()`, `new Category()`, etc.) — stay Grails until sagas or a Phase X importer-extraction
- **LoadDataService / MigrationService catalog seed** — stays Grails until sagas (Phase 7+)
- **`jwt-auth-common` shared library** — 5th copy lands in Phase 5 catalog-service; extraction deferred to Phase 5.1 per FD#6
- **Flat FK-only DTO behavior departure from Grails** (FD#3) — React consumers that depended on nested-object inflation must fetch by ID via separate calls
- **No DELETE endpoints unless React calls today** — per FD#1. Test-data cleanup remains a manual task (or future Phase X tool)
- **ProductAvailability** → Phase 6 inventory-service (imports `InventoryItem`)
- **ProductSummary** → SQL view; Phase 11 reporting or retain forever
- **ProductMergeLogger** → Phase 6 with ProductMergeService
- **13 deferred catalog entities** → Phase 5.5 (ProductSupplier + variants, ProductPackage + ProductPrice, ProductCatalog + Item, ProductAssociation, ProductComponent, ProductAttribute, UoMConversion)
- **Product POST/PUT/DELETE** → React doesn't call today; defer indefinitely (unless Phase 5.5 surfaces a need)
- **ProductApiController stays alive** — its cross-context query actions (demand, productSummary, productAvailability) cannot migrate without inventory data
- **ProductClassificationApiController stays alive** — its single `list(facilityId)` action is a cross-context query (Location + InventoryLevel + Product); cannot migrate without Phase 6 inventory data + Location dependency; future entity migration (`abcClass` → own entity) naturally lands in Phase 6 or Phase 11
- **Cross-service productService consumers** (`StockMovementService`, `ProductAvailabilityService`, `ShipmentService`, `LoadDataService`, `ProductSynonymImportDataService`) read product data via direct JDBC against shared DB during transition; switch to HTTP at each consumer's owning service extraction
- **`product_tag` M:N from Grails side** — `Tag.addToProducts/removeFromProducts` Grails calls write `product_tag` rows directly via Hibernate 5; if catalog-service also writes (per T1 audit's per-entity scope finalization), the same table receives concurrent writes via Hibernate 6. **Empirically NOT constraint-protected** (no unique constraint on `(product_id, tag_id)` per CDR R3); concurrent INSERTs from both sides produce silent duplicate rows. Per FD#9: resolution deferred to T1 — fires as forced decision only if T1 finds Tag write callers
- **Reference-data cache invalidation on Grails GSP writes** — same shared-DB pattern as Phase 4 PartyTypeCache; tolerable cache lag (caches are refresh-on-miss, not invalidate-on-write)
- **Dev DB test pollution** — Phase 5 POST tests will add catalog rows that don't get cleaned up. Tolerable for dev DB; CI uses ephemeral containers
- **Per-task gate cadence overhead** (~6-10 min across 13 tasks for user dispositions) — accepted per Phase 3/4 user preference
