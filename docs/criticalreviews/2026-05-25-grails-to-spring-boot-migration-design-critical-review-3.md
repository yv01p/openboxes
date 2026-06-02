# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 3)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec)

## 1. Verified-assumptions cross-check

All 20 assumptions (A1–A20) reconfirmed. UDD Round 2 edits touched §3 (line 20), §4.3, §5 row "Grails container runtime", §6 Phase 6 and Phase 7 rows, §7.6 (line 139), §8 header (line 148), and §11 (lines 231, 232, 246) — none modified the cited evidence in §10. Round 1's update to A19's phase-number reference (Phase 13 → Phase 9) was carried forward correctly by Round 2 (A19 still reads "defer frontend decoupling to Phase 9").

## 2. Literal-wrongness findings

### 2.1 The "single eventual-consistency exposure" claim is falsified by other cross-context atomic writes the spec doesn't address

**Description.** §4.3 (after Round 2 UDD applied γ) asserts:

> *"Invoice posting today updates Order status atomically; after extraction this becomes the design's **single** eventual-consistency exposure — finance-service emits an 'invoice posted' event that operations-service consumes (at-least-once, idempotent) to advance Order status."*

The word "single" is load-bearing: it bounds the design's eventual-consistency complexity to ONE event path, telling implementers they need to handle only this one flow with async mechanics. The claim is false — there are at least two additional cross-context atomic writes in the current Grails codebase that γ's groupings (Phase 5 Product, Phase 6 Operations, Phase 7 Finance) break, and which the spec leaves uncovered:

1. **`InvoiceService.deleteInvoiceItem` mutates Order-context and Shipment-context entities atomically.** Lines 317–325 of `InvoiceService.groovy` iterate `invoiceItem.orderAdjustments`, `invoiceItem.orderItems`, `invoiceItem.shipmentItems` and call `removeFromInvoiceItems(invoiceItem)` on each — a Grails `hasMany` link removal that mutates state on OrderAdjustment / OrderItem / ShipmentItem inside the same Grails session/transaction. After γ: InvoiceItem lives in `finance-service`; OrderAdjustment / OrderItem / ShipmentItem live in `operations-service`. This write is not "Invoice posting → Order status"; it's a different cross-service write the spec is silent on.

2. **`ProductMergeService.mergeProduct` performs atomic Product-context + Operations-context writes.** The service injects `inventoryService` and `productAvailabilityService` (lines 33–35) and calls `inventoryService.generateTransactionNumber(transaction)` (line 258) plus `productAvailabilityService.updateProductAvailabilityOnMergeProduct(...)` (lines 279, 302) inside the merge flow that also writes Product itself. After γ: Product lives in `product-service` (Phase 5); ProductAvailability / Inventory transactions live in `operations-service` (Phase 6). The merge today is one Grails transaction; after extraction it cannot be.

A developer implementing the spec literally hits these at Phase 7 (Invoice item deletion) and Phase 5 (ProductMerge) and has no design guidance — the spec's "single exposure" framing tells them they shouldn't need any. These are real cross-context writes that γ's groupings break and that γ's one named event path doesn't cover.

**Evidence.**
- `grails-app/services/org/pih/warehouse/invoice/InvoiceService.groovy:317-325` — `deleteInvoiceItem` mutates `OrderAdjustment`, `OrderItem`, `ShipmentItem` via `removeFromInvoiceItems(invoiceItem)` inside one Grails session.
- `grails-app/services/org/pih/warehouse/product/ProductMergeService.groovy:33-36` — injects `inventoryService`, `invoiceService`, `productAvailabilityService`, `requisitionService`.
- `ProductMergeService.groovy:258` — `inventoryService.generateTransactionNumber(transaction)` inside the merge.
- `ProductMergeService.groovy:279,302` — `productAvailabilityService.updateProductAvailabilityOnMergeProduct(...)` inside the merge.
- Spec §4.3 (line 38): the word "single" in *"the design's single eventual-consistency exposure."*

**Proposed fix.** The resolution is a forced decision (see §3.2 below — the user must pick how to extend γ, restructure, or accept additional eventual-consistency exposures). At minimum, replace "single" with the actual count and enumerate the additional flows; the design's scope-of-complexity claim must be accurate before Phase 5 ships.

### 2.2 The event mechanism for "invoice posted" is unspecified and contradicts the §5 tech-choice constraint

