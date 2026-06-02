# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec)

## 1. Verified-assumptions cross-check

All 20 assumptions reconfirmed; cited evidence unchanged by Round 1 edits. (UDD only touched §4.3, §4.4, §6, §7.2, §7.5, §8 step 6, §10 A19 phase number, and §11 phase number — none invalidated the cited evidence in §10.)

## 2. Literal-wrongness findings

### 2.1 Stale phase number references after Round 1 phase collapse

**Description.** UDD collapsed the phase table (old Phases 6-9 → new Phase 6; old Phases 10-11 → new Phase 7; old Phase 12 → Phase 8; old Phase 13 → Phase 9). The collapse updated the phase table itself plus two phase-number references (§10 A19 and §11 webpack-GSPs bullet). It missed seven other places in the spec that still reference the old numbering. A developer reading the spec to plan work will follow references to phases that don't exist.

**Evidence.** `grep -nE "Phase 1[12]|Phase 13|12 slice phases|13 phases|Phases 1\.\.12"` on the spec:
- **Line 20** (§3 Approach): `"Total: one foundation phase + ~12 slice phases + one cleanup phase."` — should be `~7 slice phases` (Phases 1-8 are slices, with Phase 0 = foundation and Phase 9 = cleanup → 8 slices, not 12).

   Correction count check: Phases 1, 2, 3, 4, 5, 6, 7, 8 = 8 slice phases. The "~12" is stale by 4.

- **Line 50** (§5 tech-choices table): `"Stays on Java 8 until Phase 13 deletes Grails entirely"` — Phase 13 doesn't exist; should be Phase 9.
- **Line 139** (§7.6 Explicitly NOT in Phase 0): `"Decoupling happens late ... see Phase 13."` — should be Phase 9.
- **Line 148** (§8 header): `"## 8. Per-slice template (Phases 1..12)"` — should be `Phases 1..8`.
- **Line 231** (§11 Known issues, Java 8 EOL bullet): `"Grails stays on Java 8 until Phase 13."` and `"the problem deletes itself in Phase 13."` — both should be Phase 9.
- **Line 232** (§11 Known issues, Gradle 4.10.3 bullet): `"Same logic — stays until Phase 13."` — should be Phase 9.
- **Line 246** (§12 Risks, One-developer pace bullet): `"13 phases is a multi-year undertaking."` — should be `10 phases` (Phases 0-9 inclusive).

**Proposed fix.** Replace each stale reference per the line table above. Mechanical edit; no design implications.

### 2.2 Cross-context atomic writes between Order and Inventory contradict the §4.3 "no saga / no eventual consistency" claim

**Description.** Spec §4.3 (added in Round 1) says: *"all warehouse operations that currently write Inventory in a single Grails transaction (cycle count adjustments, shipment receipts, stock movements, replenishments, put-away) live together in `operations-service`. ... No saga / eventual consistency / 2PC infrastructure is introduced — within each service, transactions remain local."* This claim is false for at least one current flow: **Order receiving processing in `OrderService.groovy` writes `InventoryItem` atomically within the same Grails transaction that updates Order-side state**. Under the spec's groupings (operations-service owns Inventory + InventoryItem; orders-and-finance-service owns Order + OrderItem), this atomic flow gets split across two services. The spec acknowledges this only via §6 Phase 7 done-gate prose ("depends on Operations service for any inventory reservation/adjustment, which goes through HTTP"), which contradicts §4.3's claim of no eventual-consistency exposure.

**Evidence.**
- `grails-app/services/org/pih/warehouse/order/OrderService.groovy:65` — `InventoryService inventoryService` (Order service holds an Inventory service reference)
- `grails-app/services/org/pih/warehouse/order/OrderService.groovy:327-328` — inside an `orderItemCommand` processing path:
  ```groovy
  InventoryItem.withSession { session ->
      inventoryItem = inventoryService.findOrCreateInventoryItem(orderItemCommand.productReceived, orderItemCommand.lotNumber, orderItemCommand.expirationDate)
  ```
  The `findOrCreateInventoryItem` call happens inside the same session/transaction as the OrderItem state update — an atomic cross-context write that the spec's groupings break.
