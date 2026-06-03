# Critical Design Review: 2026-06-03-phase-6-inventory-service-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-06-03-phase-6-inventory-service-design.md`
**Verified Assumptions section:** present

## 1. Verified-assumptions cross-check

Fresh read against cited evidence; cited evidence is unchanged since Round 1, so no re-litigation.

- **A1–A10, A13, A15, A16, A17–A28, A29–A31 — hold.** No change to the cited files since Round 1's checks (entity inventory; `product_availability` table-not-view; sole-writer `ProductAvailabilityService`; RC-16 service reads + local `facility→inventory` via `Inventory hasMany InventoryLevel` ⇒ `inventory_id` FK; no inheritance; audit fields; ports; starter; shadow-changelog pattern; direct-JDBC consumers).
- **A14 — holds.** The deletion footprint Round 1 flagged (UrlMappings:139-140, `ProductClassificationDto`, the 2 integration specs) is now enumerated in the spec (FD#5, T7); the service still has no non-controller caller.
- **A11 — now accurate.** The row that failed in Round 1 has been corrected to "❌ corrected (CDR R1 §1)" and now states the true fact (endpoint works for a valid facility per `ProductClassificationApiCRUDSpec.groovy:42-52`; 500 is the invalid-facility guard). Under fresh read this matches reality. The corrected decision is recorded as V5. Resolved → §4.

## 2. Literal-wrongness findings

No literal-wrongness findings.

(Checked this round and dropped as non-findings: (a) ProductAvailability's GORM `quantityNotPicked formula:` derived field — if mapped as a `@Column` it has no `quantity_not_picked` table column, but the design's own `ddl-auto=validate` gate catches that at startup; the correct mapping is `@Formula`. Not a literal-wrongness of the design — its safety mechanism handles it. (b) The mid-path-variable nginx route `/api/facilities/$facilityId/products/classifications` is routable via a regex `location` and is already governed by the spec's Rule-3 audit instruction; mechanism choice is implementation-level. (c) The 7 owned-but-unread entities are still exercised by `ddl-auto=validate` at startup, so "prove every mapping" holds even with no endpoint.)

## 3. Forced decisions

No forced decisions found.

(The remaining open items in the spec — snapshot/count/audit default-DEFER, the inventory-transactions-summary consumed-GET candidate, and the invalid-facility error contract — are each a documented decision with a stated default/pick + a T1 confirmation step, not an unpicked either/or. The invalid-facility contract is settled by "port the integration tests as the contract" (which assert 500); the test's `// TODO: ...400` is explicitly not taken.)

## 4. Previously addressed

Round 1 findings now resolved by the current spec state:

- **§1 A11 (failed) → resolved.** The "pre-existing 500 bug" premise is corrected throughout (header, TL;DR, FD#5, §7 risk, §8 A11) and recorded as V5; RC-16 is reframed as a behavior-preserving migration of a working, live-consumed endpoint.
- **§2.1 (literal-wrongness: "no working behavior to byte-match") → resolved.** The §7 risk bullet, FD#5, T7, and T9 now require preserving the exact `{data:[{name}]}` shape + union/facility-scoping + invalid-facility error, with the existing Grails integration tests ported as the behavior contract and the seeded round-trip seeding both global-Product and facility-scoped-InventoryLevel `abcClass` (incl. an excluded second-facility row).
- **§3.1 (forced decision: keep in core vs defer to 6.5) → resolved.** User chose keep-in-core (behavior-preserving); recorded as V5 and reflected in the RC-16 framing.

## 5. Recommendation

✅ **Approve as-is** — §2 and §3 are both empty. The Round 1 findings are resolved (§4); the corrected RC-16 framing holds under fresh read. Spec is ready for implementation planning (`thorough-writing-plans`).
