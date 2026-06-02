# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 4)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec)

## 1. Verified-assumptions cross-check

All 30 assumptions (A1–A30) reconfirmed under fresh read of the cited evidence. No prior assumption invalidated by the spec's brainstorming Round 2 rewrite. A21–A30 (newly added) each have grep/Read evidence cited inline; spot-checks of cited file:line positions match.

Note: A21 ("No cross-context atomic writes in Controllers, Quartz jobs, or GORM events that the service-file audit missed") remains technically correct as written. However, the audit pattern set used during brainstorming Round 2 (focused on `addTo/removeFrom/save/delete` patterns inside specific service-file scopes) did NOT cover `.delete()` calls on operations-domain instances across all service-file scopes. The cases this missed appear as a §2 finding below — they're additional writes the spec's §4.3 enumeration should have caught.

## 2. Literal-wrongness findings

### 2.1 Phase 7 done gate cannot be satisfied as written — shipping-service doesn't exist until Phase 8

**Description.** §6 Phase 7 done gate states:

> *"First chained saga: `OrderReceivedEvent` from ordering-service → `InventoryItemCreatedEvent` from inventory-service → ShipmentItem creation in shipping-service."*

But the same §6 phase table puts shipping-service extraction in **Phase 8**, not Phase 7. At the time Phase 7 ships, shipping-service does not exist — `Shipment` and `ShipmentItem` still live in Grails. The third step in the claimed chain ("ShipmentItem creation in shipping-service") cannot happen because there is no shipping-service to receive the event.