- `grails-app/services/org/pih/warehouse/order/OrderService.groovy:580` — `inventoryService.deleteLocalTransfer(transaction)` — another cross-context write from Order service to Inventory service.

The spec's option-(c) "transactional integrity" doctrine was applied to Inventory ↔ Shipment / CycleCount / StockMovement / Replenishment / PutAway (all merged into operations-service), but missed Inventory ↔ Order. The result: the spec claims no eventual-consistency exposure but actually has it for at least one flow.

**Proposed fix.** Three resolution options — the spec must pick one (this is a forced decision; see §3.1 below). The fix here in §2 is to acknowledge the cross-context write exists; the resolution mechanism is §3.

## 3. Forced decisions

### 3.1 How to resolve cross-context atomic writes between Order and Inventory (and any other flows surfaced by deeper inspection)

**The choice.** Per §2.2, OrderService currently writes InventoryItem atomically inside Order-related processing. Under the spec's groupings, this write crosses service boundaries. Spec must pick a resolution before Phase 6 or Phase 7 ships (whichever comes first).

**Why it's forced.** The first cross-service write that ships has already picked one of these — or has a silent atomicity bug. The spec's current text contradicts itself (§4.3 says "no eventual consistency"; §6 Phase 7 gate says inventory writes "go through HTTP"); the user has to disambiguate.

**Options.**
- **(a) Expand operations-service to also own Order + OrderItem.** Receiving-related Order state updates remain atomic with Inventory writes. `orders-and-finance-service` shrinks to Requisition + Fulfillment + PurchaseOrder + Invoice + Finance. Operations-service grows further but the design's transactional-integrity claim holds.
- **(b) Accept eventual consistency for Order-state-updates that happen during receiving.** Operations-service does the Inventory write in its local transaction; emits an event/callback to update Order status; brief window where Order status lags Inventory. Contradicts §4.3's "no saga / no eventual consistency" claim — that paragraph would need amendment to acknowledge "Order receiving status is eventually consistent."
- **(c) Restructure the orders-and-finance boundary differently.** E.g., OrderItem moves to operations-service (since its receiving state IS operations-owned) while Order metadata stays in orders-and-finance-service. Splits Order ↔ OrderItem ownership; complicates the Order aggregate.

Also: the user may want to commission a broader audit of cross-context atomic writes in the current Grails codebase (similar to the Order ↔ Inventory case here) before picking, to ensure the chosen mechanism covers all real flows — not just this one.

## 4. Previously addressed

Round 1 (`2026-05-25-grails-to-spring-boot-migration-design-critical-review-1.md`) findings now resolved by the current spec:

- **R1 §2.1** (JWT issuance plant point bypassed React login) — spec §7.2 now plants JWT in all three places (AuthController.handleLogin, ApiController.login, ApiController.chooseLocation); §7.5 done gate adds the React-LoginModal Playwright test.
- **R1 §2.2** (Step 8b silently required additive-only schema) — spec §8 Step 6 now states the additive-only constraint explicitly.
- **R1 §3.1** (cross-service WRITE strategy unpicked) — user picked option (c); applied via §4.3 paragraph and phase-table collapse (operations-service merges Phases 6-9; orders-and-finance-service merges Phases 10-11). See §2.2 above for a residual gap in this resolution.
- **R1 §3.2** (service-to-service / Grails-to-service auth unpicked) — user picked option (a); applied via §4.4 paragraph.

## 5. Recommendation

🛑 **Surface forced decisions to user.** §2.1 is a mechanical cleanup (the UDD phase-renumber missed 7 sites). §2.2 surfaces a real gap in the option-(c) resolution from Round 1 — Order ↔ Inventory atomic writes weren't covered by the operations-service grouping. §3.1 names the choice the user must now make to plug that gap. Address all three before implementation planning.
