# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 6)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec; A1–A30)

## 1. Verified-assumptions cross-check

All 30 assumptions reconfirmed under fresh read of the cited evidence. Round 5 UDD edits touched §4.4 (cookie-capture extension), §4.5 outbox table/writer/relay (3 paragraphs), §6 Phase 6/7/8 rows (deferral), and §10 A21 row (rewording). None of these edits invalidated cited evidence for any other assumption.

- **A21 reworded** (per Round 5 §1 fix) — the partial-failure framing now matches reality: jobs/domain events clean; controllers contain three direct cross-context writes (`InventoryItemController.groovy:936`, `DocumentUploadController.groovy:20`, `InvoiceController.groovy:166`); design impact absorbed by §11's policy-based coverage + §8 Step 1's per-phase audit. Re-checked: cited file:line references all still resolve to the stated patterns.
- **A22–A30** spot-checks pass; no cited evidence has shifted.
- **A1–A20** unchanged across rounds; no re-litigation per skill instructions.

## 2. Literal-wrongness findings

No literal-wrongness findings.

The Round 5 edits resolved both prior §2 items (Phase 6 done gate; A21 wording) and the §3 forced decision (outbox relay auth via captured cookie). New text in §4.4 / §4.5 / §6 was checked for internal consistency:

- The outbox-writer's "reads the current request's `obx_token` cookie" assumption holds for Phase 7's first chained saga: `OrderService.saveOrderShipment` (the `OrderReceivedEvent` trigger) is invoked from `ReceiveOrderWorkflowController.groovy:104` and `OrderController.groovy:572,577` — user-request-context, cookie present, SecurityInterceptor-gated.
- Phase 6 deferral chain (entities at Phase 6; ReplenishmentService at Phase 7; StockTransferService at Phase 8) is internally consistent with §4.5's "Built in Phase 7" and §6's Phase 7/8 done gates. The per-slice template Step 3 (port domain to JPA) + Step 8b (leave Grails class alive while callers remain) + Step 10 (conditional delete) accommodate the JPA-and-Grails-domain-coexist period during Phase 6.
- The captured-cookie + nullable `originating_user_token` column + dead-letter-on-401 path together cover the three relay-time auth failure modes (missing cookie, TTL-expired cookie, future non-user-initiated callers) consistently with §4.4's deferred sub-case.

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

Round 1 (`...-review-1.md`) — all resolved.
Round 2 (`...-review-2.md`) — all resolved.
Round 3 (`...-review-3.md`) — all resolved by spec rewrite at `6f4ca16`.
Round 4 (`...-review-4.md`) — both findings resolved at `609cc91`.
Round 5 (`...-review-5.md`) — all three findings resolved at `34283a7`:
- R5 §1 (A21 partial failure) — A21 reworded; spec §10 row now acknowledges the controller-level gap and points to §11 + §8 Step 1 for absorption.
- R5 §2.1 (Phase 6 done gate) — §6 Phase 6/7/8 rows now explicitly defer ReplenishmentService porting to Phase 7 and StockTransferService porting to Phase 8.
- R5 §3.1 (outbox relay auth) — user picked option (c); §4.4 paragraph extended for the outbox relay; §4.5 outbox table adds `originating_user_token` column; writer captures cookie at write time; relay attaches it on subscriber POST; TTL-expired dispatch path documented.

## 5. Recommendation

✅ **Approve as-is.** §2 and §3 are both empty. The spec is internally consistent, all prior CDR rounds' findings are resolved, and the design is ready for `thorough-writing-plans` (Phase 0 is the natural starting point).
