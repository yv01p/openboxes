# Synthetic-payload blind spot

Lessons from frontend-facing API migrations where test payloads did not match real SPA request/response shapes.

## Synthetic-payload blind spot (Phase 5.5)

A frontend-facing API migration is proven only when integration tests and e2e tests exercise the **real** payload shapes — the actual request the SPA sends and the actual response shape it reads. Testing against synthetic JSON shaped to match the new backend contract creates a blind spot that masks live regressions while keeping CI green.

**What went wrong**: Phase 5.5 T2 migrated `ProductSupplier` to catalog-service with a flat FK-only DTO (`productId`, `supplierId`, `manufacturerId` as raw `char(38)` id strings) and repointed nginx so `/api/productSuppliers` resolves to catalog-service. The integration tests and Playwright e2e used **synthetic flat JSON** that matched the new DTO but never matched the real React ProductSupplier form's payload shapes:

- **Read side.** The form loads nested objects: `productSupplier.product.{id,name,productCode}`, `productSupplier.supplier.{id,name,code}`, `productSupplier.manufacturer.{id,name}`, plus nested `productSupplierPreferences` (`useProductSupplierForm.js:62-95`). The flat DTO returns ids only, so every dropdown loaded empty on edit.
- **Write side.** The form saves using **Grails association-name keys** (`product`, `supplier`, `manufacturer`, `ratingTypeCode` as id strings — `buildDetailsPayload:214-217`), not the flat DTO's `productId`/`supplierId`/`manufacturerId`. So writes to catalog-service failed.

Both the catalog integration tests and the Playwright e2e posted synthetic flat JSON like `{"name":"...","productId":"p-bandage","supplierId":"..."}` — exactly the shape the new DTO accepts, but **not** the shape the real React form sends. CI passed. The live form broke.

The regression surfaced only when the migrated contract went live. A design correction was required to reconcile the frontend seam with the flat catalog contract (see `docs/specs/2026-05-31-phase-5.5-write-contract-reconciliation-design.md` §1 Problem, §2 Decision).

**Rule:** A frontend-facing migration (any plan task that migrates an API endpoint called by React/SPA code and repoints nginx/routing to the new service) MUST verify the new contract against the **real** frontend payload shapes. At minimum:

- **Load path**: the integration test MUST deserialize the new service's response using the exact accessors the real React code uses (e.g., `response.product.name`, not a synthetic flat `response.productId`). If the React code reads nested objects, the test must assert those nested paths exist and are populated.
- **Write path**: the integration test MUST POST the exact payload structure the real React save logic constructs (e.g., if the SPA sends `{"product": "<id>", "supplier": "<id>"}`, the test must send that, not synthetic `{"productId": "<id>", "supplierId": "<id>"}`).
- **E2E tests**: if using non-browser e2e (e.g., Playwright in `request.get()` mode instead of UI-driving mode), the test payload shapes must be extracted from the real SPA code, not hand-crafted to match the new backend.

**Verification:** Before approving a plan that migrates a frontend-facing endpoint:

1. Identify the React component(s) that call the endpoint (grep for the URL constant in `src/js/`).
2. Read the component's load logic: enumerate the response accessors (e.g., `data.product.name`, `data.supplier.code`).
3. Read the component's save logic: enumerate the request payload keys (e.g., `buildPayload` helper return shape).
4. Cross-check the plan's integration-test steps: do the test payloads match (2) and (3) exactly, or are they synthetic shapes that only match the new DTO?

If the test payloads are synthetic, the plan must be revised to use real SPA shapes before approval.

**Related blind spot:** The catalog integration test harness uses `ddl-auto=create` (regenerates schema from the entity), which can mask schema divergences between Hibernate-generated DDL and the actual production schema. This is a distinct (but related) issue — see `sdd-reviewer-checklist.md` § "JPA inheritance + nullability (Phase 4 RC-1)", § "Schema CHAR/TINYINT divergence (Phase 5 RC-1)". The present lesson focuses specifically on **payload-shape** mismatches (synthetic vs real SPA request/response), not schema mismatches.
