# Phase 5 Catalog Slice — Design Spec

**Date**: 2026-05-29
**Parent design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` §6 row 5 (Catalog slice, "widest reader fan-in"), §4.3 (entity ownership), §8 (per-slice template)
**Predecessor retrospectives**: Phase 1 (`docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`), Phase 2 (`docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`), Phase 3 (`docs/retrospectives/2026-05-28-phase-3-location-retrospective.md`), Phase 4 (`docs/retrospectives/2026-05-29-phase-4-organization-retrospective.md`)
**Codified process inputs**: `docs/process/sdd-reviewer-checklist.md` (RC-1 JPA SINGLE_TABLE nullability rule), `docs/process/plan-ordering-rules.md` (RC-2 compose↔CI workflow ordering, file-conflict rule), `docs/process/plan-template-defects.md` (RC-22 done-gate defects)
**Tag**: `phase-5-catalog`

## 1. TL;DR

Phase 5 stands up `catalog-service` as a new Spring Boot 3.x / Java 21 module that owns the React-facing HTTP surface for **8 catalog reference-data entities** (Category, UnitOfMeasure, UnitOfMeasureClass, Tag, Synonym, ProductType, Attribute, ProductGroup) plus **Product as read-only** (no React POST/PUT/DELETE for Product today — verified via `src/js/api/services/ProductApi.js` enumeration). Partial-strangler per Phase 4 FD#1 pattern, but **shape varies by entity tier**: the 8 reference entities follow Phase 4's full partial-strangler (GET + React-facing writes where they exist in `src/js/api/services/`); Product follows Phase 3 location-service read-only shape. 5th copy of `JwtCookieAuthFilter` + `JwtService` + `SecurityConfig` from organization-service's `security/` package; `jwt-auth-common` extraction deferred to Phase 5.1 per FD#6. nginx singular URL pattern carried forward from Phase 4 FD#4 — but **`ProductApiController.groovy` stays alive** because it's a god-controller hosting cross-context queries (demand forecasting via `forecastingService`, `productSummary` view, `productAvailability` inventory data) that cannot migrate to catalog-service. Some smaller catalog `*ApiController` files MAY be deletable; T1 audit decides per-controller. **13 entities deferred** to either Phase 5.5 (ProductSupplier+variants, ProductPackage+Price, ProductCatalog+Item, ProductAssociation, ProductComponent, ProductAttribute, UoMConversion) or their natural owning phase (ProductAvailability→Phase 6 inventory, ProductMergeLogger→Phase 6 with merge, ProductSummary→Phase 11 reporting or never since it's a SQL view backed by inventory + ordering tables). Tag `phase-5-catalog` on `main`.

## 2. Done state (one paragraph)

`catalog-service` runs in compose as the 8th container at port 8085 (`expose:` only, NOT `ports:` — mirrors organization-service:8084 pattern); validates `obx_token` cookies via shared HMAC HS256 secret (5th copy of the JWT triple); serves:

- **Reference data with writes (where React calls)**: `GET/POST/PUT/DELETE /api/category{,/{id}}`, `GET/POST/PUT/DELETE /api/tag{,/{id}}`, `GET/POST/PUT/DELETE /api/synonym{,/{id}}?productId=`, `GET/POST/PUT/DELETE /api/productGroup{,/{id}}` (exact write surface confirmed per-entity at T1 audit by reading `src/js/api/services/*Api.js` files)
- **Reference data read-only** (no React writes today): `GET /api/unitOfMeasure{,/{id}}`, `GET /api/unitOfMeasureClass{,/{id}}`, `GET /api/productType{,/{id}}`, `GET /api/attribute{,/{id}}`
- **Product read-only**: `GET /api/product/{id}`, `GET /api/product` filtered list, plus the subset of Product reads that don't depend on inventory data (e.g., `getLatestInventoryCountDate`, `lotNumbersWithExpirationDate`, `availableItems` — T1 audit confirms exact split between catalog-side reads and inventory-dependent reads that stay in Grails `ProductApiController`)

Flat FK-only DTOs per FD#3 (`CategoryDto.parentCategoryId`, `SynonymDto.productId`, `TagDto.productIds` for the M:N, `UnitOfMeasureDto.uomClassId`, `ProductDto.{categoryId,productTypeId,unitOfMeasureId,tagIds}`); Tag↔Product M:N via `product_tag` join table owned by catalog-service per FD#9; Synonym duplicate-display-name validation moves from GORM-cross-instance-validator to service layer per FD#10; UoM↔UoMClass circular FK as standard JPA bidirectional per FD#11; per-entity Liquibase shadow changelogs (one per table; `tableExists` precondition + empty body — existing data preserved); nginx routes per FD#4 added at end of location stack (insertion-order convention per Phase 4.1 RC-10) using `include /etc/nginx/conf.d/proxy_params;` per Phase 4.1 RC-11; React `src/js/api/urls.js` URL constants migrated for ONLY the moved endpoints (per T1 audit); Grails `*ApiController.groovy` files deleted ONLY where 100%-covered AND no cross-context callers remain (T1 audit decides per-controller; `ProductApiController.groovy` definitively stays); `CatalogServiceIntegrationTest` (~30-40 tests covering all endpoints, auth, CRUD round-trips, Tag↔Product M:N writes, Synonym validator-as-service rejection, Category tree walk, UoM↔UoMClass bidirectional reads, polymorphic edge cases); Playwright E2E (~6-10 specs covering React-facing surfaces); tagged `phase-5-catalog` on `main`; CI green.

## 3. Forced design decisions

### FD#1 — Read+write scope: HYBRID per entity tier

- **Reference data entities** (Category, UoM, UoMClass, Tag, Synonym, ProductType, Attribute, ProductGroup): partial-strangler per Phase 4 FD#1 — GET endpoints + React-facing writes only where React `*Api.js` POSTs/PUTs/DELETEs today.
- **Product**: READ-ONLY (Phase 3 location-service shape). `src/js/api/services/ProductApi.js` has zero `apiClient.post/put/delete` calls; Product is GSP-created.
- **Grails-internal writers stay Grails** for everything: ProductMergeService (stays until Phase 6 per parent §6 row 6), InventoryService.processData per-row product create-or-find (stays until Phase 6 per parent §4.3 row 7), GSP-driven Product/Category/UoM admin in `ProductController.groovy` + `CategoryController.groovy` + `ProductPackageController.groovy` etc. (stays until Phase 12 GSP cleanup), CSV importers (`TagImportDataService`, `CategoryImportDataService`, `ProductSynonymImportDataService` — out of partial-strangler scope), LoadDataService bootstrap + MigrationService seed (stays until sagas at Phase 7+).

**Rationale**: full-strangler (catalog-service handles all writes) would require introducing Grails→service HTTP-write pattern across the heaviest catalog write paths (ProductMergeService spans 15+ entity writes including Transaction/InventoryItem/RequisitionItem/ShipmentItem/OrderItem/ReceiptItem/InvoiceItem — atomic cross-context flow that needs saga infrastructure); InventoryService.processData per-row creates Products from inventory bulk-import. Both require sagas (parent §4.5; Phase 7). Read-only would delete zero Grails source; partial-strangler is the largest deletion achievable without sagas. Same logic as Phase 4 FD#1.

### FD#2 — JPA inheritance: NONE (verified empirically)

`grep -l '@Inheritance\|^class.*extends ' grails-app/domain/org/pih/warehouse/product/*.groovy grails-app/domain/org/pih/warehouse/core/{UnitOfMeasure,Tag,Synonym}*.groovy` returned ZERO matches. All in-scope entities are flat `class X implements Comparable, Serializable`. **Phase 4 RC-1 SINGLE_TABLE nullability rule (codified in `docs/process/sdd-reviewer-checklist.md`) does NOT apply** to any Phase 5 entity. Direct JPA `@Entity` mapping for each.

### FD#3 — Flat FK-only DTOs (carry forward Phase 3 + Phase 4 FD#3)

Same pattern. No nested entity inflation in responses. Specifically:

- `CategoryDto`: `id`, `name`, `description`, `parentCategoryId` (NOT nested Category), `sortOrder`, `isRoot`, `glAccountId`
- `SynonymDto`: `id`, `productId`, `name`, `locale`, `synonymTypeCode`
- `TagDto`: `id`, `tag`, `isActive`, `productIds` (array of Product IDs for the M:N — NOT nested Product objects)
- `UnitOfMeasureDto`: `id`, `name`, `code`, `description`, `uomClassId`
- `UnitOfMeasureClassDto`: `id`, `name`, `code`, `description`, `active`, `type`, `baseUomId` (back-reference FK; not nested)
- `ProductTypeDto`, `AttributeDto`, `ProductGroupDto`: flat per entity field set
- `ProductDto`: `id`, `name`, `description`, `productCode`, `productTypeId`, `categoryId`, `unitOfMeasureId`, `pricePerUnit`, `costPerUnit`, `active`, `tagIds`, `synonymIds` (or counts), other scalar fields per entity; NO nested Category/ProductType/etc.

**Rationale**: avoids LazyInitializationException class of bugs (Phase 3 retro line 20: ~6 days of cost saved); avoids cross-service-fetching at DTO marshal time; consumers fetch related entities by ID via separate calls. Behavior departure from Grails (which often inflated nested objects via GORM eager-fetch hints) documented as known issue §6.

### FD#4 — URL pattern: singular per Phase 4 FD#4; per-controller deletion decisions

New endpoints under singular roots: `/api/category`, `/api/tag`, `/api/synonym`, `/api/unitOfMeasure`, `/api/unitOfMeasureClass`, `/api/productType`, `/api/attribute`, `/api/productGroup`, `/api/product`. Grails generic `/api/${resource}s` URL mapping (line ~935 of `UrlMappings.groovy`) keeps the existing plural URLs alive for their existing Grails controllers.

For each Grails `*ApiController.groovy` in `grails-app/controllers/org/pih/warehouse/api/`, T1 audit decides per-controller:

- **`ProductApiController.groovy`** — DEFINITIVELY STAYS. Hosts cross-context queries: `demand` (forecastingService), `demandSummary`, `productSummary` (queries ProductSummary SQL view backed by inventory tables), `productAvailability` (queries ProductAvailability table — inventory-context per parent §4.3 row 6), plus `list`, `search`, `productSummary` actions referencing `inventoryService`/`productAvailabilityService`. catalog-service can't reproduce these without inventory data. The 4-5 basic Product reads migrate to catalog-service's `/api/product/*`; the rest stay Grails at `/api/products/*`.
- **`CategoryApiController.groovy`** — likely 100%-coverable by catalog-service `CategoryController`; T1 audit confirms by enumerating actions; if covered, delete; if not, keep alive.
- **`ProductPackageApiController.groovy`** — DEFERRED entity (ProductPackage in §5 deferrals); STAYS alive.
- **`ProductsConfigurationApiController.groovy`** — T1 audit decides; probably stays (configuration endpoint with cross-context dependencies likely).
- Other `*ApiController` files for in-scope entities (TagApiController if exists, etc.) — T1 audit decides per file.

This is a per-controller decision pattern rather than the blanket FD#4 deletion of Phase 4 (which was viable because OrganizationApiController had only 3 actions all replaceable). Phase 5's per-controller decisions documented in T1 audit output for plan-write.

### FD#5 — Entity scope: 9 IN, 13 DEFER with rationale

**IN SCOPE (9 entities)**:

| Entity | Location | Shape | Notes |
|---|---|---|---|
| Product | `product/Product.groovy:53` | READ-ONLY | No React writes today; has `publishPersistenceEvent` lifecycle hook firing `InventorySnapshotEvent` — non-issue since R/O per FD#8 |
| Category | `product/Category.groovy:14` | Full CRUD | Self-FK `parentCategory` tree (line 20); `hasMany categories`; Grails L2 cache (line 37) |
| UnitOfMeasure | `core/UnitOfMeasure.groovy:14` | GET only | FK `uomClass` (line 29); Grails GORM `beforeInsert/Update` audit hooks → JPA `@EntityListeners` port |
| UnitOfMeasureClass | `core/UnitOfMeasureClass.groovy:15` | GET only | Bidirectional FK to UoM via `baseUom` (line 32) per FD#11 |
| Tag | `core/Tag.groovy:16` | Full CRUD | M:N to Product via `product_tag` per FD#9; `belongsTo = Product` + `hasMany products` |
| Synonym | `core/Synonym.groovy:17` | Full CRUD | `belongsTo product: Product` (line 37); cross-instance validator → service layer per FD#10 |
| ProductType | `product/ProductType.groovy:16` | GET only | Flat reference data |
| Attribute | `product/Attribute.groovy:22` | GET only | Flat reference data (was missed in initial scoping — distinct from ProductAttribute) |
| ProductGroup | `product/ProductGroup.groovy:15` | Full CRUD (per `ProductGroupApi.js`) | hasMany products (M:N pattern); React surface in `src/js/api/services/ProductGroupApi.js` |

**DEFERRED to Phase 5.5** (entities with deferrable Phase-5-shape work that can land in a follow-on slice):

- **ProductSupplier, ProductSupplierPreference** — React-driven CRUD exists (`src/js/api/services/ProductSupplier*Api.js` — including `ProductSupplierAttributeApi.js` which is a React-side concept hitting catalog entities filtered by supplier; not a distinct entity); but pulls Supplier data from organization-service's Supplier SQL view (Phase 4 FD#5); cross-service coordination defers cleanly to Phase 5.5
- **ProductPackage, ProductPrice** — paired (ProductPackage imports ProductPrice from `core/`); React POST exists for ProductPackage; defer to keep Phase 5 entity count manageable
- **ProductCatalog, ProductCatalogItem** — paired; lower priority; defer to Phase 5.5
- **ProductAttribute** — paired with Attribute (Attribute stays in Phase 5; ProductAttribute defers because it has 4 cross-entity FKs: Attribute + Product + ProductSupplier + UnitOfMeasure)
- **ProductAssociation** — relationship table (substitutes, related products); defer to Phase 5.5
- **ProductComponent** — bundles/kits; defer to Phase 5.5 or Phase 6
- **UnitOfMeasureConversion** — paired with UoM but less critical; defer to Phase 5.5

**DEFERRED to natural owning phase**:

- **ProductAvailability** (`product/ProductAvailability.groovy:14`) — imports `InventoryItem` from inventory package; inventory-context cross-cut; Phase 6 inventory-service owns
- **ProductMergeLogger** — audit data for ProductMergeService which stays Grails per parent §6 row 6; moves with merge at Phase 6
- **ProductSummary** (`product/ProductSummary.groovy`) — confirmed SQL view via `grails-app/migrations/views/product-summary.sql` (`CREATE OR REPLACE VIEW product_summary AS ... FROM product_availability JOIN product ... on_order_order_item_summary ... on_order_shipment_item_summary`); read-only; backed by inventory + ordering tables; Phase 11 reporting-service home or retain in Grails forever

T1 audit re-verifies these decisions and surfaces any new entity-level findings before T2 module skeleton begins.

### FD#6 — `jwt-auth-common` shared library deferred to Phase 5.1

5th copy of `JwtCookieAuthFilter.java`, `JwtService.java`, `SecurityConfig.java` from `services/organization-service/src/main/java/org/openboxes/organization/security/` (3 files verified portable per A23) with package rename to `org.openboxes.catalog.security`. Do NOT extract a shared library in Phase 5.

**Rationale**: extracting now would touch 4 already-shipped services (document, identity, location, organization) outside Phase 5's vertical-slice purpose. Phase 5.1 horizontal-cleanup slice — planned per Phase 4.1 pattern — is the right home; sized for cross-service refactor work.

### FD#7 — Reference-data caches case-by-case (decided at T1 / plan-write)

Dev DB row counts (`SELECT COUNT(*) FROM ...` against running dev DB) returned: UnitOfMeasure=8, UnitOfMeasureClass=2, Tag=0, Category=0, Product=0, Synonym=0, ProductAttribute=0, ProductPackage=0, ProductCatalog=0, ProductSupplier=0. **Row count is NOT a useful signal** — dev DB is fresh, not seeded with realistic catalog data. Use churn-frequency heuristic:

- **Cache** (mature reference data, rare changes): UnitOfMeasure, UnitOfMeasureClass, ProductType, Attribute — `RefreshOnMissCache` pattern per Phase 4 PartyTypeCache (with `getAll()`-refresh-on-empty fix per Phase 3 RC-6)
- **Cache with refresh** (mid-churn): Category — refresh-on-write
- **No cache** (higher churn or large): Tag, Synonym, ProductGroup, Product

Final per-entity cache decision in plan-write time based on T1 audit signal (e.g., if T1 finds heavy admin UI churn on Tags, no cache).

### FD#8 — Product `publishPersistenceEvent()` lifecycle hook: N/A in Phase 5

Product entity has `afterInsert/afterUpdate/afterDelete` hooks (Product.groovy:74-78) firing `InventorySnapshotEvent` to Grails application context. Since Phase 5 Product is READ-ONLY in catalog-service per FD#1, NO write path triggers this event in catalog-service. Grails Product writes (GSP admin, ProductMergeService, InventoryService bulk import, CSV import) continue to fire the event Grails-side as today; no consumer change.

**Non-issue for Phase 5.** If Phase 5.5 adds React-driven Product writes via catalog-service, this becomes a real forced decision requiring saga design (publish to outbox? Skip silently? Local-only event?). Re-open as FD at Phase 5.5 brainstorming.

### FD#9 — Tag↔Product M:N: catalog-service owns `product_tag` join table (intra-slice)

Both Tag and Product are in catalog-service scope. M:N writes via JPA `@ManyToMany` annotation. Tag side declares `@ManyToMany(mappedBy = "tags")` (Product is the owner of the relationship from the JPA mapping perspective — matches the existing Grails `Tag.products joinTable: [name: 'product_tag', column: 'product_id', key: 'tag_id']` where `product_id` is the FK to Product). Product side declares `@ManyToMany @JoinTable(name="product_tag", joinColumns=@JoinColumn(name="product_id"), inverseJoinColumns=@JoinColumn(name="tag_id"))`.

No cross-service concern — both entities live in catalog-service. Grails-side `Tag.addToProducts/removeFromProducts` continues to work via shared DB (Grails writes `product_tag` rows directly via Hibernate 5); catalog-service writes the same table via Hibernate 6. Concurrent writes from both sides could in theory race, but `product_tag` is a join table with unique-pair constraint — collisions surface as DB-level constraint violations, not silent data corruption.

### FD#10 — Synonym validator: move from entity to service layer

Grails `Synonym.groovy:45-53` has cross-instance validator referencing `obj?.product?.synonyms?.findAll { synonym -> synonym.locale == val && synonym.synonymTypeCode == SynonymTypeCode.DISPLAY_NAME }` — checks for duplicate DISPLAY_NAME synonyms per locale per product. JPA Bean Validation (`@AssertTrue` on instance) can't express cross-instance queries cleanly.

Resolution: move validation to `SynonymService.save(synonymDto)` as explicit pre-save check:

```java
// Pseudo-code; final form in plan-write
if (dto.synonymTypeCode == DISPLAY_NAME) {
    long existingDisplayNames = synonymRepo.countByProductIdAndLocaleAndSynonymTypeCode(
        dto.productId, dto.locale, DISPLAY_NAME
    );
    if (existingDisplayNames > 0) throw new ConflictException("displayName.unique.message");
}
```

Same business rule, service-layer expression, clean unit test surface. Grails-side Synonym creation continues to use the GORM validator (shared DB writes still flow through Grails for CSV imports etc.).

### FD#11 — UoM ↔ UoMClass bidirectional FK: standard JPA

UnitOfMeasure has `UnitOfMeasureClass uomClass` (UoM.groovy:29); UnitOfMeasureClass has `UnitOfMeasure baseUom` (UoMClass.groovy:32). Standard JPA bidirectional:

```java
@Entity class UnitOfMeasure {
    @ManyToOne @JoinColumn(name="uom_class_id") UnitOfMeasureClass uomClass;
    // ...
}
@Entity class UnitOfMeasureClass {
    @ManyToOne @JoinColumn(name="base_uom_id") UnitOfMeasure baseUom;
    @OneToMany(mappedBy="uomClass") List<UnitOfMeasure> uoms;
    // ...
}
```

Cycle is in object graph only (not in FK constraints); both `uom_class_id` and `base_uom_id` are nullable so no chicken-and-egg DB constraint. No special handling needed.

### FD#12 — ProductApiController stays alive; thin migration of basic reads only

Per FD#4, `ProductApiController.groovy` keeps its existing actions (demand, demandSummary, productSummary, productAvailability, search, classifications, dashboard endpoints) Grails-side. catalog-service exposes only the basic Product reads that don't depend on inventory data:

- `GET /api/product/{id}` — basic read by ID
- `GET /api/product` — filtered list (filterable by Category, Tag, ProductType, ProductGroup, GlAccount as per `ProductApiController.list` line 44)
- `GET /api/product/lotNumbersWithExpirationDate?productIds=` (if it doesn't need inventory data; T1 audit confirms by reading the action body)
- `GET /api/product/availableItems?productIds=&locationId=` (if catalog-side; T1 audit confirms)

T1 audit confirms exact split. React `src/js/api/urls.js` constants migrate ONLY for the moved endpoints — Grails plural URLs for unmoved actions stay.

## 4. Architecture

```
                ┌────────────────────────────────────────────────────────────┐
                │  catalog-service (NEW, port 8085, expose: only)             │
                │  ┌────────────────────────────────────────────────────┐    │
                │  │ Controllers (T1 audit finalizes per-controller):     │    │
                │  │ - ProductController        (READ ONLY, ~4 GET)       │    │
                │  │   GET /api/product/{id}, GET /api/product            │    │
                │  │   GET /api/product/{lotNumbers,availableItems}       │    │
                │  │ - CategoryController       (full CRUD)               │    │
                │  │ - TagController            (full CRUD; M:N writes)   │    │
                │  │ - SynonymController        (full CRUD)               │    │
                │  │ - UnitOfMeasureController                            │    │
                │  │   GET /api/unitOfMeasure{,/{id}}                     │    │
                │  │   GET /api/unitOfMeasureClass{,/{id}}                │    │
                │  │ - ReferenceController                                │    │
                │  │   GET /api/productType{,/{id}}                       │    │
                │  │   GET /api/attribute{,/{id}}                         │    │
                │  │ - ProductGroupController   (CRUD per ProductGroupApi)│    │
                │  ├────────────────────────────────────────────────────┤    │
                │  │ Services + reference-data caches per FD#7:           │    │
                │  │  - ProductService, CategoryService, TagService,      │    │
                │  │    SynonymService (incl. validator port per FD#10),  │    │
                │  │    UnitOfMeasureService, ProductTypeService,         │    │
                │  │    AttributeService, ProductGroupService             │    │
                │  │  - Caches: UnitOfMeasureCache, ProductTypeCache,     │    │
                │  │    AttributeCache, CategoryCache (TBD plan-write)    │    │
                │  ├────────────────────────────────────────────────────┤    │
                │  │ JPA Entities (FLAT per FD#2; 9 total):               │    │
                │  │ - Product (READ ONLY; no write hooks active per FD#8)│    │
                │  │ - Category (self-FK parent + hasMany categories)     │    │
                │  │ - UnitOfMeasure ↔ UnitOfMeasureClass (bidir FD#11)   │    │
                │  │ - Tag (M:N to Product per FD#9)                      │    │
                │  │ - Synonym (FK Product; @ManyToOne)                   │    │
                │  │ - ProductType, Attribute, ProductGroup               │    │
                │  ├────────────────────────────────────────────────────┤    │
                │  │ Security: JwtCookieAuthFilter (5th copy per FD#6)    │    │
                │  │ Liquibase: 9 shadow changelogs (per table;           │    │
                │  │   tableExists precondition + empty body)             │    │
                │  └────────────────────────────────────────────────────┘    │
                └────────────────────────────────────────────────────────────┘
                                  ▲                                  ▲
                                  │ JPA reads (Product R/O)          │ JPA CRUD (8 ref entities)
                                  ▼                                  ▼
                          ──────── SHARED MariaDB ───────────────────────
                                          ▲
                                          │ Grails continues to write:
   openboxes-app (Grails) ────────────────┤   - ProductController (GSP admin) — Phase 12
       │  Stays alive:                    │   - ProductMergeService — Phase 6
       │  - ProductApiController          │   - InventoryService.processData — Phase 6
       │    (god-controller; cross-       │   - CSV importers (Tag/Category/Synonym)
       │     context queries: demand,     │   - LoadDataService bootstrap (Phase 7+ sagas)
       │     productSummary, etc.)        │
       │  - GSP admin controllers         │
       │  - CSV importer services         │
       │  - StockMovementService,         │ Reads productService (switch to HTTP at Phase 6/7)
       │    ProductAvailabilityService,   │
       │    ShipmentService (cross-       │
       │    context productService        │
       │    consumers — direct JDBC       │
       │    against shared DB)            │
       │                                  │
   React (7 *Api.js files for catalog,    │ Per T1 audit:
        per src/js/api/services/) ────────┘   - Migrate URL constants in src/js/api/urls.js
                                              for moved endpoints
                                          │
                                  nginx routes (insertion-order per Phase 4.1 RC-10;
                                  include /etc/nginx/conf.d/proxy_params per Phase 4.1 RC-11):
                                  Added at end of location stack, before /api/ Grails catch-all:
                                  - /api/category, /api/tag, /api/synonym
                                  - /api/unitOfMeasure, /api/unitOfMeasureClass
                                  - /api/productType, /api/attribute, /api/productGroup
                                  - /api/product (exact match for 4 specific routes;
                                    Grails /api/products/* plural stays for unmoved)
```

## 5. Tasks (provisional; plan-write finalizes)

| # | Task | Notes |
|---|------|-------|
| **T1** | **Empirical audit** (FD#5 final IN/DEFER + FD#4 per-`*ApiController` delete/keep + FD#12 ProductApiController action-level migration split + React `src/js/api/urls.js` enumeration per migrated endpoint + cross-context atomic-write audit per parent §8 Step 1 — already partially done in brainstorming; T1 finalizes) | Output: final entity table, URL surface table, React URL migration list, cross-context atomic-write findings. User approval gate before T2. |
| **T2** | Spring Boot module skeleton (`services/catalog-service/`); Gradle sub-module; main class; basic build | Phase 4 T2 pattern; ~50 LOC |
| **T3** | JPA entities (9) + Liquibase shadow changelogs (one per table) | Heavier than Phase 4 T3 (9 vs 5 entities); includes Tag↔Product M:N + Category tree + UoM↔UoMClass bidir |
| **T4** | DTOs (flat FK-only per FD#3) + mappers (entity → DTO static `from()` methods) | Mechanical; ~20 LOC per entity |
| **T5** | Services (port Grails business rules to Spring `@Service`) + reference-data caches per FD#7; Synonym service-layer validator port per FD#10 | Heaviest logic task |
| **T6** | Controllers + OpenAPI annotations + JWT filter wiring (5th copy of JWT triple per FD#6) | Plan-verbatim code blocks per Phase 4 T7 pattern |
| **T7** | nginx routes + docker-compose service entry + healthcheck | Adds catalog-service as 8th container; nginx routes per FD#4 + Phase 4.1 RC-10 ordering + Phase 4.1 RC-11 proxy_params include |
| **T8** | Delete deletable `*ApiController.groovy` files per T1 audit (NOT ProductApiController); migrate React `src/js/api/urls.js` for moved endpoints | Smaller deliverable than Phase 4 T8 (Product API stays alive); per-controller delete decisions documented in commit message |
| **T9** | TestContainers JUnit suite (`CatalogServiceIntegrationTest` + companion files; seed.sql for 9 entities incl. Tag↔Product M:N, Category tree, UoM↔UoMClass bidir, Synonym validator-as-service) | ~30-40 tests |
| **T10** | React URL migration commits + Playwright E2E specs per migrated React surface | Per Phase 4 T10 pattern |
| **T11** | CI workflow update (`.github/workflows/e2e-tests.yml`) — builds catalog-service jar, probes its `/actuator/health`, dumps logs on failure | **MUST be in same commit as T7 OR strictly BEFORE T7** per Phase 4.1 RC-2 codified rule (`docs/process/plan-ordering-rules.md`) |
| **T12** | Done-gate (light): `nginx -t` + Playwright re-run + JUnit re-run + smoke each new endpoint + 8-route nginx smoke | Per Phase 4 T12 with Phase 4.1 RC-22 plan defects already corrected (no `down -v`; `-x generateGitProperties` for local `prepareDocker`; `docker stats` positional-args form) |
| **T13** | Retro: backlog catalog with A-F triage; Phase 5.1 forward pointer (includes jwt-auth-common extraction + 13 deferred entities) | Per Phase 4 T13 pattern |

**Process discipline carry-forward (no new ground):**
- **Per-task gate cadence** (Phase 3+4 retro lesson, Phase 4.1 carry-forward): stop after each task's two-stage review for user disposition
- **Light SDD calibration** (Phase 4 T11 lesson): Direct apply when (plan-pre-approved verbatim AND no business logic AND <20 LOC)
- **thorough-brainstorming → thorough-writing-plans** strict cadence (Phase 4.1 pattern); CDR + CIR rounds; spec/code-quality reviewer subagents per task
- **A23 commit convention**: `phase 5 task N: <description>`; subject-only (no Co-Authored-By body per Phase 4.1 T5 noted-and-dropped)
- **Push deferral**: bundle push at T12 (per A23 convention)

**Estimated pace** (one developer, Phase 4 reference point ~3 days active dev): Phase 5 with 9 entities + Product-as-readonly is ~1.5× Phase 4 → **4-6 days active dev**. Smaller than the original 17-entity full-scope estimate (which would have been ~7-10 days).

## 6. Known issues / accepted as out of scope

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
- **Cross-service productService consumers** (`StockMovementService`, `ProductAvailabilityService`, `ShipmentService`, `LoadDataService`, `ProductSynonymImportDataService`) read product data via direct JDBC against shared DB during transition; switch to HTTP at each consumer's owning service extraction
- **`product_tag` M:N from Grails side** — `Tag.addToProducts/removeFromProducts` Grails calls write `product_tag` rows directly via Hibernate 5; catalog-service writes the same table via Hibernate 6. Concurrent writes are constraint-protected (unique pair) but not coordinated
- **Reference-data cache invalidation on Grails GSP writes** — same shared-DB pattern as Phase 4 PartyTypeCache; tolerable cache lag (caches are refresh-on-miss, not invalidate-on-write)
- **Dev DB test pollution** — Phase 5 POST tests will add catalog rows that don't get cleaned up. Tolerable for dev DB; CI uses ephemeral containers
- **Per-task gate cadence overhead** (~6-10 min across 13 tasks for user dispositions) — accepted per Phase 3/4 user preference

## 7. Risks

- **"No (or few) Grails source deleted" risk.** Depending on T1 audit, Phase 5 may not delete any `*ApiController.groovy` (Phase 3 location-service shape). Phase 5 deliverable measured by: new catalog-service standing up + 9 entities migrated + Product reads moved + Phase 6/7 enabled. Not zero value, but smaller "strangler bite" than Phase 4's 38-LOC OrganizationApiController deletion. **Mitigation**: communicate the per-controller decision matrix in T1 audit clearly; track which Grails controllers are slated for Phase 5.5 + Phase 6 deletions instead.

- **Widest reader fan-in (parent §6 row 5) — confirmed empirically.** ProductService is consumed by `LoadDataService`, `ProductSynonymImportDataService`, `ProductAvailabilityService`, `StockMovementService`, `ShipmentService` (verified via `grep -rln 'productService\.' grails-app/services/`). All read-side at extraction time; switch to HTTP at their owning service's extraction (mostly Phase 6 inventory + Phase 7+ ordering/shipping). **Mitigation**: parent design's "direct JDBC during transition" policy handles this; no Phase 5 action needed beyond documenting.

- **Per-entity write API enumeration in T1 audit could shift entity scope.** If T1 reveals an entity has more write surface than expected (e.g., Synonym has bulk-edit endpoint surfaced by a closer read of `src/js/api/services/`), Phase 5 task estimate shifts. **Mitigation**: T1 audit produces full React-side surface enumeration with action signatures; surface findings as forced decisions for user before T2.

- **Phase 5.5 commitment becomes real.** 13 entities deferred. Phase 5.5 will be the first time the Phase N.1 pattern handles substantial entity migration work (Phase 4.1 was mostly cleanup + 2 entities of effective scope via the proxy_params extract + login fixture). Risk: Phase 5.5 grows to Phase-5-size and the "horizontal cleanup slice" abstraction stops holding. **Mitigation**: Phase 5.1 brainstorming explicitly considers whether to split deferred entities across multiple sub-cleanup slices (Phase 5.1.A for jwt-auth-common + small batch, Phase 5.1.B for heavier entities) OR roll into Phase 6 inventory's scope at boundary.

- **5th JWT copy widens the "must fix in N places" surface.** If HMAC HS256 → RS256 ever happens, that's 5 services × ~50 LOC each. **Mitigation**: Phase 5.1 commits to extraction; if a security model change becomes urgent before 5.1 ships, accelerate 5.1.

- **Plural URL hard-deletion (per FD#4 per-controller) could surface unexpected Grails callers.** Phase 4 RC-3 saw "overstated similarity" of 3 specs (actually 2-byte-identical + 1 different); analogous risk for any plural URL `*ApiController` we delete at T8. **Mitigation**: T1 audit greps `grep -rn '/api/<plural>'` across `grails-app/` for any server-side callers + greps React for any direct usages of soon-to-delete plural URLs.

- **Schema additive-only constraint (parent §8 Step 6) gets tight.** Catalog tables have heavy column inventory (Product alone has 50+ columns). Once shadow changelogs land in catalog-service, all migrations on those tables are additive-only until Grails callers are deleted (Phase 12). **Mitigation**: catalog schema is mature; no business-driven schema change expected during Phase 5; if any surface, defer to Phase 5.1+ or Phase 12.

- **Category tree (self-FK) edge cases.** Grails likely has soft-delete or `parent_id IS NULL` root semantics. JPA self-reference works fine but tree-walking endpoints (if any in scope) need care. **Mitigation**: T1 audit checks for any tree-walk methods in `CategoryController`/`CategoryService`; plan-write decides whether to port server-side recursion or expose flat list + client-side tree assembly.

- **Tag↔Product M:N concurrent write coordination.** Grails Tag.addToProducts and catalog-service POST /api/tag both write `product_tag`. Unique-pair constraint protects against duplicate rows but doesn't coordinate deletes. **Mitigation**: phase-5 scope test the M:N writes from both sides in T9 integration tests (TestContainers session against the live Grails session via shared DB if needed; OR explicit single-side-only test cases).

- **Synonym validator-as-service port subtleties (FD#10).** Cross-instance validator was in Grails entity; moving to service means the validation only fires for catalog-service writes. Grails-side Synonym creation via CSV import (`ProductSynonymImportDataService`) still validates via GORM entity-side rule — but that path is unchanged. No regression. **Mitigation**: T9 test the catalog-service service-layer rejection of duplicate display name.

- **4-6 days active dev pace** (estimated) — larger than Phase 4 (3 days). Risk of mid-phase fatigue or context loss. **Mitigation**: per-task gate cadence + handoff discipline (proven across Phase 4 + Phase 4.1); aggressive deferral at T1 audit if scope creeps.

- **nginx block count growth.** Currently 8 blocks at Phase 4.1 done. Phase 5 adds 8-9 more (one per entity controller). Stack reaches ~16-17 blocks. Still well within nginx scalable range, but ordering convention (Phase 4.1 RC-10 codified) becomes more important. **Mitigation**: T1 audit verifies all new nginx blocks follow phase-insertion-order convention; ordering comment in `app.conf` line 11-13 reminds.

- **Dev DB has 0 catalog rows.** T9 TestContainers tests fully self-seed via `seed.sql` (Phase 4 pattern). T10 Playwright tests need to either POST-then-test (where writes exist) or seed via SQL before tests run. **Mitigation**: T10 Playwright specs include explicit seed-via-POST setup phases for entities that need test data.

## 8. Verified assumptions

The following 26 load-bearing assumptions were enumerated cold against the design and verified against the codebase before this spec was committed. Where verification surfaced design changes, the design was revised and re-approved by the user before this spec was written.

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
| A10 | ProductSupplier React surface | ⚠️ React has CRUD | `ProductSupplierApi.js:14-19` `deleteProductSupplier`, `saveDetails` POST, `updateDetails` PUT — DEFERRED to Phase 5.5 per FD#5 (Supplier-view cross-cut) |
| A11 | No JPA inheritance in catalog | ✅ | `grep -l '@Inheritance\|class.*extends' grails-app/domain/.../product/ + .../core/{UoM,Tag,Synonym}*.groovy` returned ZERO → FD#2 |
| A12 | Reference-data row counts | ⚠️ All ZERO except UoM(8)/UoMClass(2) | Dev DB queries via `sudo docker exec openboxes-db mariadb ... SELECT COUNT(*) FROM ...` → FD#7 falls back to churn-frequency heuristic |
| A13 | ProductApiController exists | ⚠️ God-controller | `ProductApiController.groovy` has list, demand, demandSummary, productSummary, productAvailability, search + injects productService/inventoryService/forecastingService/productAvailabilityService → FD#4 stays alive, FD#12 thin migration |
| A14 | Grails URL mappings exist | ⚠️ Huge surface | `UrlMappings.groovy` lines 45-148, 504-509 enumerate 20+ /api/products/* and /api/categories/* paths INCLUDING cross-context endpoints → FD#4 per-URL decisions |
| A15 | React URL constants location | ✅ | `src/js/api/urls.js` is THE URL constants file; `src/js/api/services/ProductApi.js` confirmed READ-ONLY (no apiClient.post/put/delete for Product) → FD#1 Product R/O |
| A16 | Cross-context atomic-write audit | ⚠️ Many findings | greps surfaced: `new Product(` in InventoryService; `new Tag(` in TagImportDataService; `new Category(` in CategoryImportDataService; cross-package productService injections in LoadDataService, StockMovementService, ProductAvailabilityService, ShipmentService, ProductSynonymImportDataService → all handled per parent §4.3 coverage policy + FD#1 (stay Grails) |
| A17 | ProductMergeService stays Grails | ✅ | `ProductMergeService.groovy:44` `@Transactional def mergeProduct` has 15+ `.save(flush:true)` across Transaction/InventoryItem/RequisitionItem/ShipmentItem/OrderItem/ReceiptItem/InvoiceItem — confirms parent §6 Phase 6 deferral |
| A18 | InventoryService.processData creates Products | ✅ | `grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy` — Phase 6 per parent §4.3 row 7 |
| A19 | ProductSummary is SQL view | ✅ | `grails-app/migrations/views/product-summary.sql` is `CREATE OR REPLACE VIEW product_summary AS ... FROM product_availability JOIN product ... on_order_*_summary` — backed by inventory + ordering tables → DEFERRED to Phase 11 (or never) |
| A20 | ProductDimension in reporting/ | ✅ | `grails-app/domain/org/pih/warehouse/reporting/ProductDimension.groovy` — Phase 11 scope |
| A21 | ProductAvailability is inventory cross-cut | ✅ | `ProductAvailability.groovy:14` imports `InventoryItem` — Phase 6 scope |
| A22 | ProductMergeLogger is merge audit | ✅ | `ProductMergeLogger.groovy` package `product/`, imports User; defers with ProductMergeService → Phase 6 |
| A23 | JWT triple portable | ✅ | `services/organization-service/src/main/java/org/openboxes/organization/security/{JwtCookieAuthFilter,JwtService,SecurityConfig}.java` confirmed — 3 files ready for 5th copy + package rename |
| A24 | Port 8085 unused | ✅ | `docker-compose-base.yml` lines 32-105: expose 8081 (document) / 8082 (identity) / 8083 (location) / 8084 (organization); 8085 free |
| A25 | Phase 4.1 docs/process/ + nginx infra applies | ✅ | docs/process/{sdd-reviewer-checklist,plan-template-defects,plan-ordering-rules}.md exist (committed at Phase 4.1); nginx app.conf has RC-10 block-order comment + 8 include /etc/nginx/conf.d/proxy_params lines (committed at Phase 4.1) |
| A26 | Catalog entity inventory complete | ⚠️ 17+ entities, not 6 | `ls grails-app/domain/.../product/` + `core/{UoM,Tag,Synonym}*.groovy` surfaced 17 candidates; Attribute, ProductPrice, ProductCatalogItem, UoMConversion missed in initial scoping → FD#5 9-IN-13-DEFER table reflects empirical reality |

## 9. Phase 5.1 / Phase 5.5 forward pointer

Phase 5.1 horizontal-cleanup slice (per Phase 4.1 pattern) anticipated to handle:

- **`jwt-auth-common` shared library extraction** (FD#6) — extract `JwtCookieAuthFilter` + `JwtService` (subset) + `SecurityConfig` to `services/jwt-auth-common/`; migrate all 5 services (document, identity, location, organization, catalog) to consume it
- **Plus any retrospective candidates surfaced during Phase 5 execution** (A-F triage per Phase 4.1 invented framework)

Phase 5.5 (or rolled into Phase 5.1, or rolled into Phase 6 — decided during Phase 5 retro):

- **13 deferred catalog entities** (ProductSupplier + variants, ProductPackage + ProductPrice, ProductCatalog + Item, ProductAssociation, ProductComponent, ProductAttribute, UoMConversion)
- React-driven CRUD for ProductSupplier + ProductPackage already exists; needs the Supplier-view cross-cut with organization-service worked through
- May surface as one larger Phase 5.5 OR split into Phase 5.5.A (simpler entities) + roll-into-Phase 6 (ProductSupplier needs inventory boundary anyway)
- Decision deferred to Phase 5 retro per Phase 4.1 lesson #2 (Phase N + Phase N.1 separation as strong organizational invariant)
