# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 5)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec; A1–A30)

## 1. Verified-assumptions cross-check

29 of 30 assumptions reconfirmed under fresh read of the cited evidence. One partial failure:

- **A1–A20** all reconfirmed; cited evidence unchanged by Round 4 edits (Round 4 only touched §4.3 line 73, §4.5 line 104, §6 Phase 7 row line 141, §8 Step 1 line 229).
- **A22–A30** all reconfirmed; spot-checks of cited file:line positions all match.
- **A21 partial failure — controllers DO contain cross-context atomic writes that the original service-file audit missed.** A21 claims: *"grep of `grails-app/controllers/`, `grails-app/jobs/`, and `grails-app/domain/` for cross-domain `addTo`/`removeFrom`/`save`/`delete` patterns — controllers showed only intra-domain writes."* A fresh grep contradicts this:
  - `grails-app/controllers/org/pih/warehouse/inventory/InventoryItemController.groovy:936` — `shipmentInstance.addToShipmentItems(shipmentItem).save()` performs an atomic cross-context write from an inventory-package controller to shipping-owned `Shipment` + `ShipmentItem` tables. Not delegated to a service captured by the original audit.
  - `grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy:20` — `shipment.addToDocuments(command.document)` writes a shipping↔document link.
  - `grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy:166` — `invoiceInstance.removeFromDocuments(documentInstance)` writes a billing↔document link.

  These additional cross-context atomic writes are NOT enumerated in §4.3's table. The design's coverage policy in §11 ("Cross-context atomic-write coverage is policy-based, not exhaustive") and the §8 Step 1 audit checklist absorb the design impact — per-phase audits will catch these. But A21's wording overstates the original audit's completeness. The spec author should either reword A21 to acknowledge the partial gap or add these three cases to §4.3's enumeration.

## 2. Literal-wrongness findings

### 2.1 Phase 6 done gate cannot be satisfied as written — saga consumer services don't exist yet

**Description.** §4.3 enumerates three cross-context atomic-write rows whose emitter services live in the `replenishment/` and `stockTransfer/` packages, and whose target services don't exist at Phase 6:

| §4.3 row | Emitter (Phase 6) | Saga target | Target available |
|---|---|---|---|
| `StockTransferService.deleteStockTransfer` | inventory-service | ordering-service | Phase 7+ |
| `StockTransferService.rollback*` | inventory-service | shipping-service | Phase 8+ |
| OrderItem deletion from `ReplenishmentService` / `StockTransferService` | inventory-service | ordering-service | Phase 7+ |

Per the §4.3 service-ownership table, Replenishment and StockTransfer entities are owned by inventory-service. Per §8 Step 4 ("Port services. Rewrite Grails services as Java Spring `@Service`s"), service code ports at the same phase as its entities. So `ReplenishmentService` and `StockTransferService` move to inventory-service at Phase 6.

After Phase 6, inventory-service's `ReplenishmentService` runs `orderItem.delete()` (a Grails-owned table; ordering-service doesn't exist until Phase 7). Per §4.3 row, the resolution is "Saga (2-step): … ordering-service consumes." But ordering-service doesn't exist. Similarly for `StockTransferService.rollback*`: the saga consumer is shipping-service (Phase 8). And the saga infrastructure itself (§4.5 outbox + relay + subscriber framework) is built in Phase 7, so even if the consumer existed, the mechanism doesn't.

§6's Phase 6 row addresses the *opposite* direction explicitly: *"any Grails→inventory-service atomic writes route through Grails-side JDBC against the shared DB (additive-only schema constraint applies)."* But inventory-service→Grails atomic writes (the cases above) are not addressed.

A developer working through Phase 6 cannot meet the done gate as written: the extracted `ReplenishmentService` and `StockTransferService` will have cross-context writes with no resolution mechanism in place.

This is structurally the same issue as Round 4 §2.1 (Phase 7 done gate claiming a 3-step chain that depends on a Phase 8 service). The fix pattern is the same: honestly describe which work defers to a later phase.

**Evidence.**
- `grails-app/services/org/pih/warehouse/replenishment/ReplenishmentService.groovy:131-132` — `order.removeFromOrderItems(orderItem); orderItem.delete()` (cited in §4.3 row).
- `grails-app/services/org/pih/warehouse/stockTransfer/StockTransferService.groovy:286,301` — same pattern.
- `grails-app/services/org/pih/warehouse/stockTransfer/StockTransferService.groovy` — `deleteStockTransfer` and `rollback*` methods (cited in §4.3 rows for inventory→ordering and inventory→shipping sagas).
- Spec §4.3 (line 73): OrderItem deletion saga row — consumer = ordering-service.
- Spec §4.3 (line 69): `StockTransferService.deleteStockTransfer` row — consumer = ordering-service.
- Spec §4.3 (line 70): `StockTransferService.rollback*` row — consumer = shipping-service.
- Spec §4.5 (line 92): saga infrastructure "Built in Phase 7 (first phase that needs it)" — contradicted by Phase 6 actually being the first phase that needs it once ReplenishmentService/StockTransferService port.
- Spec §6 Phase 6 row (line 140): addresses Grails→inventory-service direction only.
- Spec §6 Phase 7 row (line 141): "ordering-service stands up."
- Spec §6 Phase 8 row (line 142): "shipping-service stands up."