**Description.** §4.3 and §6 Phase 7 done gate both assert that `finance-service` "emits an 'invoice posted' event that operations-service consumes (at-least-once, idempotent)." Async, at-least-once, idempotent delivery is messaging-system semantics; the design says nothing about which messaging system delivers the event. §5 tech choices, in the same spec, says:

> *"Inter-service comms: Synchronous REST over the same nginx routing as the frontend uses; **no message broker until a real async use case appears**."*

γ has introduced exactly such a use case — Invoice posting needing async Order-status delivery is a real async use case — but §5's "no broker" tech choice has not been revisited. The spec is therefore internally contradictory: §4.3 needs an async event channel; §5 forbids one. A developer following both faithfully has no mechanism to implement.

Three mechanisms can satisfy the §4.3 contract without a broker, but the spec picks none:
- **Transactional outbox** (finance-service writes "event" rows to its own tables in the same local transaction; operations-service polls / streams that outbox table; shared MariaDB makes this cheap).
- **Synchronous HTTP with retry + idempotency** (finance-service POSTs to operations-service during invoice posting; on failure, finance-service's local transaction either rolls back, losing the invoice, or commits with a TODO retry, breaking "at-least-once" if the JVM dies).
- **Introduce a broker now** (acknowledge §5's caveat clause has triggered; RabbitMQ or similar).

Without picking, "at-least-once, idempotent" is unimplementable.

**Evidence.**
- Spec §4.3 (line 38): "*finance-service emits an 'invoice posted' event that operations-service consumes (at-least-once, idempotent) to advance Order status.*"
- Spec §6 Phase 7 done gate (line 72): "*Invoice posting emits an 'invoice posted' event consumed by operations-service to advance Order status (eventually consistent — see §4.3).*"
- Spec §5 (line 55): "*Inter-service comms | Synchronous REST over the same nginx routing as the frontend uses; no message broker until a real async use case appears.*"

**Proposed fix.** Pick a mechanism (see §3.1 below — forced decision). Whichever is picked, update §5's "Inter-service comms" row to describe the chosen async mechanism alongside synchronous REST (or to explicitly note that a broker is now in scope).

## 3. Forced decisions

### 3.1 Mechanism for the eventually-consistent event path(s) γ introduces

**The choice.** §4.3 and §6 Phase 7 assert a "invoice posted" event with at-least-once, idempotent delivery semantics. §5 tech choices says no message broker. Some mechanism must reconcile them, and that mechanism has meaningfully different infrastructure and operational properties; the spec must pick before Phase 7 ships (and before any other event-based cross-service write, per §3.2, is implemented).

**Why it's forced.** Phase 7 cannot implement "at-least-once, idempotent" without a delivery mechanism. §5's "no broker" stance cannot stand alongside γ's event semantics without resolution. The first developer to touch Phase 7 has to invent something; the spec should make the choice, not punt to that developer.

**Options.**
- **(a) Transactional outbox in shared MariaDB.** finance-service writes an `outbox_event` row in the SAME local transaction as the invoice posting. A poller in operations-service (or a small dedicated outbox-relay process) reads new rows and applies the Order-status update; rows are marked consumed when the operations-service write returns success. Idempotency is enforced on the operations-service handler keyed on the outbox event ID. No broker added. Tradeoff: poller latency (seconds, not milliseconds); outbox table grows; recovery requires understanding the outbox semantics.
- **(b) Synchronous HTTP with bounded retries + idempotency key.** finance-service POSTs to operations-service inside invoice posting. operations-service rejects duplicate POSTs by idempotency key. On failure, finance-service retries N times with backoff; if all retries fail, finance-service commits the invoice anyway and queues a TODO row for later operator-driven retry. Tradeoff: NOT actually at-least-once on its own (JVM crash between commit and retry-queue write = lost event); requires operator visibility into the TODO queue; technically violates the "at-least-once" claim unless an outbox protects the retry-queue write.
- **(c) Introduce a message broker now.** Acknowledge §5's caveat clause has triggered. Add RabbitMQ (or similar) to docker-compose; finance-service publishes; operations-service subscribes; broker's persistence + ack semantics deliver at-least-once. Tradeoff: real infrastructure addition; new failure mode (broker down); operations / monitoring overhead the spec's "one developer / no live users" constraint has so far avoided.
- **(d) Reconsider γ — fold finance-service back into operations-service (= α).** No eventual-consistency exposure remains; Invoice + Order share a local transaction inside one larger service. Eliminates the need to pick a mechanism. Tradeoff: operations-service grows further; the "microservices" framing weakens further (already weakened by γ's grouping).

### 3.2 Scope of γ given the additional cross-context atomic writes surfaced in §2.1

**The choice.** §2.1 identifies two cross-context atomic write paths γ does not cover — `InvoiceService.deleteInvoiceItem` (Invoice → Operations) and `ProductMergeService.mergeProduct` (Product → Operations). The user must pick how the design handles them.

**Why it's forced.** The spec's "single eventual-consistency exposure" claim is false until these are addressed. Phase 5 (Product) and Phase 7 (Finance) implementers will hit these writes and have no design guidance.

**Options.**
- **(a) Extend γ — additional event paths, same mechanism.** Add "invoice item deleted" event (finance-service emits, operations-service consumes to remove links from OrderAdjustment / OrderItem / ShipmentItem). Add "product merged" event (product-service emits, operations-service consumes to update ProductAvailability and write transactions). Replace "single eventual-consistency exposure" in §4.3 with the enumerated set (currently 3 events: invoice posted, invoice item deleted, product merged). Whichever mechanism is picked in §3.1 carries all three. Tradeoff: more event handlers to design and maintain; idempotency keys for each; the set may grow as further audits surface more cross-context writes.
- **(b) Restructure to make these writes local.** Move InvoiceItem (and the line-link tables it cascades through) into operations-service so deletion is local; finance-service owns only Invoice header + GL. Move ProductMerge (the service, not the Product entity) into operations-service even though Product itself lives in product-service. Tradeoff: splits two more aggregates across service boundaries (Invoice ↔ InvoiceItem; Product entity ↔ ProductMerge service); awkward seams; may surface yet more cross-context writes during implementation.
- **(c) Audit further and revisit scope after.** Round 2 §3.1's note (*"the user may want to commission a broader audit … before picking, to ensure the chosen mechanism covers all real flows — not just this one"*) was acknowledged but not exhaustively run. Run a complete cross-context atomic-write audit across all Grails services before locking γ; the audit result may force (a), (b), or (d) below. Tradeoff: design work delays Phase 0 implementation; but cheaper than discovering a third or fourth uncovered write at Phase 5.
- **(d) Collapse to α — fold finance-service into operations-service.** Same as §3.1 option (d). Invoice → Order is local; ProductMerge → Inventory crosses Product↔Operations but is documented as an accepted exception (ProductMerge is a rare admin operation; eventual consistency for it is operationally tolerable). Tradeoff: operations-service grows; "microservices" framing weakens further; but the design becomes coherent and complete.

Picking (d) for §3.2 makes §3.1 moot (no event path remains except ProductMerge-as-documented-exception, which doesn't need at-least-once mechanics). Picking (a) or (b) for §3.2 still requires picking a mechanism in §3.1. Picking (c) for §3.2 defers both.

## 4. Previously addressed

Round 1 (`...-review-1.md`) findings resolved by the current spec, last confirmed in Round 2 §4:
- R1 §2.1 (JWT plant points) — §7.2 plants JWT in all three locations + §7.5 Playwright test added.
- R1 §2.2 (additive-only schema constraint) — §8 Step 6 states it explicitly.
- R1 §3.1 (cross-service WRITE strategy) — partially resolved (option-c → γ); RESIDUAL gap covered by Round 3 §2.1.
- R1 §3.2 (service-to-service auth) — §4.4 forwards `obx_token` cookie.

Round 2 (`...-review-2.md`) findings resolved by Round 2 UDD edits:
- R2 §2.1 (7 stale phase-number references) — all 7 sites updated (lines 20, 50, 139, 148, 231, 232, 246).
- R2 §2.2 (Order ↔ Inventory atomic writes) — partially resolved (Order + OrderItem moved into operations-service via γ Phase 6); RESIDUAL gap (other cross-context atomic writes) covered by Round 3 §2.1.
- R2 §3.1 (how to resolve the Order ↔ Inventory split) — partially resolved (γ picked); RESIDUAL coverage gap covered by Round 3 §3.2.

## 5. Recommendation

🛑 **Surface forced decisions to user.** §2 surfaces two literal-wrongness issues both rooted in γ's resolution being incomplete: the "single exposure" claim is falsified by additional cross-context writes, and the event-delivery mechanism contradicts the §5 tech-choice constraint. §3 surfaces the two forced decisions (mechanism + scope) the user must pick before Phase 5 (ProductMerge) and Phase 7 (Finance) implementation can begin. Phase 0 work can still proceed in parallel — none of these issues affect Phase 0 — but the spec should be settled before Phase 5 to avoid mid-flight re-design.
