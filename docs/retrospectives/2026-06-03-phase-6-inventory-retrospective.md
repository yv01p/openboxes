---
date: 2026-06-03
phase: 6 (core) — Inventory slice
tag: phase-6-inventory
commit_range: d28e3096e..<this commit>
plan: docs/plans/2026-06-03-phase-6-inventory-service-implementation-plan.md
spec: docs/specs/2026-06-03-phase-6-inventory-service-design.md (FD#1–11, A1–A31, V1–V5)
forward_to: Phase 6.5 (inventory writes + CycleCount family + 2 parent-design restructures + RC-13 remainder — see §"Forward")
---

# Phase 6 (core) Inventory Slice — Retrospective

## TL;DR

Phase 6 core stood up **inventory-service** as the 6th Spring Boot service (9th container, port 8086) — a **read-only** slice that owns 8 inventory JPA entities (Inventory, InventoryItem, InventoryLevel, ProductAvailability, Transaction, TransactionEntry, TransactionSource, TransactionType; flat FK-only per FD#6, `ddl-auto=validate` proving all 8 against the live Grails schema) and migrates exactly one live read — **RC-16** `GET /api/facilities/{facilityId}/products/classifications` — behavior-preserving. RC-16 returns the sorted/deduped/non-empty UNION of global `Product.abc_class` (fetched from catalog-service over HTTP, forwarding the caller's `obx_token`) ∪ facility-scoped `InventoryLevel.abc_class`. All inventory **writes** + the CycleCount family + the two parent-design restructures stay on Grails → Phase 6.5 (FD#2/§6).

The phase's substance was **two empirical corrections caught by DESCRIBE-first discipline before they could ship as bugs**, both now permanent lessons:
1. **`inventory.warehouse_id` does not exist** (PA19/spec-V1/A16 were FALSE — the `:3659` evidence was the `user` table's column, not `inventory`'s). The real facility→inventory link is `location.inventory_id`. User-approved resolution: **Option B** — a transitional native read of `location.inventory_id` (no `Location` JPA entity, FD#6 preserved; FD#10 cross-context read → becomes an HTTP call at location-service extraction).
2. **The "default Spring 500" on an invalid facility was actually a 401** in the real servlet container — Spring Security re-intercepts the internal `/error` dispatch and masks the true 500. Fixed with `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`. Found via T5 real-container e2e, **not** MockMvc (a fresh instance of the synthetic-payload blind spot).

Execution was **subagent-driven (SDD) with a user-enforced stop-after-every-task gate** and a **two-stage review** per task (controller independent re-verification + a code-quality reviewer subagent). The two-stage review earned its keep at T6, catching a genuine **false-confidence test** (the union fixture's insertion order coincided with its sorted order, so a non-sorting implementation would have passed). 

**13 SDD/doc commits** across `d28e3096e..<this commit>` (design CDR×2 + plan CIR + T2–T8 task commits incl. 3 follow-ups: the T4 ERROR-dispatch fix, the T6 sort-fixture review-fix, the T7 seed-precondition review-fix). T9 done-gate passed with **no fix needed** (full rebuild, 9/9 healthy, RC-16 union 200, `validate` clean, Playwright 72 passed / 12 skipped / 0 failed, both build toolchains green, strangler-bite end state confirmed: Grails-direct 404s the deleted endpoint while nginx still serves it via inventory-service). Tag `phase-6-inventory` at this commit.

## What worked

- **DESCRIBE-first paid for itself twice — both corrections would otherwise have shipped broken.** The `inventory.warehouse_id` non-existence was caught at T3 by running `DESCRIBE inventory` against the live DB instead of trusting the plan's PA19 evidence line. Had `InventoryRepository.findByWarehouseId(...)` been written per the original plan, T3's `ddl-auto=validate` would have failed loudly — but the *resolution* (Option B vs. mapping a Location entity) needed a user decision, which the early catch surfaced before any code was committed against the wrong premise. The schema-drift discipline carried from Phases 1–5 (CHAR(38)/TINYINT divergence, `@ElementCollection` inner-column names) generalized cleanly to "don't trust a migration line's column attribution either."

- **The two-stage review caught a false-confidence test at T6 that a green run hid.** The first T6 fixture mocked global `{A,B}` ∪ facility `{A,C}` → the union's only natural insertion order (`[A,B,C]`) *equalled* the sorted order, so `contains("A","B","C")` could not distinguish a `TreeSet` from a `LinkedHashSet`. The code-quality reviewer flagged it; the fix made the fixture **sort-distinguishing** (mock `{B,D}` ∪ facility `{A,D}` → sorted `[A,B,D]` ≠ insertion `[B,D,A]`), and additionally un-hollowed the entity round-trip (assert a distinctive non-id getter per repo-less entity, not just `isNotNull`). Lesson generalized below (RC-3).

- **The Grails-direct ground-truth comparison was the strongest behavior-preservation proof available.** At T7, with the seed applied, BOTH inventory-service (via nginx) and the still-present Grails endpoint (hit directly at `:8080/openboxes/api/...`) returned **byte-identical** `{"data":[{"name":"A"},{"name":"B"},{"name":"D"}]}`. This is a better regression guard than asserting against a hand-written expectation — it pins the migrated output to the *original's actual* output. (Correctly NOT committed as a cross-call, since T8 deletes the Grails side; the comparison was a one-time verification artifact.)

- **Controller-side empirical groundwork before dispatch de-risked the environment-coupled tasks.** Before dispatching T7, the controller discovered live that the dev/CI DB is **empty of products/inventory_levels** (so the seed must INSERT, not UPDATE), that the minimal NOT-NULL footprint is tiny (`information_schema` → product needs only id/version/name/dates; inventory_level only id/version), that nginx routes RC-16 to inventory-service while `/api/` falls through to Grails, and **proved the seeded union end-to-end** — then handed the implementer a prompt with verified facts and exact expectations. The implementer shipped a green spec first try. For infra/data-coupled tasks, the controller doing the discovery beats a fresh subagent reconstructing it.

- **Proactive cross-spec interference check at T7.** The abc_class seed adds 2 `product` rows to a previously-empty e2e DB, which runs before the *whole* Playwright suite. The controller checked whether this breaks other specs and found it **beneficially un-skips** `catalog-product-readonly.spec.ts`'s by-id tests (catalog-service tolerates the minimal null-FK products) — then verified they pass. A shared-DB seed is a suite-wide change; treating it as local would have been a latent CI surprise.

- **Per-task STOP gate + follow-up-commit (not amend) discipline held across T6–T8.** Three review-driven fixes landed as NEW commits (`50835b52a` T4 ERROR-dispatch, `0f4b40063` T6 sort-fixture, `21e92354b` T7 seed-precondition) preserving the audit trail of what was caught at which gate. The convention has now held through Phases 3–6.

- **`ddl-auto=validate` against the live schema is the right mapping proof for a read-only slice.** T3 proved all 8 entity mappings at build time; T9 re-proved them in-container (inventory-service started clean, no `Schema-validation` error). The only T3 fix was `InventoryLevel.preferred` needing `columnDefinition="TINYINT"` (the CHAR/TINYINT pattern, now 6-of-6 services) and `ProductAvailability.quantityNotPicked` as `@Formula` (derived, no column).

- **T9 done-gate honored the Phase 4 conditional-rebuild lesson correctly.** Only Grails source changed since the last clean build (T8 deletions); the service bootJars were up-to-date (6s, all UP-TO-DATE) while the Grails WAR genuinely needed `prepareDocker` (to drop the deleted endpoint). Rebuild was warranted for the changed artifact, cheap for the unchanged ones — the "rebuild iff the deliverable's code changed" rule applied without ceremony.

## Codebase / env gotchas (Phase 6.5+ should know)

### Schema & cross-context

- **`inventory` has NO warehouse FK.** Columns: `id, version, last_inventory_date, date_created, last_updated`. The facility→inventory link is `location.inventory_id` (on the location-context `location` table). Any future inventory work resolving a facility's inventory MUST go through `location.inventory_id`, not `inventory`.
- **Option B native read is live tech-debt (FD#10).** `InventoryRepository.countLocationById` / `findInventoryIdByFacility` issue native SQL against the `location` table (no `Location` entity, no shadow changelog, no `validate` coverage for `location`). This is transitional — it becomes an HTTP call to location-service when that service is extracted (Phase 7/8). The 3-case guard (no `location` row → throw → 500; `location` row with NULL `inventory_id` → global-only, no error; else union) reproduces Grails `ProductClassificationService.list()` exactly.
- **`product_classification` (DB view) is a benign name-collision, NOT this surface.** A per-(inventory,product) ABC view feeding the cycle-count view chain (`grails-app/migrations/views/product-classification.sql` + consumers). The deleted GORM `ProductClassificationService` never read it. Leave it; don't mistake it for an orphan.
- **`abcClass` stays a `Product` column** (not its own entity); catalog-service serves the global set via `GET /api/products/abcClasses` (distinct, non-empty).

### Test & e2e

- **MockMvc does NOT replicate the real container's error-dispatch-through-Spring-Security.** A MockMvc test asserting `status 500` for an invalid facility can PASS even if `SecurityConfig` would 401-mask it in production. Error-status contracts must be proven against a real container (T7/T9) or asserted at the **service** level (`assertThrows`), never MockMvc-only.
- **Local Playwright runs need `sg docker` when any spec shells out to `docker exec`.** `catalog-product-supplier-roundtrip.spec.ts` seeds via `execSync('docker exec openboxes-db mysql ...')`; without docker-group access in the shell it fails at its own `sql()` helper (NOT a product bug). Run the suite as `sg docker -c 'BASE_URL=http://localhost npx playwright test'`. CI runners have docker access, so this is local-only.
- **The dev/CI DB is empty of product/inventory_level rows.** A non-empty e2e assertion REQUIRES a seed; the RC-16 read-through seed (`docker/seed-rc16-abc-class.sql`, applied after `init-baseline.sql` in CI) is idempotent (`rc16-%` ids, delete-first) and FK-depends on the BootStrap demo `inventory` rows 1/2 + `location 1` "Main Warehouse".
- **catalog-service tolerates minimal null-FK products** (flat DTO returns nulls + empty id-arrays), so seeding bare products does not break catalog read specs — it un-skips them.

### Build

- **RC-55 toolchain split held:** Grails via Temurin 8 (`JAVA_HOME=/usr/lib/jvm/temurin-8-jdk-amd64 ./gradlew prepareDocker -Dgrails.env=prod`, Gradle 4.10.3); services via JDK 21 (`cd services && ./gradlew :inventory-service:bootJar`). Gradle TestContainers runs need `sg docker -c './gradlew --no-daemon ...'` (the `--no-daemon` avoids a stale daemon started without docker-group access).
- **Service Docker images COPY pre-built `build/libs/*.jar`; the Grails image COPYs `build/docker/openboxes.war`** (produced by `prepareDocker`). `docker compose up --build` rebuilds image layers around those artifacts — so the artifacts must be built FIRST, or the image bakes a stale binary (the T9 trap: the pre-rebuild WAR still contained the deleted endpoint).

## Retrospective candidates — A–F triage

Legend (per Phase 4 retro): **A** codify lesson (high recurrence) · **B** accumulating debt (worse per phase) · **C** 1-line atomic cleanup · **D** intentional parity → DELETE with rationale · **E** true Phase X (future-blocked) · **F** defensible style → DELETE.

| # | Candidate | Cat | Disposition |
|---|-----------|-----|-------------|
| RC-57 | **Changelog evidence misattribution**: PA19 cited `changelog-create-tables.groovy:3659 warehouse_id` as `inventory`'s column; it belonged to the `user` table (`createTable(tableName:"user")` at `:3645`). | **A** | Codify in `docs/process/sdd-reviewer-checklist.md` + plan-template: when a plan cites a changelog line as a column's origin, verify the *enclosing* `createTable` block, not the nearest line — and confirm with `DESCRIBE <table>`. FIXED in-phase (Option B); lesson codified in 6.5. |
| RC-58 | **Error-status contracts can't be proven by MockMvc** (Spring Security masks the `/error` dispatch as 401). | **A** | Extend `docs/process/synthetic-payload-blind-spot.md` with the error-dispatch case; add a checklist line: error-status assertions go at service level (`assertThrows`) or real-container, never MockMvc-only. Bug FIXED in-phase (`50835b52a`); codify in 6.5. |
| RC-59 | **Discriminating-fixture rule**: a green test whose fixture insertion-order coincides with the expected sorted/transformed order does not prove the transform. | **A** | Add to `sdd-reviewer-checklist.md` a mandatory reviewer question: *"could this test pass against a deliberately-broken impl?"* — for sort/dedup/filter, the fixture must make correct-output ≠ any naive-output. FIXED in-phase (`0f4b40063`); codify in 6.5. |
| RC-60 | **catalog-service `SecurityConfig` has the SAME latent /error-401 masking** (identical clone; currently dodged only because catalog routes errors through a `GlobalExceptionHandler`). Every cloned service inherits the trap. | **B** | Phase 6.5: harmonize error handling across all 6 services — standardize on EITHER the ERROR-dispatch permit OR a `GlobalExceptionHandler`, and add the chosen pattern to the service-clone template. |
| RC-61 | **JWT/cookie test-helper duplication** (`validToken()`/`authCookie()` copy-pasted into each service's integration test). | **B** | Phase 6.5 (or alongside the next service): extract a shared test-fixture (a `jwt-auth-common` test artifact) so the helper isn't re-copied a 7th time. |
| RC-62 | **`sg docker` requirement for local Playwright / Gradle TestContainers** keeps biting (specs that `docker exec`; daemon started without docker group). | **A** | One-line codification in `docs/process/dev-env-setup.md`: local e2e + TestContainers commands must be `sg docker -c '...'` (and `--no-daemon` for Gradle). |
| RC-63 | **Controller-side empirical groundwork before dispatching environment-coupled tasks** (T7 data/route discovery + live proof) produced a first-try-green implementer run. | **A** | Codify as an SDD practice: for infra/data-coupled tasks, the controller de-risks the data/infra layer and hands the implementer verified facts + exact expectations, rather than dispatching into the unknown. |
| RC-64 | **Option B transitional native `location.inventory_id` read** (FD#10) becomes an HTTP call at location-service extraction. | **E** | Phase 7/8 (blocked on location-service extraction). Explicit dependency, not a graveyard placeholder. |
| RC-65 | **Minimal seed-against-live-Grails-schema recipe** (use `information_schema` to find NOT-NULL-without-default columns; INSERT only those + the fields under test; idempotent `<prefix>-%` delete-first). | **C** | Note the recipe in `dev-env-setup.md` (or a seeds README) — reusable for every future read-through e2e against the empty dev DB. |
| RC-66 | **`product_classification` DB view name-collision** could be mistaken for an orphan in a future cleanup. | **D** | DELETE from backlog with rationale (documented above + in the T8 reviewer note): it's an unrelated cycle-count view; intentionally retained. |
| RC-67 | **All inventory writes + CycleCount family + 2 restructures + RC-13 remainder** deferred from core. | **E** | Phase 6.5 — see §Forward. Legitimate, spec-§6-scoped deferral. |
| RC-68 | **inventory-service ERROR-dispatch permit vs. catalog GlobalExceptionHandler** as the chosen 500-contract mechanism. | **F** | Defensible per-service choice (both yield the correct 500). The *divergence* is style; the *latent catalog bug* is the real item — tracked as RC-60 (B). No separate action. |

Math: 12 candidates → **3 FIXED in-phase** (RC-57/58/59, with their lessons to codify in 6.5) · **5 codify/cleanup** (A: RC-57/58/59/62/63; C: RC-65) routed to Phase 6.5 · **2 accumulating debt** (B: RC-60/61) to Phase 6.5 · **2 true Phase X** (E: RC-64 Phase 7/8, RC-67 Phase 6.5/6.x) · **1 DELETE-with-rationale** (D: RC-66) · **1 DELETE style** (F: RC-68).

## Forward — Phase 6.5 (and beyond)

Per spec §6 (accepted out-of-scope during brainstorming; a new spec→plan cycle is required to change any):

**Phase 6.5 (inventory writes + cleanup the core revealed):**
- **All inventory writes** stay Grails for now → 6.5+: record stock, adjustments, transactions, cycle count, transfers, bulk import, product merge, availability refresh.
- **Restructure (a)** ProductMergeService → inventory-service + catalog thin delegate; **ProductAvailability refresh** (FD#4) moves with it (shared service).
- **Restructure (b)** InventoryService bulk-import → inventory-service with per-row sync HTTP to catalog (this is why `InventoryApiController.importCsv` stays alive in core).
- **CycleCount family + snapshot/count/audit + LocalTransfer/OutboundStockMovement*/Requirement** → 6.5/6.x (FD#2).
- **RC-13 remainder**: CategoryApiController final deletion / `runtime.groovy:757` fillRate `categoryApi` dependency (FD#11) — separate from RC-16.
- **Codify carry-forward A/B items**: RC-57 (changelog-attribution check), RC-58 (error-status real-container rule), RC-59 (discriminating-fixture gate), RC-62 (`sg docker` doc), RC-63 (groundwork-before-dispatch), RC-65 (seed recipe), and the two debt items RC-60 (harmonize service error handling) + RC-61 (shared JWT test fixture).

**Phase 7/8 (blocked on future extractions):**
- **Cross-context readers stay direct-JDBC** (FD#10): `ProductApiController`, `StockMovementService`, fillRate/`IndicatorDataService`, and inventory-service's own `location.inventory_id` read (RC-64). Switch to HTTP at the owning service's extraction, or never.
- **InventoryItem / ProductAvailability React reads stay on Grails `ProductApiController`** — cross-context (Product + forecasting + inventory); re-open only if a dedicated inventory read API is demanded.
- **`StockMovementService` / `StockTransferService` / `ReplenishmentService` stay Grails** — their cross-context atomic writes need saga consumers that don't exist yet. (V3: StockMovement/StockTransfer/Replenishment have no domain class/table; the parent design's "Liquibase ownership transfer" for them is vacuous.)

**Permanent (by design):** flat FK-only DTO degradation, no cross-service name resolution (RC-48); `abcClass` stays a Product column.