**Proposed fix.** Rewrite the Phase 6 done-gate prose to defer porting of `ReplenishmentService` and `StockTransferService` (and any other inventory-package service whose cross-context targets don't yet exist) to a later phase, in the same honest style as Round 4's Phase 7 fix:

```
At Phase 6, the Replenishment and StockTransfer ENTITIES move to inventory-service
(Liquibase ownership transfers; additive-only schema constraint applies). ReplenishmentService
and StockTransferService themselves continue to live in Grails through Phase 6 — both contain
cross-context atomic writes to OrderItem (ordering-service, Phase 7+) and shipping events
(shipping-service, Phase 8+), and their saga consumers don't exist yet. At Phase 7, when
ordering-service stands up and the saga infrastructure (§4.5) lands, ReplenishmentService
ports to inventory-service with its OrderItem-deletion saga wired in. StockTransferService
ports to inventory-service in Phase 8 when shipping-service is also available, completing
all of its saga targets.
```

Also update §4.5 line 92 to read *"Built in Phase 7 (first phase whose own done-gate cross-context writes need it)"* — Phase 6's writes defer past Phase 6's done gate per the above, so §4.5's "first phase that needs it" remains Phase 7.

Alternative resolutions exist (pull saga infra forward to Phase 6; use direct shared-DB JDBC and lose Grails cascade semantics), but the deferral pattern matches the precedent set by Round 4's Phase 7 fix and preserves the design's saga-infra-lands-once principle. If the user prefers a different resolution, they can override.

## 3. Forced decisions

### 3.1 Auth mechanism for the outbox relay's HTTP POSTs to subscriber `/events/{eventType}` endpoints

**The choice.** §4.4 explicitly defers the per-service identity question: *"Background jobs (the 13 Quartz jobs, per A20) continue to use direct JDBC against the shared MariaDB until they are themselves migrated; the per-service identity question (for non-user-initiated calls in the post-Grails world) is deferred until a concrete instance emerges."* The concrete instance has now emerged in §4.5: the outbox relay is a `@Scheduled` task (no request context, no `obx_token` cookie to forward) that POSTs to subscriber endpoints. §8 Step 7 says services validate the `obx_token` cookie on every request. The relay's POST has no cookie to send.

Without picking a mechanism, Phase 7's first chained saga (the `OrderReceivedEvent` → inventory-service `/events/OrderReceivedEvent` POST) cannot succeed — the inventory-service subscriber endpoint rejects the unauthenticated POST.

**Why it's forced.** §4.4's deferral was conditional on no concrete instance existing. §4.5 introduces the instance in the same spec. Phase 7 cannot ship without the relay being able to authenticate to subscribers (or subscribers explicitly being exempt). The spec must either pick now, or explicitly re-affirm "defer the choice to Phase 7 implementation" and accept that Phase 7's developer makes a design-level decision under implementation pressure.

**Options.**
- **(a) Exempt `/events/{eventType}` endpoints from `obx_token` auth; rely on internal-network trust.** Subscribers expose `/events/...` on an internal-only docker network (not routed through nginx to the public internet). Simplest mechanism. Tradeoff: only safe if the internal docker network is genuinely trusted (in this deployment topology — single-tenant fork, no live users — it is); breaks if/when the service mesh ever spans untrusted networks.
- **(b) Per-service identity HMAC token signed by the same `OPENBOXES_JWT_SECRET`.** Relay attaches `Authorization: Service <jwt>` header with claims `{ sub: "<service-name>", iss: "outbox-relay" }`. Subscribers' filter accepts either `obx_token` user cookie or the service JWT. Tradeoff: small additional code in the shared `RestClient` configuration + filter; preserves "all auth uses the same HMAC secret" simplicity; no new infrastructure.
- **(c) Originating-user identity carried in the outbox event payload.** When the business write enqueues the outbox event, the current user's identity is captured into the event payload. The relay forwards this identity (e.g., as a custom header) on the HTTP POST. Subscribers trust the header IF the request comes from inside the network. Tradeoff: every event payload carries identity metadata; relies on network trust same as (a); preserves audit trail of "who initiated this saga."
- **(d) Re-affirm the §4.4 deferral; let the Phase 7 implementer pick during implementation.** Explicitly note in §4.4 (or §4.5) that the relay's auth mechanism is deferred to Phase 7 implementation. Tradeoff: the implementer will pick under implementation pressure (likely choosing whatever is fastest, probably (a)); CDR's job to surface this is fulfilled by recording the deferral explicitly.

## 4. Previously addressed

Round 1 (`...-review-1.md`) — all resolved (re-confirmed Round 2 §4, Round 3 §4, Round 4 §4).

Round 2 (`...-review-2.md`) — all resolved.

Round 3 (`...-review-3.md`) — all resolved by spec rewrite at `6f4ca16`.

Round 4 (`...-review-4.md`) — both findings resolved by spec edits at `609cc91`:
- R4 §2.1 (Phase 7 done gate honesty about 2-step in Phase 7 + 3rd step in Phase 8) — applied at spec §6 Phase 7 row (line 141) and §4.5 last sentence (line 104).
- R4 §2.2 (OrderItem deletion case + audit checklist) — applied at §4.3 table row (line 73) and §8 Step 1 checklist (line 229). Note: §2.1 of this Round 5 review surfaces the analogous Phase 6 gap that R4's Phase 7 fix didn't extend to.

## 5. Recommendation

🛑 **Surface forced decisions to user.** §2.1 is a mechanical fix following the same pattern as Round 4 §2.1 (honest done-gate prose; defer porting until consumer services exist). §3.1 is a real forced decision the spec deferred explicitly in §4.4, and whose triggering instance has now arrived in §4.5 — the user must pick the auth mechanism (or explicitly re-affirm the defer) before Phase 7 ships. §1 surfaces a partial failure of A21 that the spec's existing coverage policy already absorbs; spec author should reword A21 for accuracy but no design change required.