This is internally inconsistent: a developer working through Phase 7 cannot deliver the done gate as written. They either ship a 2-step chain (ordering → inventory; ShipmentItem creation stays in Grails for now), or they pull shipping-service extraction forward into Phase 7 (which would inflate Phase 7 from ~6–8 weeks to ~10–12 weeks and contradict the spec's Phase 8 estimate).

**Evidence.**
- Spec §6, Phase 6 row: *"Phase 6 is the largest single-service extraction. No saga yet — Order/Shipping/etc. still in Grails."* Confirms shipping is in Grails after Phase 6.
- Spec §6, Phase 7 row (line 140): claims the 3-step chain ending in shipping-service.
- Spec §6, Phase 8 row (line 141): *"shipping-service stands up. StockMovementService's many cross-context writes resolve via saga (~20+ events surface during scoping — pairwise inventory↔shipping)."* Confirms shipping-service is a Phase 8 deliverable.
- §6 pace estimate: *"Phase 7 (~7 weeks — saga harness + extraction)"* vs *"Phases 8–10 (~3–4 weeks each)"* — Phase 7 is scoped for saga harness + ordering only, not for shipping extraction.

**Proposed fix.** Rewrite Phase 7's done-gate sentence:

```
First chained saga in Phase 7: 2-step chain — `OrderReceivedEvent` from
ordering-service → inventory-service consumes and creates InventoryItem.
ShipmentItem creation continues to happen in Grails (Grails OrderService
still owns saveOrderShipment) until Phase 8 extracts shipping-service.
Phase 8 then adds the third step: shipping-service consumes
`InventoryItemCreatedEvent` (or a Phase-8-defined derived event) and
creates ShipmentItem, completing the full 3-step `saveOrderShipment`
choreography.
```

Also update §4.5's "3-step choreography" example wording to clarify that the 3-step chain only goes live at Phase 8, not Phase 7.

### 2.2 §4.3's enumerated cross-context atomic write list missed `OrderItem` deletion paths

**Description.** §4.3 enumerates "currently-known cases" of cross-context atomic writes. It includes `OrderService.saveOrderShipment`, `StockMovementService.*`, `StockTransferService.deleteStockTransfer`, `StockTransferService.rollback*`, `ProductMergeService.*`, and `InventoryService` import flow. It does NOT include the `OrderItem.delete()` cross-context atomic writes performed by **Replenishment, StockTransfer, and Putaway services**, all of which call `orderItem.delete()` on Order-domain objects from inside what will become the inventory-service or shipping-service.

After extraction (γ groupings per §4.3):
- `ReplenishmentService.deleteOrderItem` (lives in inventory-service) calls `orderItem.delete()` (ordering-service) — cross: inventory → ordering atomic write
- `StockTransferService.removeOrderItems` (lives in inventory-service) calls `orderItem.delete()` (line 301) — cross: inventory → ordering atomic write
- `PutawayService.deleteOrderItems` (lives in shipping-service per §4.3, since `putaway/` is in shipping) calls `orderItem.delete()` (line 251) — cross: shipping → ordering atomic write

These writes also remove the OrderItem from `order.orderItems` (`order.removeFromOrderItems(orderItem)` immediately before the delete), and via Grails cascade behavior may affect linked `picklistItems`, `shipmentItems`, and `invoiceItems` join-table rows.

The spec's §4.3 last-paragraph coverage policy says "additional writes surface per-phase scoping and resolve via the documented policy." So strictly, the design accommodates these (Phase 6 / 8 / 9 scoping would catch them). BUT: the brainstorming Round 2 audit explicitly attempted to enumerate this exact class of issue and missed it. The audit's grep patterns (focused on `addTo/removeFrom` and on specific service-file scopes) did not catch `orderItem.delete()` calls in `replenishment/`, `stockTransfer/`, or `putaway/` services. Without a documented audit method for per-phase scoping, the same gap will recur — the per-slice template Step 1 says *"audit cross-context atomic writes touching this slice"* but doesn't specify HOW.

The literal-wrongness: per-phase audits using ad-hoc grep patterns will miss cross-context writes the same way brainstorming Round 2 did, and the design's resolution mechanisms (saga / sync HTTP / restructure) won't apply to writes that aren't surfaced. Result: silent breakage at extraction time.

**Evidence.**
- `grails-app/services/org/pih/warehouse/replenishment/ReplenishmentService.groovy:131-132` — `order.removeFromOrderItems(orderItem); orderItem.delete()`
- `grails-app/services/org/pih/warehouse/stockTransfer/StockTransferService.groovy:286,301` — `order.removeFromOrderItems(orderItem); orderItem.delete()`
- `grails-app/services/org/pih/warehouse/putaway/PutawayService.groovy:250-251` — `orderItem.order.removeFromOrderItems(orderItem); orderItem.delete()`
- Spec §8 Step 1 (line 226): *"Identify scope. ... Audit cross-context atomic writes touching this slice — apply the §4.3 coverage policy to each."* — instruction is given but no method specified.

**Proposed fix.** Two-part:

1. **Add the OrderItem-deletion cases to §4.3's enumerated list** under a new row:
   ```
   | OrderItem deletion from ReplenishmentService / StockTransferService /
     PutawayService — calls `order.removeFromOrderItems(oi); oi.delete()`
     atomically in one Grails transaction
   | inventory → ordering, shipping → ordering
   | Saga (2-step): emitter service writes "OrderItem deletion requested"
     event; ordering-service consumes (idempotent) and performs the
     remove+delete locally. Eventual consistency on the deletion.
   ```

2. **Add an audit checklist to §8 Step 1** specifying the grep patterns the per-phase audit must run. Concretely:
   ```
   Step 1 audit checklist (cross-context atomic writes):
   - grep service files in this slice's source package for:
     a. `.addTo<X>(` / `.removeFrom<X>(` on instances of domain classes
        owned by OTHER γ services
     b. `<DomainClass>.delete(` / `<lowercase>.delete(` where DomainClass
        is owned by another γ service
     c. `new <DomainClass>(` followed by `.save(` where DomainClass is
        owned by another γ service
     d. `def <otherDomain>Service` / `@Autowired <Other>Service`
        injections crossing γ boundaries; for each, check usage call
        sites for writes
   - Cross-reference each finding against §4.3's coverage policy
     (ownership / saga / restructure / sync HTTP)
   - Document each in the slice's commit message
   ```

This makes the per-phase audit method explicit and reproducible, preventing the same enumeration gap from recurring.

## 3. Forced decisions

No forced decisions found. All open design questions either have a single right answer (§2 fixes are mechanical) or are picked elsewhere in the spec.

## 4. Previously addressed

Round 1 (`...-review-1.md`) findings, all resolved:
- R1 §2.1 (JWT plant points) — §7.2 plants in 3 places + Playwright test
- R1 §2.2 (additive-only schema constraint) — §8 Step 6 states it
- R1 §3.1 (cross-service WRITE strategy unpicked) — §4.3 + §4.5 design (11 services + ownership rule + outbox saga + 3-step choreography)
- R1 §3.2 (service-to-service auth unpicked) — §4.4 forwards `obx_token` cookie

Round 2 (`...-review-2.md`) findings, all resolved:
- R2 §2.1 (7 stale phase-number refs) — new §6 uses new numbering throughout
- R2 §2.2 (Order ↔ Inventory atomic writes) — §4.3 case `saveOrderShipment` with 3-step choreography
- R2 §3.1 (how to resolve cross-context atomic writes) — §4.3 + §4.5 design replaces γ entirely

Round 3 (`...-review-3.md`) findings, all resolved:
- R3 §2.1 ("single eventual-consistency exposure" claim falsified) — replaced with policy-based coverage; explicit disclaimer in §11; A28–A29 verify shared-kernel + cross-package field references
- R3 §2.2 (event mechanism contradicting §5 "no broker") — §4.5 transactional-outbox-without-broker resolves; §5 row updated to mention outbox saga
- R3 §3.1 (mechanism for the event paths) — §4.5 picked: transactional outbox + HTTP relay
- R3 §3.2 (scope of γ given additional cross-context writes) — entire spec rewritten via brainstorming Round 2; γ replaced with 11 domain-aligned services

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes.** §3 is empty (no forced decisions surfaced — significant improvement vs prior rounds, where the design's framing concealed forced decisions). §2 has two mechanical fixes: (a) reword Phase 7 done gate to honestly describe the 2-step chain that ships in Phase 7 and the 3rd step landing in Phase 8; (b) add the OrderItem-deletion cases to §4.3 enumeration AND add an explicit audit checklist to §8 Step 1 so per-phase audits use a reproducible method instead of ad-hoc grep. Both fixes are within UDD scope; no design rework needed.
