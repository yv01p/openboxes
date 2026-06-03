# Synthetic-payload blind spot

Lessons from frontend-facing API migrations — proving the migrated contract against **reality**, not against the new code's own expectations: real SPA payload shapes (request + response, RC-43), the full consumer round-trip at cutover (RC-44), and real seeded data for write paths (RC-45).

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

## Cutover is a verification task, not wiring (Phase 5.5 RC-44)

A "cutover" (flipping the SPA/nginx seam to the migrated service) is NOT frontend wiring on top of a finished backend — it is a verification task whose job is to prove the COMPLETE consumer round-trip against real payloads and to fix whatever that proof reveals.

**What went wrong**: Phase 5.5 CUT was scoped as "switch the React seam + repoint nginx", on the premise that the T2–T5 ProductSupplier write-cluster was complete. The real-payload ground-truth trace (run BEFORE coding, per the synthetic-payload lesson above) found three gaps the plan assumed didn't exist:

- **LQ2** — the flat list DTO omitted 6 columns the live Grails list serves (`packageSize`/`packagePrice`/`unitPrice` + preferences); the Preference Type column would have crashed on `undefined.length`.
- **PKG-FIX** — the catalog ProductPackage save was create-only and never linked `defaultProductPackage`, so the form's save→reload round-trip was broken (the form POSTs the package on BOTH create and edit; editing 409'd on the duplicate-tuple check).
- **Attr load + Jackson** — the form would have fetched the whole `product_attribute` table (no `?productSupplier=` filter), and the package payload carried fields not on `ProductPackageDto`, which catalog's default-strict Jackson (`FAIL_ON_UNKNOWN_PROPERTIES`) rejects with 400.

None were in the plan; all three would have shipped broken under a wiring-only execution.

**Rule**: a cutover/seam-flip task's first step is a real-payload ground-truth trace of the FULL round-trip the consumer drives — load → save → reload → list → edit — against the new service. The task OWNS fixing whatever the trace reveals, including backend gaps the upstream per-entity tasks were assumed to have closed. Plan it as verification-and-remediation, never as wiring. When the "upstream is complete" premise turns out false, re-spec it (a falsified load-bearing assumption is a design defect) rather than patching the cutover task. (Plan-level codification: `plan-template-defects.md` § "Cutover/seam-flip premise".)

## Empty DB hides every write path (Phase 5.5 RC-45)

When the live/dev database has no rows for the entities under test, the write/UI e2e surface self-skips (suite convention), so a skip-heavy "all green" is a FALSE NEGATIVE for write-path correctness — and it can also hide a stale deployed container.

**What went wrong**: `openboxes-db` holds 0 products/suppliers, so the standard ProductSupplier write/UI Playwright specs self-skip. The catalog integration tests (their own `ddl-auto=create` seed) didn't model the form's two-POST save sequence. The PKG-FIX package round-trip was therefore unproven by BOTH green CI surfaces; it was caught only by a self-seeding round-trip e2e (`e2e/tests/catalog-product-supplier-roundtrip.spec.ts`) that seeded a minimal fixture (product/uom/party/preference_type), drove the REAL flat payloads through nginx, asserted `defaultProductPackageId` + derived pricing + preferences + upsert, then self-cleaned. That spec ALSO surfaced that the deployed catalog container was stale (built before LQ2/PKG-FIX).

**Rule**: for any write-path migration verified against an empty live DB, a self-seeding round-trip e2e is MANDATORY — seed a minimal fixture, exercise the real SPA payloads end-to-end through the routing seam, assert the persisted shape, and self-clean. Do NOT accept skip-heavy green as write-path proof; a passing suite where the write specs all skipped has proven nothing about writes. After any backend change, rebuild + recreate the service container before running the round-trip (empty-DB green can mask a stale image).

## Error-status contracts can't be proven by MockMvc (Phase 6 RC-58)

A MockMvc test asserting an error status (e.g. 500 for invalid input) can PASS even when the real servlet container returns a different status — Spring Security re-intercepts the internal `/error` dispatch and can mask the true status as 401 (see `sdd-reviewer-checklist.md` § RC-60). MockMvc does not replicate the container's error-dispatch-through-Spring-Security path.

**Rule**: error-status assertions go at the **service** level (`assertThrows`) or against the **real container** (e2e), never MockMvc-only.

**Rationale**: Phase 6 — RC-16's invalid-facility "default Spring 500" was actually a 401 in the real container; found via T5 real-container e2e, not MockMvc. Bug fixed at `50835b52a`. This generalizes the synthetic-payload blind spot to error paths.

**Verification**: for any test asserting a non-2xx, non-handled status, confirm it runs at service level or against the real container; a MockMvc-only error-status assertion is insufficient proof.
