# Phase decomposition & sequencing (project-local)

Lessons for *defining and ordering the phases themselves* — consult before writing a new phase's design/plan. This is distinct from `plan-ordering-rules.md` (which governs task ordering *within* a single plan); this doc is about how to carve a domain into phases and decide which phase comes next.

Surfaced during the **Phase 6.5 analysis** — a didactic deep-dive that set out to brainstorm the next inventory slice and instead found the inventory phase had been mis-sliced. Student-facing telling: `docs/lessons/2026-06-02_04-00-58_grails-migration-teaching-brief.md` §3f.

## Decompose by runtime coupling, not just data-ownership boundaries (Phase 6.5 analysis)

Slicing by service/context (data ownership) is a fine *top-level* frame, but it can miss the dominant *runtime* coupling. In inventory, writing a `Transaction` fans out to derived-data refreshes (`product_availability` + `InventoryCount` + `InventoryTransactionsSummary`) via in-process GORM lifecycle hooks (`grails-app/domain/org/pih/warehouse/inventory/Transaction.groovy:50-75`). The Phase-6 read/write split cut straight across this coupling and never named it — which is why "Phase 6.5 = inventory writes" had no tractable first slice.

**Habit**: before slicing a domain, map what fires when its core entity is written — events, lifecycle hooks, cascade, DB triggers, jobs. Slice so a slice owns a *whole* coupling, not half of one.

## A phase's scope claims are assumptions — verify them against the code at design time (Phase 6.5 analysis)

The inventory design carried two false load-bearing scope claims: `inventory.warehouse_id` exists (RC-57 — it doesn't) and "the bulk-import does product create-or-find via sync HTTP." The actual `InventoryApiController.importCsv` *rejects* unknown products (`grails-app/services/org/pih/warehouse/importer/InventoryImportDataService.groovy:59-62`; `:366` `assert product != null`); create-or-find is a *different* endpoint (`/api/products/import` → `ProductApiController.importCsv:339`). Two wrong claims about one domain ⇒ the decomposition was done *above the code*.

**Habit**: apply the same empirical-assumption-verification used for specs (the `thorough-brainstorming` discipline) to *phase scope* — read the endpoints/services a phase claims to move, before committing the phase. Catching it at N.5 wastes a whole bucket.

## "Phase N.5 / the rest of X" is a smell, not a slice (Phase 6.5 analysis)

"Phase 6.5 = all inventory writes + 2 restructures + cycle count + RC-13" is a deferral label. It hid two *different* blocker classes under one name: the **intra-inventory refresh keystone** (blocks adjust/import/merge) vs the **cross-service saga** (blocks replenishment/transfer — genuinely Phase 7/8). A real slice has a crisp done-state and one blocker class.

**Habit**: when a phase's scope reads "the rest of X," stop and re-decompose. Resolve the blocker structure (what gates each piece) before naming the phase.

## Sequence by empirically-measured demand, not the roadmap's stated order (Phase 6.5 analysis)

This migration is demand-driven (extract what React consumes). The demand is measurable: nginx routing (`docker/nginx/conf.d/app.conf` — what's already a service) × React's API surface (`src/js/api/urls.js` — what React calls) × git churn (`git log -- src/js/components/*` — where React work lands). That measurement showed the parent design's *recommended* next phase (Ordering) was dormant (`purchaseOrder` last touched 2024-12-12) while the real demand (cycle count ≈253 commits; stock-movement ≈1033) sat unmigrated.

**Habit**: before committing the next phase, compute the live React→Grails coupling and recent churn per domain. Let that — subject to dependency feasibility — pick the next slice, not the roadmap's stated order.

## Name the keystone; classify every deferred write as keystone- vs saga-blocked (Phase 6.5 analysis)

Inventory's keystone is the **ProductAvailability refresh**. Three properties decide how it can move:

- **GORM-event-coupled** to the writer (`Transaction.groovy:50`) — so any inventory-service transaction write must own its own refresh; the Grails GORM hook will not fire for an out-of-process write.
- **Recompute-from-truth** (`ProductAvailabilityService` `calculateBinLocations` → `saveProductAvailability`) — idempotent, so a Grails ↔ inventory-service **dual-writer transition is safe** *provided the two computations are proven behavior-identical* (Grails-direct ground-truth comparison, as in RC-16).
- **No process-independent trigger** (`RefreshProductAvailabilityJob` has `static triggers = {}`; the job only runs when the persistence-event listener fires it) — so there is no safety-net poller to reconcile out-of-process writes.

Consequence: the refresh cannot move alone; move it *with* the first inventory write (smallest real one = `/api/stockAdjustments`, React consumer `AdjustInventoryModal.jsx:162`).

**Habit**: name each domain's keystone explicitly in its phase design, and tag every deferred write as keystone-blocked (intra-service) or saga-blocked (cross-service). They sequence differently.

## The discipline works — pull it earlier (Phase 6.5 analysis)

Empirical verification caught both mis-slices before they shipped as bugs (`warehouse_id` at Phase 6 T3; the import premise during the 6.5 analysis, before any code). The process worked; the only cost was discovering the inventory mis-slice at N.5 rather than at parent-design time. The remedy is the habits above — verify phase scope and coupling when the *phase* is defined, not when its plan is written.
