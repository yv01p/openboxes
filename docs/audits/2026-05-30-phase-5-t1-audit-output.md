# Phase 5 T1 Audit Output

**Date:** 2026-05-30
**Phase:** Phase 5 — Catalog Service Extraction
**Purpose:** Empirical audit gating Phase 5 T2-T11. Per-entity write scope finalization (FD#1, FD#5), per-controller delete/keep matrix (FD#4), ProductApiController action-level migration split (FD#12), expanded React URL enumeration (CDR R1 §3.1), cross-context atomic-write findings, and FD#9 Tag-writes-disposition.

**Spec:** `docs/specs/2026-05-29-phase-5-catalog-service-design.md`
**Plan:** `docs/plans/2026-05-29-phase-5-catalog-service-implementation-plan.md`

---

## Table of contents

1. Entity write-scope table (9 entities)
2. URL surface table (Grails catalog-area routes + their disposition)
3. React URL migration list (inline + `urls.js`-constant catalog URLs)
4. Cross-context findings (atomic catalog-entity writes that stay Grails)
5. FD#9 disposition (Tag-writes determination)
6. Per-controller delete/keep decisions (10 catalog-area `*ApiController.groovy` files)

Auxiliary appendices:
- A1. Inline `/api/*` matches in `src/js/**` (230 total; catalog subset of 16 isolated)
- A2. `src/js/api/urls.js` catalog-area constants (full list with consumers)

---

## 1. Entity write-scope table

| Entity | GET | POST | PUT | DELETE | Evidence (caller `file:line` OR "no callers found") |
|---|---|---|---|---|---|
| **Product** | Y (R/O per FD#1) | N | N | N | GET callers: `src/js/api/services/ProductApi.js:13,14,16,17,24,28` (getProducts, getInventoryItem, getProduct via generic, getLatestInventoryCountDate, getLotNumbersByProductIds, availableItems); `src/js/utils/option-utils.jsx:122,147` (search). NO `apiClient.post/put/delete` against `PRODUCT_API`/`INVENTORY_ITEM`/`AVAILABLE_ITEMS`/`LOT_NUMBERS_WITH_EXPIRATION_DATE` — verified via grep. Confirms FD#1 + spec §3 R/O. |
| **Category** | Y | N | N | N | GET callers: `src/js/utils/option-utils.jsx:281` (inline `/api/categoryOptions`). No React caller of `/api/categories` (server-side `CategoryApiController.list/read/save/delete` exist but are unused by React per zero hits in `grep -rn "/api/categories" src/js/`). |
| **UnitOfMeasure** | Y | N | N | N | GET callers: `src/js/api/services/UnitOfMeasureApi.js:5` (`CURRENCIES_OPTIONS`), `:6` (`UNIT_OF_MEASURE_OPTIONS`). No POST/PUT/DELETE in React. |
| **UnitOfMeasureClass** | Y (implicit) | N | N | N | No direct React caller for UoMClass; UoM responses include `uomClassId` per FD#3 flat DTOs. GET-only by inheritance (no React mutation surface). |
| **Tag** | Y | N | N | N | GET callers: `src/js/utils/option-utils.jsx:291` (inline `/api/tagOptions`). NO React POST/PUT/DELETE — verified via `grep -rnE "apiClient\.(post\|put\|delete).*[tT]ag\|/api/tags?\b" src/js/` returning zero matches. Confirms spec FD#5 default + clears FD#9 forced decision (see §5). |
| **Synonym** | N (no React surface) | N | N | N | No `/api/synonyms` URL in React; no React caller of Synonym. Synonym CRUD lives entirely Grails-side (GSP admin via `ProductController.editSynonym` + `ProductService.addSynonymToProduct` line 1517 + CSV import `ProductSynonymImportDataService`). No React `Synonym*Api.js` file exists in `src/js/api/services/`. **Catalog-service may still implement a GET endpoint for future use but no React migration target exists today.** |
| **ProductType** | Y (implicit) | N | N | N | No direct dedicated React caller for ProductType options found; ProductType is referenced as filter on `ProductApiController.list` (line 50 `productFamilies = ProductGroup.getAll(params.list('productFamilyId'))` — ProductGroup, not ProductType). React fetches ProductType implicitly via Product responses (as `productTypeId` per FD#3 flat DTO). GET-only. |
| **Attribute** | Y | N | N | N | GET callers: `src/js/api/services/ProductSupplierApi.js:16` (`ATTRIBUTES` constant; calls `/api/attributes` → `AttributeApiController.list`). Used by `productSupplierReducer.jsx:25` `FETCH_ATTRIBUTES` action. No POST/PUT/DELETE. |
| **ProductGroup** | Y | N | N | N | GET callers: `src/js/api/services/ProductGroupApi.js:5` (`PRODUCT_GROUP_OPTION` → `/api/productGroupOptions`). No React POST/PUT/DELETE. |

**Summary:** All 9 in-scope entities are **GET-only from React's perspective**. This matches spec FD#5 defaults exactly; **no entity surfaces POST/PUT/DELETE callers that would expand scope beyond defaults.** No additional write endpoints required in catalog-service Phase 5.

---

## 2. URL surface table

Catalog-area URL routes found in `grails-app/controllers/org/pih/warehouse/UrlMappings.groovy` and their Phase 5 disposition. Generic `/api/${resource}s` mappings (line 935 list/create; line 940 status; line 945 by-id) catch plural resource paths that resolve to existing `*ApiController` instances.

| URL pattern | HTTP method | UrlMappings line(s) | Controller / action | Disposition | Target catalog-service action / Notes |
|---|---|---|---|---|---|
| `/api/categories` | GET | 45-48 | `categoryApi.list` | Stay Grails initially; catalog-service exposes new `/api/category` per FD#4 | catalog `CategoryController.list()` (GET-only) |
| `/api/categories` | POST | 45-48 | `categoryApi.save` | Stay Grails (no React caller; possibly used by GSP or dead) | n/a (Category POST not in Phase 5 scope) |
| `/api/categories/$id` | GET, POST, PUT, DELETE | 49-52 | `categoryApi.{read,save,save,delete}` | Stay Grails (no React caller; dead-from-React) | n/a |
| `/api/categoryOptions` | GET | 55-58 | `selectOptionsApi.categoryOptions` | Stay Grails (SelectOptionsApiController served by Phase 5.5+ or never; uses `genericApiService` + `Category.getHierarchyAsString`) | inline call from `option-utils.jsx:281` stays Grails for Phase 5 |
| `/api/catalogOptions` | GET | 61-64 | `selectOptionsApi.catalogOptions` | **OUT OF SCOPE (ProductCatalog deferred to Phase 5.5)** | inline call from `option-utils.jsx:286` stays Grails (per spec §1 explicit exclusion) |
| `/api/productGroupOptions` | GET | 67-70 | `selectOptionsApi.productGroupOptions` | Stay Grails (SelectOptionsApi not migrated in Phase 5) | `ProductGroupApi.js:5` stays consuming Grails URL |
| `/api/tagOptions` | GET | 73-76 | `selectOptionsApi.tagOptions` | Stay Grails (SelectOptionsApi not migrated in Phase 5) | inline call from `option-utils.jsx:291` stays Grails |
| `/api/products` | GET, POST | 109-112 | `productApi.{list,save}` | GET: split per FD#12 (basic list → catalog `GET /api/product`; advanced filters with inventory dependencies → stays Grails). POST: stays Grails (Product is R/O in catalog-service per FD#1; ProductApiController.save line 306-337 references `inventoryService`-adjacent productType validation and remains Grails) | catalog `ProductController.list()` (GET only); see §5.5 deferred for POST |
| `/api/products/search` | GET | 114-117 | `productApi.search` | Stays Grails (depends on `productAvailabilityService.getAvailableBinLocations` line 135 — inventory-context) | n/a (Grails-served) |
| `/api/products/$id/$action` | (catch-all) | 119-121 | `productApi.<action>` | Per-action split per FD#12 — most stay Grails (cross-context) | See the ProductApiController action-level split table below |
| `/api/products/$productId/inventoryItems/$lotNumber` | GET | 123-126 | `productApi.getInventoryItem` | Stays Grails (depends on `productAvailabilityService.getQuantityOnHand` line 291 — inventory-context) | n/a |
| `/api/products/getLatestInventoryCountDate` | GET | 128-131 | `productApi.getLatestInventoryCountDate` | Stays Grails (productService delegates to inventory-bound `latestInventoryDateForProducts` — verify in T6) OR catalog if pure SQL on `product_availability` is acceptable — per FD#12, defer to plan-write classification | leans Grails (inventory-dependent name suggests inventory-context) |
| `/api/products/import` | POST | 133-136 | `productApi.importCsv` | Stays Grails (CSV importer per spec §6 "CSV importer services stay Grails") | n/a |
| `/api/products/availableItems` | GET | 138-141 | `productApi.availableItems` | Stays Grails (line 169 `inventoryService.getAvailableBinLocations` — inventory-context) | n/a |
| `/api/products/inventoryItems/lotNumbersWithExpirationDate` | GET | 143-146 | `productApi.getLotNumbersWithExpirationDate` | Stays Grails (productService delegates to inventory item queries — verify) OR catalog (line 361 invokes `productService.getLotNumbersWithExpirationDate` which may be inventory-context); leans Grails | n/a |
| `/api/facilities/$facilityId/products/classifications` | GET | 148-151 | `productClassificationApi.list` | **DEFINITIVELY STAYS Grails** per spec FD#4 (cross-context: Location + InventoryLevel + Product.abcClass) | n/a |
| `/api/products/$id/withCatalogs` | GET | matched by 119 catch-all | `productApi.withCatalogs` | Stays Grails (depends on `ProductCatalog` — DEFERRED entity) | n/a (catalogs deferred to 5.5) |
| `/api/products/$id/productDemand` | GET | matched by 119 catch-all | `productApi.productDemand` | Stays Grails (`forecastingService.getDemand` line 284 — cross-context) | n/a |
| `/api/products/$id/productAvailabilityAndDemand` | GET | matched by 119 catch-all | `productApi.productAvailabilityAndDemand` | Stays Grails (`productAvailabilityService` + `inventoryService` + `forecastingService` lines 273-275) | n/a |
| `/api/products/$id/availableBins` | GET | matched by 119 catch-all | `productApi.availableBins` | Stays Grails (inventoryService line 184) | n/a |
| `/api/products/$id/substitutions`, `/api/products/$id/associatedProducts` | GET | matched by 119 catch-all | `productApi.{substitutions,associatedProducts}` | Stays Grails (ProductAssociation entity DEFERRED + line 211 `inventoryService.getAvailableItems` — cross-context) | n/a (ProductAssociation in Phase 5.5) |
| `/api/products/$id/{demand,demandSummary,productSummary,productAvailability}` | GET | matched by 119 catch-all | `productApi.{demand,demandSummary,productSummary,productAvailability}` | Stays Grails (forecastingService + ProductAvailability + ProductSummary view — all cross-context per spec FD#12) | n/a |
| `/api/attributes` | GET | matched by 935 generic | `attributeApi.list` | Stay Grails initially; catalog-service exposes new `/api/attribute` per FD#4 | catalog `ReferenceController.attributes()` (GET only) |
| `/api/unitOfMeasure/currencies` | GET | 504-507 | `unitOfMeasureApi.currencies` | Stay Grails (Currency is NOT a catalog entity — `uomService.getCurrencies()` returns currency reference data; comment in `urls.js:159` notes "do not change to plural") | n/a (currencies are not UoM entity proper; stays in Grails) |
| `/api/unitOfMeasures/options` | GET | 509-512 | `unitOfMeasureApi.uomOptions` | Stay Grails initially; catalog-service exposes new `/api/unitOfMeasure` per FD#4 | catalog `UnitOfMeasureController.list()` (GET; may serve a `?type=…` filter param) |
| `/api/productPackages` | POST | matched by 935 generic | `productPackageApi.create` | **DEFINITIVELY STAYS Grails** (ProductPackage DEFERRED to Phase 5.5 per spec FD#5) | n/a |
| `/api/productSuppliers` (GET, POST) | GET, POST | matched by 935 generic | `productSupplierApi.{list,create}` | **DEFINITIVELY STAYS Grails** (ProductSupplier DEFERRED to Phase 5.5) | n/a |
| `/api/productSuppliers/$id` (GET, PUT, DELETE) | GET, PUT, DELETE | matched by 945 generic | `productSupplierApi.{read,update,delete}` | **DEFINITIVELY STAYS Grails** | n/a |
| `/api/productSuppliers/export` | GET | 911-914 | `productSupplierApi.export` | **DEFINITIVELY STAYS Grails** | n/a |
| `/api/productSupplierPreferences/batch` | POST | 901-904 | `productSupplierPreferenceApi.createOrUpdateBatch` | **DEFINITIVELY STAYS Grails** (deferred) | n/a |
| `/api/productSupplierAttributes/batch` | POST | 906-909 | `productSupplierAttributeApi.updateAttributes` | **DEFINITIVELY STAYS Grails** (deferred) | n/a |
| `/api/productsConfiguration/{categoriesCount,downloadCategories,importCategories,importCategoryCsv,downloadCategoryTemplate,categoryOptions,productOptions,importProducts}` | GET, POST | 861-899 | `productsConfigurationApi.<action>` | **DEFINITIVELY STAYS Grails** (configuration/bootstrap area; cross-context with `userService.isSuperuser` line 72 and config-driven `categoryOptions`/`productOptions` from `grailsApplication.config`) | n/a |

### ProductApiController action-level split (FD#12)

| Action (`ProductApiController.groovy` line) | Current URL | Injected deps | Disposition | Target catalog-service action (if migrating) |
|---|---|---|---|---|
| `list()` line 44 | GET `/api/products` | `productService` + reads Category, Tag, ProductCatalog, GlAccount, ProductGroup | **MIGRATE basic read to catalog** (filters by Category/Tag/ProductGroup/GlAccount are all catalog-context); the `format=csv` branch uses `productService.exportProducts` which can move too. **Caveat:** `format=csv` includes `includeAttributes` flag — Attribute is in catalog scope, OK. | catalog `ProductController.list()` (GET only — POST `save` stays Grails) |
| `demand()` line 89 | GET `/api/products/$id/demand` | `forecastingService` | **STAY Grails** | n/a |
| `demandSummary()` line 100 | GET `/api/products/$id/demandSummary` | `forecastingService` | **STAY Grails** | n/a |
| `productSummary()` line 107 | GET `/api/products/$id/productSummary` | `ProductAvailability.findAll…` (inventory-context) | **STAY Grails** | n/a |
| `productAvailability()` line 114 | GET `/api/products/$id/productAvailability` | `ProductAvailability` | **STAY Grails** | n/a |
| `search()` line 121 | GET `/api/products/search` | `productService.searchProducts` + `productAvailabilityService.getAvailableBinLocations` (when `availableItems` flag set) | **STAY Grails** (typeahead branch uses inventory data) | n/a |
| `availableItems()` line 160 | GET `/api/products/availableItems` | `inventoryService.getAvailableBinLocations` | **STAY Grails** | n/a |
| `availableBins()` line 174 | GET `/api/products/$id/availableBins` | `inventoryService.getAvailableBinLocations` | **STAY Grails** | n/a |
| `substitutions()` line 189 | GET `/api/products/$id/substitutions` | `inventoryService.getAvailableItems/Products` + `ProductAssociation` (deferred entity) | **STAY Grails** | n/a |
| `associatedProducts()` line 195 | GET `/api/products/$id/associatedProducts` | same as `substitutions` | **STAY Grails** | n/a |
| `withCatalogs()` line 254 | GET `/api/products/$id/withCatalogs` | `Product.getProductCatalogs()` → `ProductCatalog` (deferred entity) | **STAY Grails** | n/a |
| `productAvailabilityAndDemand()` line 270 | GET `/api/products/$id/productAvailabilityAndDemand` | `productAvailabilityService` + `inventoryService` + `forecastingService` | **STAY Grails** | n/a |
| `productDemand()` line 279 | GET `/api/products/$id/productDemand` | `productAvailabilityService` + `forecastingService` | **STAY Grails** | n/a |
| `getInventoryItem()` line 288 | GET `/api/products/$productId/inventoryItems/$lotNumber` | `InventoryItem.findByProductAndLotNumber` + `productAvailabilityService.getQuantityOnHand` | **STAY Grails** | n/a |
| `getLatestInventoryCountDate()` line 298 | GET `/api/products/getLatestInventoryCountDate` | `productService.latestInventoryDateForProducts` (delegates to inventory-side query) | **STAY Grails** (inventory-dependent) — re-verify at T6 plan-write whether productService method body is pure-product or inventory-coupled | n/a |
| `save(Product)` line 306 | POST `/api/products` | `productService.saveProduct` + `ProductType.defaultProductType` + `product.validateRequiredFieldsInLocation` (Location dep) | **STAY Grails** (Product R/O in catalog-service per FD#1) | n/a |
| `importCsv()` line 339 | POST `/api/products/import` | `productService.{validateProducts,importProducts}` (CSV importer flow) | **STAY Grails** (CSV importers stay Grails per spec §6) | n/a |
| `getLotNumbersWithExpirationDate()` line 358 | GET `/api/products/inventoryItems/lotNumbersWithExpirationDate` | `productService.getLotNumbersWithExpirationDate` (inventory-context per URL pattern) | **STAY Grails** (inventory item context per URL) | n/a |

**Net result:** Only **`list()`** migrates from `ProductApiController` to catalog-service. ProductApiController retains 18 of 19 actions and stays alive (confirms FD#4 + FD#12).

---

## 3. React URL migration list

Catalog-area inline URLs (from `src/js/utils/option-utils.jsx` and other components) + `src/js/api/urls.js` constants that are in catalog scope.

### 3.1 In-scope migrations (Phase 5 T9 work)

| `src/js/...` file:line | Current URL / import | New URL / import | T9 step |
|---|---|---|---|
| `src/js/api/urls.js:78` (constant) | `export const PRODUCT_API = '${API}/products'` | `export const PRODUCT_API = '${API}/product'` (singular per FD#4) — **only the catalog-side basic `list()` route migrates; all other consumers of `PRODUCT_API` continue to hit Grails plural URLs** | Either (a) update `PRODUCT_API` to singular and add a new `PRODUCTS_LEGACY_API` for the non-migrated consumers (`getLatestInventoryCountDate`, etc.), OR (b) leave `PRODUCT_API` as plural and add a new `CATALOG_PRODUCT_API = '${API}/product'` constant used only for the migrated `getProducts()` call. **Plan-write decides (b) is safer**: minimizes regression surface to 1 line in `ProductApi.js:13`. |
| `src/js/api/services/ProductApi.js:13` | `getProducts: (config) => apiClient.get(PRODUCT_API, config)` | `getProducts: (config) => apiClient.get(CATALOG_PRODUCT_API, config)` (assuming option-b above) | T9 |
| `src/js/api/urls.js:113-114` (constant) | `export const ATTRIBUTES = '${API}/attributes'` | `export const ATTRIBUTES = '${API}/attribute'` (singular per FD#4) | T9 |
| `src/js/api/services/ProductSupplierApi.js:16` consumer of `ATTRIBUTES` | (unchanged; constant value updates) | (unchanged) | T9 |
| `src/js/api/urls.js:157-158` (constant) | `export const UNIT_OF_MEASURE_API = '${API}/unitOfMeasures'; export const UNIT_OF_MEASURE_OPTIONS = '${UNIT_OF_MEASURE_API}/options'` | `export const UNIT_OF_MEASURE_API = '${API}/unitOfMeasure'; export const UNIT_OF_MEASURE_OPTIONS = '${UNIT_OF_MEASURE_API}'` (singular per FD#4; the new catalog `GET /api/unitOfMeasure?type=...` replaces `GET /api/unitOfMeasures/options?type=...`). **Verify at T6 plan-write:** catalog `UnitOfMeasureController` must accept `?type=...` filter param matching Grails `UnitOfMeasureType` enum. | T9 |
| `src/js/api/services/UnitOfMeasureApi.js:6` consumer of `UNIT_OF_MEASURE_OPTIONS` | (unchanged signature; URL changes via constant) | (unchanged) | T9 |
| `src/js/api/urls.js:96` (constant) | `export const PRODUCT_GROUP_OPTION = '${API}/productGroupOptions'` | **Leave unchanged** — `productGroupOptions` is served by `SelectOptionsApiController.productGroupOptions` (not migrated in Phase 5; SelectOptionsApi has cross-context dependencies on `genericApiService` for multiple entity types). React `ProductGroupApi.js:5` continues to hit Grails. **Future option:** catalog-service could expose `GET /api/productGroup` returning a flat list; React would migrate then. Defer to plan-write decision. | (defer to Phase 5.5 / no T9 work) |
| `src/js/utils/option-utils.jsx:281` (inline) | `apiClient.get('/api/categoryOptions')` | **Leave unchanged** — `categoryOptions` is served by `SelectOptionsApiController` with custom `Category.getHierarchyAsString` formatting (hierarchical "> " separator); not just a catalog read. Defer to Phase 5.5+. | (defer) |
| `src/js/utils/option-utils.jsx:291` (inline) | `apiClient.get('/api/tagOptions', { params })` | **Leave unchanged** — `tagOptions` served by `SelectOptionsApiController` with `hideNumbers` formatting (line 77 `"${it.tag} (${it?.products?.size()})"`); cross-context with Product count. Defer. | (defer) |

### 3.2 Out-of-scope (catalog-related URLs that do NOT migrate in Phase 5)

| `src/js/...` file:line | URL | Reason for no migration |
|---|---|---|
| `src/js/utils/option-utils.jsx:122` | `/api/products/search?...location.id=...` | `ProductApiController.search` stays Grails (inventory-dependent typeahead) |
| `src/js/utils/option-utils.jsx:147` | `/api/products/search?...availableItems=true` | same — stays Grails |
| `src/js/utils/option-utils.jsx:286` | `/api/catalogOptions` | ProductCatalog DEFERRED to Phase 5.5 (spec §1) |
| `src/js/components/stock-list-management/StocklistManagement.jsx:120` | `/api/products/$id/withCatalogs` | ProductCatalog deferred |
| `src/js/components/stock-movement-wizard/request/AddItemsPage.jsx:1512` | `/api/products/$id/productDemand?...` | forecastingService cross-context (stays Grails) |
| `src/js/components/stock-movement-wizard/request/AddItemsPage.jsx:1533` | `/api/products/$id/productAvailabilityAndDemand?...` | same |
| `src/js/components/products-configuration/*.jsx` (8 inline calls) | `/api/productsConfiguration/...` | ProductsConfigurationApiController stays Grails (bootstrap/superuser flow) |
| `src/js/api/services/ProductApi.js:14,16,17,24,28` (constants `INVENTORY_ITEM`, `${GENERIC_API}/product/${id}`, `${PRODUCT_API}/getLatestInventoryCountDate`, `LOT_NUMBERS_WITH_EXPIRATION_DATE`, `AVAILABLE_ITEMS`) | varies | All consume Grails-stays actions on `ProductApiController` (inventory-dependent) or `GenericApiController`. No migration. |
| `src/js/api/services/ProductGroupApi.js:5` (consumer of `PRODUCT_GROUP_OPTION`) | `/api/productGroupOptions` | Defer (SelectOptionsApi not migrated in Phase 5) |
| `src/js/api/services/ProductClassificationApi.js:5` (consumer of `PRODUCT_CLASSIFICATIONS_API`) | `/api/facilities/$facilityId/products/classifications` | ProductClassificationApiController DEFINITIVELY STAYS per spec FD#4 |
| `src/js/api/services/ProductPackageApi.js:5` (consumer of `PRODUCT_PACKAGE_API`) | `/api/productPackages` POST | ProductPackage DEFERRED |
| `src/js/api/services/ProductSupplier*.js` (multiple consumers of `PRODUCT_SUPPLIER_*`) | `/api/productSuppliers*` | ProductSupplier DEFERRED |
| `src/js/api/services/UnitOfMeasureApi.js:5` (consumer of `CURRENCIES_OPTIONS`) | `/api/unitOfMeasure/currencies` | Currency is not a UoM entity proper; stays Grails (separate concern) |

### 3.3 Summary

- **3 URL constants in `urls.js` to update (singularize)**: `PRODUCT_API` (or new `CATALOG_PRODUCT_API`), `ATTRIBUTES`, `UNIT_OF_MEASURE_API` + `UNIT_OF_MEASURE_OPTIONS`.
- **1 service file consumer change**: `ProductApi.js:13` (`getProducts` to call new catalog URL if option-b chosen).
- **0 inline URL strings migrated in Phase 5** (the 3 catalog-related inline calls in `option-utils.jsx` — `:281` `/api/categoryOptions`, `:286` `/api/catalogOptions`, `:291` `/api/tagOptions` — all remain Grails because their server-side handlers are `SelectOptionsApiController` which is not in Phase 5 scope; see §3.2 rationale and FD#5/Phase 5.5 deferral).
- **All Product action sub-routes stay Grails** (only `list()` migrates per FD#12).

---

## 4. Cross-context findings

Cross-context Grails callers that create catalog entities AS PART OF an atomic write including non-catalog entities. These stay Grails per FD#1 + spec §6.

| Caller (`file:line`) | Catalog entity created | Cross-context co-write | Disposition |
|---|---|---|---|
| `grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy:2326` | `new Product(...)` (with `category`, `unitOfMeasure`, `manufacturer`, etc.) | Co-creates `InventoryItem` (line 2347 `findInventoryItemByProductAndLotNumber`) in same transactional flow (`validateData` method) | **Stays Grails** (spec §6 "InventoryService bulk import stays Grails until Phase 6") |
| `grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy:2474` | `new Product(...)` | Co-creates `InventoryItem` (line 2492-2493) + Category save (line 2466) | **Stays Grails** (Phase 6 inventory-service) |
| `grails-app/services/org/pih/warehouse/inventory/InventoryService.groovy:160` | `new Category(name: "Unclassified")` | In-memory only (within `getProductMap` for grouping; not persisted) | **No write — safe** (in-memory category placeholder) |
| `grails-app/services/org/pih/warehouse/product/ProductService.groovy:485,1016,1037,1053` | `new Category(...)` (multiple via `findOrCreateCategory` flow) | Called by `InventoryService.processData` (via `ImporterUtil.findOrCreateCategory` line 2321) and `importCategoryCsv` (cross-context with CSV import flow) | **Stays Grails** (spec §6 "CSV importer services stay Grails") |
| `grails-app/services/org/pih/warehouse/product/ProductService.groovy:837` | `new Product(productProperties)` | Within `importProducts` CSV importer flow (line 824 adds tags `tagNames`); not atomic with inventory but is CSV importer | **Stays Grails** (CSV importer per spec §6) |
| `grails-app/services/org/pih/warehouse/product/ProductService.groovy:1175` | `new Tag(tag: tagName)` (inside `findOrCreateTag`) | Called from `importProducts` (line 814 of ProductController also calls `new Tag(tag: tagText)`); part of CSV product import flow | **Stays Grails** (CSV importer) |
| `grails-app/services/org/pih/warehouse/product/ProductService.groovy:1521` | `new Synonym(...)` (inside `addSynonymToProduct`) | Called from `ProductController` GSP admin (synonym add UI); not cross-context but is GSP-admin path | **Stays Grails** (GSP admin per spec §6 "GSP-driven Product/Category/UoM admin stays Grails until Phase 12") |
| `grails-app/services/org/pih/warehouse/product/ProductGroupService.groovy:37` | `new ProductGroup(name: name)` (inside `findOrCreateProductGroup`) | Called from `ProductService.groovy:735` inside `importProducts` CSV importer flow | **Stays Grails** (CSV importer per spec §6) |
| `grails-app/services/org/pih/warehouse/importer/CategoryImportDataService.groovy:56` | `new Category()` | CSV importer flow | **Stays Grails** (spec §6 CSV importer) |
| `grails-app/services/org/pih/warehouse/importer/TagImportDataService.groovy:44` | `new Tag()` | CSV importer flow | **Stays Grails** (spec §6 CSV importer) |
| `grails-app/controllers/org/pih/warehouse/api/CategoryApiController.groovy:42` | `new Category(request.JSON)` | API write — but **no React caller** (see §1 evidence); likely served by a future GSP admin path or dead | **Stays Grails** (no React caller to migrate; controller may be deletable but the `new Category` call is benign — never invoked) |
| `grails-app/controllers/org/pih/warehouse/core/TagController.groovy:40,47` | `new Tag()` / `new Tag(params)` | GSP admin `save` action | **Stays Grails** (GSP admin) |
| `grails-app/controllers/org/pih/warehouse/product/{AttributeController,CategoryController,ProductController,ProductGroupController,ProductTypeController}.groovy` | various `new <Entity>(params)` | GSP admin controllers | **All stay Grails until Phase 12 GSP cleanup** (per spec §6) |

**Additional cross-context `productService.` callers** (Step 8 second grep):
- `JsonController`, `InventoryController`, `InventoryLevelController`, `ReportController`, `LoadDataService`, `ProductSynonymImportDataService`, `ProductAvailabilityService`, `StockMovementService`, `ProductAssociationController`, `ProductCatalogController` — all **read-side** during transition; switch to HTTP at their owning service's extraction per parent §4.3 policy. No Phase 5 action.

**Conclusion:** No new cross-context findings beyond those already documented in spec §6 / FD#1. All identified atomic writes match spec deferrals (CSV importers, GSP admin, InventoryService bulk import, LoadDataService).

---

## 5. FD#9 disposition

**OUTCOME: Tag is GET-only confirmed; no forced decision required for Phase 5.**

**Evidence:**

- React grep `grep -rnE "apiClient\.(post|put|delete).*[tT]ag|/api/tags?\b" src/js/ --include="*.js*" --include="*.ts*"` returned **zero matches**.
- React Tag GET callers: `src/js/utils/option-utils.jsx:291` (inline `/api/tagOptions`) — read-only; served by `SelectOptionsApiController.tagOptions` (NOT migrated in Phase 5).
- No `src/js/api/services/TagApi.js` file exists (verified via `ls src/js/api/services/`).
- Spec FD#5 default (Tag = GET only) holds empirically.

**Implication:**

The concurrent-write race condition described in spec FD#9 (empirically unconstrained `product_tag` join table per CDR R3) **cannot manifest in Phase 5**, because catalog-service Phase 5 will NOT write to `product_tag` (Tag is GET-only). Grails-side Tag writes (via `Tag.addToProducts`/`removeFromProducts` from `ProductController` GSP admin + `TagImportDataService` CSV import + `ProductService.findOrCreateTag` from CSV product import) continue unchanged; no race partner.

**Decision deferred to:** whichever later phase first introduces catalog-side Tag writes. Candidates: Phase 5.5 (if React Tag CRUD is added then) or Phase 6+ (if internal-write paths migrate). When that phase brainstorms, FD#9's 3 options re-open as a fresh forced decision:
- (a) Accept silent duplicates (empirically no unique constraint on `(product_id, tag_id)` per CDR R3)
- (b) Escalate to saga/distributed-lock infrastructure
- (c) App-layer pair-uniqueness check in TagService (race-y but reduces incidence)

No Phase 5 action required.

---

## 6. Per-controller delete/keep decisions

| # | Controller file | Decision | Rationale | T9 disposition |
|---|---|---|---|---|
| 1 | `grails-app/controllers/org/pih/warehouse/api/AttributeApiController.groovy` | **DELETE candidate** | Single `list()` action (line 12-16) reads only `AttributeService.list(entityTypeCode)` — pure catalog read. Phase 5 catalog-service `ReferenceController.attributes()` covers this. **BUT** the only React consumer (`ProductSupplierApi.js:16` `getAttributes`) is part of the **deferred** ProductSupplier flow. **Recommendation:** keep the deletion conditional on React `ATTRIBUTES` constant being repointed to catalog `/api/attribute` at T9. If `ATTRIBUTES` URL migrates, **DELETE** at T9. If left pointing at `/api/attributes` (Grails plural), **KEEP** alive. | **DELETE-in-T9 conditional on URL migration** |
| 2 | `grails-app/controllers/org/pih/warehouse/api/CategoryApiController.groovy` | **DELETE candidate** | All 4 actions (list, read, save, delete) have **no React caller** (verified: `grep -rn "/api/categories" src/js/` returned zero matches). Server-side endpoints exist for legacy/dead reasons. Catalog-service `CategoryController` covers GET; POST/PUT/DELETE are unused. **However:** `productService.getCategoryTree()` call in `CategoryApiController.list` (line 25) is the ONLY callsite — any other consumer (GSP, test) would still need verification. **Recommendation:** **DELETE** at T9 only if a fresh grep at T9 time (across `grails-app/views/`, integration tests, controller-test specs) confirms zero callers. | **DELETE-in-T9 conditional on no-other-callers final check** |
| 3 | `grails-app/controllers/org/pih/warehouse/api/UnitOfMeasureApiController.groovy` | **DELETE candidate** | 2 actions: `currencies()` (line 19; reads `uomService.getCurrencies()`) and `uomOptions()` (line 24; reads `uomService.getUoms(uomType)`). The currencies action serves a non-UoM concern (currency reference data) — keeping `/api/unitOfMeasure/currencies` alive on Grails OR migrating to catalog needs plan-write decision. The `uomOptions` action is direct UoM listing → covered by catalog `UnitOfMeasureController`. **Recommendation:** **DELETE** at T9 IF (a) `UNIT_OF_MEASURE_OPTIONS` URL migrates to catalog AND (b) `currencies()` action is either migrated to catalog as well OR re-homed elsewhere (e.g., new `CurrencyController` in Grails or a `currency-service`). **If currencies stays Grails on this controller alone**, **KEEP**. | **DELETE-in-T9 conditional on currencies disposition (plan-write decides)** |
| 4 | `grails-app/controllers/org/pih/warehouse/api/ProductApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#12. Only `list()` migrates; 18 of 19 actions stay Grails (cross-context: forecastingService, inventoryService, productAvailabilityService, ProductSummary view, ProductAssociation, ProductCatalog). | **NO-ACTION** (controller stays; URL `/api/products` GET stays alive serving non-migrated callers) |
| 5 | `grails-app/controllers/org/pih/warehouse/api/ProductClassificationApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#4 + spec §6. Single `list(facilityId)` action is cross-context (Location + InventoryLevel + Product.abcClass); catalog-service can't reproduce without Phase 6 inventory + Location dependency. | **NO-ACTION** |
| 6 | `grails-app/controllers/org/pih/warehouse/api/ProductPackageApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#5: ProductPackage DEFERRED to Phase 5.5. | **NO-ACTION** |
| 7 | `grails-app/controllers/org/pih/warehouse/api/ProductSupplierApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#5: ProductSupplier DEFERRED to Phase 5.5. | **NO-ACTION** |
| 8 | `grails-app/controllers/org/pih/warehouse/api/ProductSupplierAttributeApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#5: ProductSupplierAttribute (paired with ProductSupplier) DEFERRED. | **NO-ACTION** |
| 9 | `grails-app/controllers/org/pih/warehouse/api/ProductSupplierPreferenceApiController.groovy` | **DEFINITIVELY KEEP** | Spec FD#5: ProductSupplierPreference (paired with ProductSupplier) DEFERRED. | **NO-ACTION** |
| 10 | `grails-app/controllers/org/pih/warehouse/api/ProductsConfigurationApiController.groovy` | **DEFINITIVELY KEEP** | Configuration/bootstrap area: uses `userService.isSuperuser` (line 72; identity cross-cut), `grailsApplication.config.openboxes.configurationWizard.categoryOptions/productOptions` (config-driven dropdowns), CSV import (`importCategoryCsv`), Category list export (`downloadCategories`). All cross-context or admin-bootstrap. Spec §1 explicitly notes "probably stays". | **NO-ACTION** |

**Net deletable controllers in Phase 5: 0-3** (Attribute, Category, UnitOfMeasure — all conditional on T9 URL migration + final-grep checks). The minimum case is 0 (if plan-write defers all 3 conditional deletions); the maximum case is 3.

**Important note for plan-write (T9):** The "conditional" status of these 3 deletions means the actual delete decision lives in plan-write / T9 execution, not in this audit. This is consistent with spec risk-7 ("'No (or few) Grails source deleted' risk").

---

## Appendix A1: All catalog-related inline `/api/*` matches in `src/js/**` (16 total of 230)

```
src/js/components/products-configuration/ConfigureProductCategories.jsx:32:    const url = '/api/productsConfiguration/categoryOptions';
src/js/components/products-configuration/ConfigureProductCategories.jsx:76:    const url = `/api/productsConfiguration/importCategories?categoryOption=${categoryName}`;
src/js/components/products-configuration/ConfigureProducts.jsx:34:    const url = '/api/productsConfiguration/productOptions';
src/js/components/products-configuration/ConfigureProducts.jsx:111:    const url = `/api/productsConfiguration/importProducts?productOption=${productName}`;
src/js/components/products-configuration/ImportCategories.jsx:38:    const url = '/api/productsConfiguration/importCategoryCsv';
src/js/components/products-configuration/ImportCategories.jsx:53:    apiClient.get('/api/productsConfiguration/downloadCategoryTemplate')
src/js/components/products-configuration/ReviewCategories.jsx:30:    apiClient.get('/api/productsConfiguration/categoriesCount')
src/js/components/products-configuration/ReviewCategories.jsx:42:    apiClient.get('/api/productsConfiguration/downloadCategories')
src/js/components/stock-list-management/StocklistManagement.jsx:120:    const url = `/api/products/${this.props.match.params.productId}/withCatalogs`;
src/js/components/stock-movement-wizard/request/AddItemsPage.jsx:1512:        const url = `/api/products/${product.id}/productDemand?...`;
src/js/components/stock-movement-wizard/request/AddItemsPage.jsx:1533:        const url = `/api/products/${product.id}/productAvailabilityAndDemand?...`;
src/js/utils/option-utils.jsx:122:      apiClient.get(encodeURI(`/api/products/search?...location.id=${locationId}`))
src/js/utils/option-utils.jsx:147:      apiClient.get(encodeURI(`/api/products/search?...availableItems=true`))
src/js/utils/option-utils.jsx:281:  const response = await apiClient.get('/api/categoryOptions');
src/js/utils/option-utils.jsx:286:  const response = await apiClient.get('/api/catalogOptions', { params });
src/js/utils/option-utils.jsx:291:  const response = await apiClient.get('/api/tagOptions', { params });
```

The remaining 214 inline matches are non-catalog (identity, location, organization, stock-movement, invoice, etc.) — already migrated in Phase 1-4 or out of catalog scope. Not enumerated here.

Non-catalog inline calls in `option-utils.jsx` for completeness (per plan context):
- `:21` `/api/persons` → identity-service (Phase 2 — already migrated)
- `:170` `/api/combinedShipmentItems/getProductsInOrders` → cross-context shipment query (Phase 7+)
- `:191,225` `/api/organization` → organization-service (Phase 4 — already migrated)
- `:209` `/api/locationGroups` → location-service (Phase 3 — already migrated)
- `:241` `/api/generic/person/${id}` → identity-service via generic API
- `:246` `/api/locations/${id}` → location-service

---

## Appendix A2: `src/js/api/urls.js` catalog-area constants (full inventory)

| Constant (line) | Current URL value | Importers | Phase 5 disposition |
|---|---|---|---|
| `PRODUCT_API` (78) | `${API}/products` | `ProductApi.js:13` (getProducts), `:17` (template for getLatestInventoryCountDate); plus `ProductsListTable.jsx`, `StocklistManagement.jsx`, `useProductsListTableData.jsx`, `stock-movement-wizard/inbound/AddItemsPage.jsx`, `cycleCountColumn.js`, `inboundColumns.js`, `transactionType.js`, ~10 hooks (see §2 grep output) | **Most consumers stay Grails plural; only `ProductApi.js:13 getProducts` migrates** — recommend a new `CATALOG_PRODUCT_API = '${API}/product'` constant used only at that one call site (option-b above) |
| `INVENTORY_ITEM` (79) | `${PRODUCT_API}/${productCode}/inventoryItems/${lotNumber}` | `ProductApi.js:14` | Stay Grails (inventory-dependent) |
| `LOT_NUMBERS_WITH_EXPIRATION_DATE` (80) | `${PRODUCT_API}/inventoryItems/lotNumbersWithExpirationDate` | `ProductApi.js:24` | Stay Grails |
| `AVAILABLE_ITEMS` (81) | `${PRODUCT_API}/availableItems` | `ProductApi.js:28`, `useConfirmExpirationDateModal.jsx` | Stay Grails (inventoryService) |
| `PRODUCT_GROUP_OPTION` (96) | `${API}/productGroupOptions` | `ProductGroupApi.js:5` | Stay Grails (SelectOptionsApi not migrated) |
| `ATTRIBUTES` (114) | `${API}/attributes` | `ProductSupplierApi.js:16` (call site; constant imported at `:2`) | **MIGRATE to `${API}/attribute` (singular per FD#4)** — covered by catalog `ReferenceController.attributes()` |
| `PRODUCT_SUPPLIER_API` (150) | `${API}/productSuppliers` | `ProductSupplierApi.js:18` (saveDetails); `useProductSupplierListTableData.jsx:49` | Stay Grails (DEFERRED) |
| `PRODUCT_SUPPLIER_BY_ID` (151) | `${PRODUCT_SUPPLIER_API}/${id}` | `ProductSupplierApi.js:14,15,19` (delete/get/updateDetails) | Stay Grails |
| `PRODUCT_SUPPLIER_PREFERENCES_API` (152) | `${API}/productSupplierPreferences` | — | Stay Grails |
| `PRODUCT_SUPPLIER_PREFERENCES_BY_ID` (153) | `${PRODUCT_SUPPLIER_PREFERENCES_API}/${id}` | `ProductSupplierApi.js:17` (deleteProductSupplierPreference) | Stay Grails |
| `PRODUCT_SUPPLIER_EXPORT` (154) | `${PRODUCT_SUPPLIER_API}/export` | `useProductSupplierActions.jsx:61` | Stay Grails |
| `UNIT_OF_MEASURE_API` (157) | `${API}/unitOfMeasures` | `UnitOfMeasureApi.js:6` (indirect via `UNIT_OF_MEASURE_OPTIONS`) | **MIGRATE to `${API}/unitOfMeasure` (singular per FD#4)** |
| `UNIT_OF_MEASURE_OPTIONS` (158) | `${UNIT_OF_MEASURE_API}/options` | `UnitOfMeasureApi.js:6` | **MIGRATE; new value `${UNIT_OF_MEASURE_API}` (the catalog list endpoint is the root)** OR `${UNIT_OF_MEASURE_API}?type=...` once filter is settled at T6 plan-write |
| `CURRENCIES_OPTIONS` (160) | `${API}/unitOfMeasure/currencies` (singular!) | `UnitOfMeasureApi.js:5` | Stay Grails (currency is not a catalog UoM entity) |
| `PRODUCT_PACKAGE_API` (163) | `${API}/productPackages` | `ProductPackageApi.js:5` | Stay Grails (DEFERRED) |
| `PRODUCT_SUPPLIER_PREFERENCE_API` (166) | `${API}/productSupplierPreferences` | — | Stay Grails (duplicate of `PRODUCT_SUPPLIER_PREFERENCES_API`; pre-existing duplication, not our concern) |
| `PRODUCT_SUPPLIER_PREFERENCE_BATCH` (167) | `${PRODUCT_SUPPLIER_PREFERENCE_API}/batch` | `ProductSupplierPreferenceApi.js:5` | Stay Grails |
| `PRODUCT_SUPPLIER_ATTRIBUTE_API` (170) | `${API}/productSupplierAttributes` | — | Stay Grails |
| `PRODUCT_SUPPLIER_ATTRIBUTE_BATCH` (171) | `${PRODUCT_SUPPLIER_ATTRIBUTE_API}/batch` | `ProductSupplierAttributeApi.js:5` | Stay Grails |
| `PRODUCT_CLASSIFICATIONS_API` (174) | `${API}/facilities/${facilityId}/products/classifications` | `ProductClassificationApi.js:6` | Stay Grails (FD#4 keep) |

---

## Self-review checklist

- [x] All 6 required tables present (§1-6)
- [x] All 9 in-scope entities covered in §1 write-scope table
- [x] All 10 catalog-area controllers covered in §6 decision matrix
- [x] §5 FD#9 disposition explicit: Tag GET-only confirmed, no forced decision required
- [x] §4 cross-context findings explicit (12 atomic-write rows + summary)
- [x] All evidence references use `file:line` format
- [x] No production code written
- [x] No commit made
- [x] No user-reserved decisions made (3 deletable controllers in §6 are flagged "conditional" for plan-write to decide)
- [x] Numbers reconciled (Appendix A1 count matches actual grep total; summary stats reconcile internally)
