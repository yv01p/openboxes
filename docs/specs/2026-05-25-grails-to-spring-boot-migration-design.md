# Grails → Spring Boot / React Migration: Design Spec

**Date:** 2026-05-25
**Repo:** openboxes (yv01p/openboxes fork)
**Companion:** `docs/reviews/2026-05-25-openboxes-architecture-review-1.md`

## 1. Problem

OpenBoxes today is a Grails 3.3.16 monolith (Java 8, Groovy 2.4, Hibernate 5.2.18, 163k LOC server + 77k LOC GSP + 92k LOC React + 280 Liquibase changesets, single MariaDB) with a React frontend that runs as a tenant of the Grails app (webpack writes `.gsp` files into the Grails view tree). The goal is to retire Grails entirely: the end state has zero Groovy/Grails code; the system is Spring Boot microservices + standalone React.

## 2. Constraints

- **One developer.** Coordination cost is the developer's own time; favor approaches that don't require parallel workstreams.
- **No live users.** Migration runs against your own fork; "production soak" means running the new state against realistic test data without regression for a chosen interval.
- **High quality, low risk.** Each phase ends with the system in a runnable, coherent state. E2E tests cover the changed slice. Soak before advancing.
- **Endpoint: Grails fully gone.** Every Grails controller, GSP, taglib, and domain class replaced; final repo is Java + Spring Boot + React.

## 3. Approach

**Strangler-fig with vertical slices.** Each phase is one bounded context migrated end-to-end: schema ownership moves, Spring Boot service stands up, React frontend talks to the new service, Grails counterparts deleted (or marked for later deletion if external Grails callers remain). The Grails monolith shrinks every phase, never grows. Total: one foundation phase + ~11 slice phases + one cleanup phase.

Alternatives considered and rejected:

- **Horizontal layers** (infrastructure → all backends → frontend): phase boundaries too large to soak meaningfully; "extract every backend service in one phase" is a flag-day, not a phase.
- **Parallel greenfield** (build new system alongside, sync data, cut over): too much dual-maintenance work for one developer; multi-year data-sync project; original Grails app keeps developing during the sync so parity is a moving target.
- **Modular monolith / one big operations-service** (α option from prior design rounds): considered and rejected — user prioritizes architectural integrity (clean domain-aligned services) over saga-cost minimization.

## 4. Resolution of forced decisions

### 4.1 Migration strategy (§2.1 of arch review)
**Strangler-fig with vertical slices.** Each Phase 1..N is a strangler step that removes Grails code as it adds Spring Boot code.

### 4.2 Sequencing (§2.2)
**Vertical slices.** Each slice migrates one context's backend AND frontend together. React-on-Grails screens migrate slice-by-slice as their backend contexts are extracted; GSP-only screens get React frontends as part of their context's slice.

### 4.3 Service boundaries and data ownership (§2.3 + new design from brainstorming Round 2)

**11 domain-aligned services** plus reference data and reporting. The boundaries align with Grails bounded contexts, not with the Grails directory structure (the `core/` Grails package is a shared kernel whose entities map by NAME to specific services).

| # | Service | Owns (top-level entities) |
|---|---|---|
| 1 | **identity-service** | User, Role, Person, LocationRole, auth issuance/validation |
| 2 | **location-service** | Location, LocationGroup, LocationType, LocationStatus |
| 3 | **organization-service** | Organization, Party, PartyRole, PartyType, Supplier, Shipper, Address, Donor |
| 4 | **document-service** | Document, DocumentType |
| 5 | **catalog-service** | Product, ProductAttribute, ProductPackage, ProductSupplier, ProductCatalog, Category, Tag, UnitOfMeasure, UnitOfMeasureClass, Synonym |
| 6 | **inventory-service** | Inventory, InventoryItem, InventoryLevel, InventorySnapshot, ProductAvailability, Transaction, TransactionEntry, CycleCount* (Item/Request/Candidate/ProductSummary/Details), StockMovement, StockMovementItem, StockTransfer, StockTransferItem, LocalTransfer, Replenishment |
| 7 | **shipping-service** | Shipment, ShipmentItem, ShipmentType, ShipmentMethod, ShipmentWorkflow, Container, Receipt, ReceiptItem, PutAway |
| 8 | **ordering-service** | Order, OrderItem, OrderType, OrderAdjustment, OrderSummary, PurchaseOrder, RefreshOrderSummaryEvent |
| 9 | **requisition-service** | Requisition, RequisitionItem, Fulfillment, FulfillmentItem, Picklist, PicklistItem |
| 10 | **billing-service** | Invoice, InvoiceItem, InvoiceItemCandidate, InvoiceList, InvoiceType, GlAccount, GlAccountType, PaymentTerm, PaymentMethodType, BudgetCode |
| 11 | **reporting-service** | DateDimension, LocationDimension, ProductDimension, TransactionTypeDimension, LotDimension, reporting endpoints, expirationHistory, reorderReport |

**Shared kernel handling.** Grails `core/` package contains entities owned by multiple services (User → identity; Location → location; GlAccount → billing; etc.). At each slice's extraction, the relevant `core/` entities migrate to that service's module. `core/` shrinks each slice; deleted entirely in Phase 12.

**Data ownership during transition.** Shared MariaDB; each new service owns its tables; cross-service reads go direct-JDBC against the shared DB until the dependency's owning service exists, then switch to HTTP. Identity and reference data (Location, Product) extract early so they don't sit as direct-JDBC dependencies for long.

**Cross-service writes — link-table ownership rule.** For M:N relationships that cross service boundaries, ONE service owns the join table. That service writes; other services read via the owner's API (or shared DB during transition). Specifically:

- **billing-service owns** join tables `order_invoice` (InvoiceItem↔OrderItem), `shipment_invoice` (InvoiceItem↔ShipmentItem), and `order_adjustment_invoice` (InvoiceItem↔OrderAdjustment).
- This makes Invoice creation/deletion writes LOCAL to billing-service even though they syntactically appear as `oi.removeFromInvoiceItems(...)` in current Grails code — the underlying SQL is `DELETE FROM order_invoice WHERE invoice_item_id = ?`, which billing-service runs locally.

**Cross-service writes — atomic flows that can't be made local.** Resolution per case. The following are the currently-known cases (audit covered service files; additional cases surface per-phase scoping and use the same resolution policy):

| Flow | Direction | Mechanism |
|---|---|---|
| **`OrderService.saveOrderShipment`** — Order receiving creates Shipment + ShipmentItem + InventoryItem atomically (3 services) | ordering → inventory + ordering → shipping | **3-step choreography:** ordering-service emits `OrderReceivedEvent`; inventory-service consumes, creates InventoryItem, emits `InventoryItemCreatedEvent` with order context; shipping-service consumes that, creates Shipment + ShipmentItem with the InventoryItem reference. Order state in ordering-service marks "received" immediately; downstream creation lags by seconds (eventually consistent). |
| **`StockMovementService.*`** — many methods atomically write across inventory + shipping (e.g., `sendShipment`, `createShipmentEvent`, `findOrCreateInventoryItem`) | inventory ↔ shipping | **Pairwise saga (2-step choreography):** each cross-context write is its own event. inventory-service emits when source flow lives there; shipping-service consumes. ~20+ events surface during Phase 8/9 scoping. |
| **`StockTransferService.deleteStockTransfer`** — deletes Order from inventory context | inventory → ordering | **Saga (2-step):** inventory-service emits `StockTransferDeletedEvent` carrying order ID; ordering-service consumes and deletes Order. |
| **`StockTransferService.rollback*`** — rolls back shipment events from inventory context | inventory → shipping | **Saga (2-step):** event-driven rollback. |
| **`ProductMergeService.*`** — catalog-service writes to inventory tables during merge | catalog → inventory | **Restructure:** Move `ProductMergeService` implementation from catalog/ to inventory-service at Phase 6. catalog-service exposes thin merge endpoint that delegates. Inventory writes stay local. Product entity's "mark obsolete" update (`obsolete.active = false; obsolete.save()`) becomes a single sync HTTP call from inventory-service back to catalog-service post-merge — acceptable for rare admin operation. |
| **`InventoryService` import flow** — creates Product during inventory bulk import (lines 2326, 2474) | inventory → catalog | **Sync HTTP, per-row:** inventory-service's import endpoint calls catalog-service per row to create-or-find products by name; gets back IDs; writes inventory locally using those IDs. Per-row atomicity preserved; multi-row "best effort" matches existing per-row error handling. |
| **OrderItem deletion from `ReplenishmentService` / `StockTransferService` / `PutawayService`** — `order.removeFromOrderItems(oi); oi.delete()` runs atomically inside one Grails transaction (see `ReplenishmentService.groovy:131-132`, `StockTransferService.groovy:286,301`, `PutawayService.groovy:250-251`) | inventory → ordering (Replenishment, StockTransfer); shipping → ordering (Putaway) | **Saga (2-step):** emitter service (inventory or shipping) writes an `OrderItemDeletionRequestedEvent` to its outbox in the same local transaction as the surrounding business write; ordering-service consumes (idempotent on event ID) and performs the `removeFromOrderItems` + `delete()` locally. Eventual consistency on the deletion. |

**Coverage policy.** The audit covered service files and surfaced the above. Additional cross-context atomic writes likely surface per-phase scoping. The design adopts these resolution patterns:

1. M:N link writes on the link-table-owning side → local.
2. Business-critical atomic across two contexts → 2-step saga (outbox event).
3. Atomic across three contexts → 3-step choreography (chained events).
4. Atomic across four+ contexts → revisit boundary; if unavoidable, lightweight orchestrator in the initiating service.
5. Catalog/inventory admin boundary, eventually-consistent OK → restructure or sync HTTP.

**Cross-package field references.** Domain classes have heavy cross-package field references (Order has `shipments` hasMany Shipment; Shipment has `InventoryItem` fields; OrderAdjustment references `InvoiceItem`). At extraction time, these become HTTP calls or shared-DB direct reads per the per-slice template (§8). Field-level resolution is per-entity work during each phase.

### 4.4 Auth during coexistence (§2.4)
**JWT issued by Grails in Phase 0, served as `obx_token` HttpOnly SameSite=Strict cookie alongside the existing `JSESSIONID`. Grails `SecurityInterceptor` accepts both during transition. Phase 2 moves issuance to identity-service; Grails then validates JWTs only. Final state: no session cookies anywhere.** No external OIDC provider; HMAC-HS256 with secret in env var `OPENBOXES_JWT_SECRET`.

**Service-to-service / Grails-to-service auth: forward the user's `obx_token` cookie.** Grails controllers that call Spring Boot services after their slice migration read the incoming `obx_token` cookie and propagate it on outbound HTTP calls. Spring Boot services calling other Spring Boot services do the same — a small shared `RestClient` configuration reads the cookie from the current request context and adds it to outbound calls. The outbox relay (§4.5) extends this model for dispatch outside a request context: the originating user's `obx_token` is captured into the outbox row at write time (inside the same JPA transaction as the business write) and the relay attaches it on the subscriber POST. If the captured token has expired by dispatch time (rare; only matters for dead-lettered events retried beyond the 8h TTL), the POST 401s and the event stays dead-lettered for operator review. Background jobs (the 13 Quartz jobs, per A20) continue to use direct JDBC against the shared MariaDB until they are themselves migrated; the per-service identity question for post-Grails callers with no originating user (e.g., a future job that needs to enqueue outbox events without a request context) remains deferred until that concrete sub-case emerges.

### 4.5 Saga infrastructure (NEW — replaces γ's contradictory event-without-broker text)

**Pattern: transactional outbox + HTTP relay + idempotent subscribers.** No message broker. No external orchestrator. Built in Phase 7 (first phase that needs it).

**Outbox table** (per emitter service, in shared MariaDB):
- Columns: `id` (UUID), `event_type`, `payload` (JSON), `originating_user_token` (the captured `obx_token` cookie value from the request that triggered the write; nullable for future non-user-initiated callers per §4.4), `created_at`, `consumed_at` (nullable), `retries`, `dead_lettered_at` (nullable)
- First instance: `ordering_outbox_event` in Phase 7

**Outbox writer** (in emitter service): helper that inserts outbox rows inside the same JPA transaction as the business write — atomicity guaranteed by the DB. The helper reads the current request's `obx_token` cookie and persists it in the `originating_user_token` column for the relay to attach at dispatch time (per §4.4).

**Outbox relay** (in emitter service): scheduled Spring `@Scheduled` task (1-second polling interval) that reads unconsumed events, dispatches each to its subscriber via HTTP POST (attaching the row's `originating_user_token` as the `obx_token` cookie per §4.4) with retry+backoff, marks `consumed_at` on 200, increments `retries` on failure, dead-letters at threshold (~20 retries, ~24h delay).

**Event subscriber framework** (in consumer service): `POST /events/{eventType}` endpoint returning 200 if processed (idempotently) or 4xx if invalid (no retry). Per-service `{service}_processed_event_id` table dedupes by event ID. First instance: `inventory_processed_event_id` + `OrderReceivedEvent` handler in Phase 7.

**3-step choreography pattern.** When a flow requires atomic writes across 3 services (e.g., `saveOrderShipment`: ordering → inventory → shipping), implement as chained events: service A's handler emits to service B (via A's outbox); service B's handler emits to service C (via B's outbox). Each step is its own transaction with its own outbox. End-to-end consistency lag = sum of relay polling intervals (seconds). Per the Phase 7 / Phase 8 done gates in §6, the first 3-step chain (`saveOrderShipment`) only goes live at Phase 8 — at Phase 7 the chain runs 2 steps (ordering → inventory) with Grails-side ShipmentItem creation still in place.

**Lightweight orchestrator pattern.** When a 3-step flow needs compensation (e.g., if shipping-service rejects the ShipmentItem create, ordering-service needs to roll back Order's "received" status), the initiating service implements a small state machine: tracks the flow ID, awaits acks (via an inbound event), times out + compensates on failure. This is service-local code, not a framework. Used only when needed (currently anticipated: `saveOrderShipment`).

**Operational tooling** (minimal): Spring Boot Actuator on each service exposing outbox lag (oldest unconsumed event age) + dead-letter count; admin endpoint to inspect dead-letter rows; structured logs.

**Explicitly NOT in scope:** message broker (Kafka, RabbitMQ); external orchestrator (Camunda, Temporal); distributed tracing infrastructure beyond Actuator; event schema registry (events versioned per-service via Java package naming).

## 5. Tech choices

| Concern | Choice |
|---|---|
| **Backend services** | Spring Boot 3.x, Java 21 LTS, Spring Data JPA + Hibernate 6 |
| **Grails container runtime** | Stays on Java 8 until Phase 12 deletes Grails entirely |
| **Build** | Two Gradle wrappers: existing `gradle/wrapper` at 4.10.3 for Grails (unchanged); new `services/gradle/wrapper` at 8.x for Spring Boot services |
| **Module layout** | All services in this repo under `services/{context}-service/` as Gradle sub-modules of the new `services/` build |
| **Routing / gateway** | nginx (already in `docker/docker-compose.yml`); one `location` block per service; no separate gateway process |
| **Auth** | jjwt 0.11.x (Java 8 compatible) on Grails side; matching jjwt 0.12+ on Spring Boot side (Java 21); HMAC-HS256; HttpOnly SameSite=Strict cookie |
| **Inter-service comms** | Synchronous REST over nginx routing for queries and admin ops; transactional outbox + HTTP relay for eventually-consistent cross-service writes (per §4.5). No message broker. |
| **API contracts** | springdoc-openapi on each Spring Boot service; React generates clients from the generated OpenAPI spec |
| **Per-service schema migrations** | Each service has its own Liquibase changelog under `services/{context}-service/src/main/resources/db/changelog/`; runs on service startup; shares MariaDB's `DATABASECHANGELOG` table (filename-scoped per service) |
| **E2E tests** | Playwright (JS/TS, lives next to React in `e2e/`); existing Spock integration tests (`src/integration-test/`) stay in place as service-level regression coverage |
| **Per-slice tag** | `phase-N-{context}` git tag on `main` at each "done" gate |

## 6. Phase structure

13 phases total: Phase 0 foundations + Phases 1–11 slices + Phase 12 cleanup.

| Phase | Service / context | Saga involvement | Done gate |
|---|---|---|---|
| **0** | Foundations | none | nginx routing, JWT alongside JSESSIONID, Playwright harness, ProductApi.js fix — per §7 |
| **1** | **Document** | none | document-service serves Document + DocumentType; 7 Grails callers migrated to HTTP; Grails Document.groovy + service + controller deleted |
| **2** | **Identity** | none | identity-service mints+validates JWTs; Grails delegates to it; `SecurityInterceptor` gone or trivial; React LoginModal posts to identity-service |
| **3** | **Location** | none | location-service owns Location tables; other services that read location columns switch from direct JDBC to HTTP call |
| **4** | **Organization** | none | organization-service stands up (incl. Donor); admin screens go React |
| **5** | **Catalog** | none | catalog-service stands up (Product, Category, UoM, etc.); widest reader fan-in. ProductMergeService stays in Grails for now (moves in Phase 6) |
| **6** | **Inventory** | none yet | inventory-service stands up. **Two restructures land here:** (a) ProductMergeService moves from Grails to inventory-service; catalog-service exposes thin merge endpoint that delegates. (b) InventoryService import endpoint moves to inventory-service and calls catalog-service for product create-or-find via sync HTTP per row. **Service-porting deferral for cross-context writers:** Replenishment and StockTransfer entities move with the rest of inventory-service at Phase 6 (Liquibase ownership transfers; additive-only schema constraint applies), but ReplenishmentService and StockTransferService themselves stay in Grails through Phase 6 — both contain cross-context atomic writes whose saga consumers don't yet exist (OrderItem deletion → ordering-service Phase 7+; rollback shipment events → shipping-service Phase 8+). ReplenishmentService ports to inventory-service in Phase 7. StockTransferService ports to inventory-service in Phase 8. No saga yet for Phase 6's own scope — Order/Shipping/etc. still in Grails, so any Grails→inventory-service atomic writes route through Grails-side JDBC against the shared DB (additive-only schema constraint applies). Phase 6 is the largest single-service extraction (~4-6 weeks for one dev). |
| **7** | **Ordering** + **saga infrastructure** | introduces it | ordering-service stands up (Order, OrderItem, OrderAdjustment, etc.). **Saga infrastructure built here** — outbox table + relay + subscriber framework + 3-step choreography pattern + lightweight orchestrator for `saveOrderShipment` (per §4.5). First chained saga in Phase 7: **2-step chain** — `OrderReceivedEvent` from ordering-service → inventory-service consumes and creates InventoryItem. ShipmentItem creation continues to happen in Grails (Grails `OrderService` still owns `saveOrderShipment`) until Phase 8 extracts shipping-service; Phase 8 then adds the third step (shipping-service consumes `InventoryItemCreatedEvent` or a Phase-8-defined derived event and creates ShipmentItem), completing the full 3-step `saveOrderShipment` choreography. ReplenishmentService ports to inventory-service in this phase with its OrderItem-deletion saga wired in (per §6 Phase 6 deferral). Largest phase by infrastructure cost (~6-8 weeks for one dev). |
| **8** | **Shipping** | reuses saga (heavy) | shipping-service stands up. StockMovementService's many cross-context writes resolve via saga (~20+ events surface during scoping — pairwise inventory↔shipping). StockTransferService ports to inventory-service in this phase with its `deleteStockTransfer` (→ordering-service) and `rollback*` (→shipping-service) sagas wired in (per §6 Phase 6 deferral). Reuses Phase 7 framework; mostly new event types and handlers (~4-5 weeks). |
| **9** | **Requisition** | reuses saga | requisition-service stands up (Requisition, Picklist, Fulfillment, etc.). New events for any cross-context writes that surface during scoping. (~3-4 weeks). |
| **10** | **Billing** | reuses saga (light) | billing-service stands up (Invoice, InvoiceItem, GL, payment terms, budget codes). **Link tables for Invoice↔Order/Shipment/OrderAdjustment owned by billing-service per §4.3.** That resolves the bulk of audit findings locally. Saga only for genuinely business-critical async writes that surface. (~2-3 weeks). |
| **11** | **Reporting** | none | reporting-service stands up; read-only consumer of every other service. (~1-2 weeks). |
| **12** | **Cleanup** | none | Switch webpack output to standalone `index.html` + `frontend-dist/`; serve via nginx static. Delete `grails-app/`, `grailsw*`, root `build.gradle` (Grails parts), `gradle/wrapper` at 4.10.3, Grails Docker image, `src/main/groovy/`, `src/integration-test/groovy/`, `src/main/webapp/`. Repo collapses to `services/` + React. Promote `services/gradle/wrapper` to root. (~1 week). |

The Phase 3+ ordering is a recommendation. After Phase 2 the developer will have first-hand slice experience and may re-order; the per-slice template and the data-ownership model don't depend on the specific order, only on dependencies being satisfied (a slice can't extract before its data dependencies have either been extracted or accepted as shared-DB direct reads).

**Pace estimate (one developer):** Phases 1–5 (~1–3 weeks each) → ~10 weeks. Phase 6 (~5 weeks). Phase 7 (~7 weeks — saga harness + extraction). Phases 8–10 (~3–4 weeks each) → ~10 weeks. Phase 11 (~2 weeks). Phase 12 (~1 week). Total: ~35 weeks of focused work spread across whatever calendar time the developer wants. The user has chosen architectural integrity over saga-cost minimization explicitly.

## 7. Phase 0 in detail

### 7.1 nginx routing layer

`docker/docker-compose.yml` already includes nginx. Configure:

```
location /api/ {
    proxy_pass http://grails:8080/openboxes/api/;
    proxy_set_header Host $host;
    proxy_set_header Cookie $http_cookie;
}

location /openboxes/ {     # legacy GSP routes + React-hosted-by-Grails
    proxy_pass http://grails:8080/openboxes/;
    proxy_set_header Host $host;
    proxy_set_header Cookie $http_cookie;
}
```

Each subsequent slice adds one location block at the top of the list (more specific paths win), e.g., `location /api/documents/ { proxy_pass http://document-service:8081/; ... }` after Phase 1.

### 7.2 JWT auth alongside JSESSIONID

- Add `io.jsonwebtoken:jjwt-impl:0.11.5` and `jjwt-jackson:0.11.5` to `build.gradle` (Java-8 compatible line).
- New `JwtService.groovy` (in `grails-app/services/org/pih/warehouse/auth/`): HMAC-HS256 sign/validate. Claims: `{ sub: userId, loc: locationId, roles: [...], exp }`. Secret in env var `OPENBOXES_JWT_SECRET`. Token lifetime: 8 hours. No refresh tokens.
- Mint JWT and set the HttpOnly SameSite=Strict cookie `obx_token` on the response at all three login plant points (same `JwtService` helper):
  - `AuthController.handleLogin` success branch (line 117) — the GSP form-redirect login path.
  - `ApiController.login` success branch (line 41) — the React LoginModal login path (`POST /api/login`).
  - `ApiController.chooseLocation` (line 55) — re-issue JWT with updated `loc` claim when the React app changes location via `PUT /api/chooseLocation/{id}`.
- `SecurityInterceptor.before()` (current line 35): at the top, check for `obx_token` cookie. If present and valid, call `authService.setCurrentUser(...)` and `authService.setCurrentLocation(...)` with values from the claims, return `true`. If absent or invalid, fall through to the existing session-based logic.
- `AuthController.logout` (line 136): clear both `JSESSIONID` and `obx_token` cookies.
- `chooseLocation` action: when location changes, re-issue the JWT with the updated `loc` claim and reset the cookie.

### 7.3 Playwright E2E harness

- New `e2e/` directory at repo root with `playwright.config.ts`.
- Initial test set:
  - Login flow (form submit → `obx_token` cookie set → API call succeeds with cookie)
  - Navigation to a React route (e.g., `/openboxes/invoice/list`)
  - API call from React using the JWT cookie
  - GSP regression: `/openboxes/admin/index` still loads and works via JSESSIONID
- New `package.json` script `e2e`. CI job that boots the docker-compose stack and runs Playwright against it.
- Existing Spock integration tests stay untouched. (The Geb browser-test surface turned out to be much smaller than the survey suggested — see §11.)

### 7.4 Fix ProductApi.js to use apiClient

`src/js/api/services/ProductApi.js:15` — one-line change: replace raw `axios.get(INVENTORY_ITEM(...))` with `apiClient.get(INVENTORY_ITEM(...))`. So the JWT cookie is sent on this call too. (The other direct axios use — `SupportButton.jsx:21` calling HelpScout — stays as-is; it's a third-party API that doesn't need our auth.)

### 7.5 Phase 0 "done" gate

- `docker-compose up` brings up MariaDB + Grails + nginx — same as today.
- Logging in sets the `obx_token` cookie.
- API calls from the React app succeed (cookie sent automatically; SecurityInterceptor validates JWT path).
- A legacy GSP page (e.g., `/openboxes/admin/index`) still loads via the JSESSIONID path.
- Playwright tests for login, React navigation, API auth, GSP regression all green.
- Playwright test specifically logs in via the React LoginModal (POSTs `/api/login`) and confirms `obx_token` cookie is set on the response.
- One commit tagged `phase-0-foundations` on `main`. No Grails domain code deleted. Rollback by reverting the commit.

### 7.6 Explicitly NOT in Phase 0

- **No frontend build decoupling** (webpack continues to generate `common/react.gsp` and `partialReceiving/create.gsp`; React continues to be hosted by Grails). The 8+ Grails controllers that `render(view: "/common/react")` continue to work unchanged. Decoupling happens late, when most of those controllers have been deleted as part of their slice migrations — see Phase 12.
- **No OpenAPI spec for existing Grails API**. Grails endpoints are terminal — being deleted. springdoc-openapi gets added per slice on the Spring Boot side.
- **No shared JWT validation library**. Phase 1 establishes its shape based on one real consumer.
- **No external OIDC / Keycloak**. HMAC JWT is enough for one developer / no live users.
- **No refresh tokens, no token revocation, no JWKS**. Long-lived token, env-secret, single signing key.
- **No schema decomposition**. Each slice decomposes its tables when it extracts; Phase 0 doesn't touch the DB.
- **No deletion of any Grails code**. Phase 0 is purely additive.
- **No Java upgrade for the Grails container**. Stays Java 8.
- **No saga infrastructure**. Built in Phase 7 when first needed.

## 8. Per-slice template (Phases 1..11)

Every slice does these steps, in order:

| Step | What |
|---|---|
| 1 | **Identify scope.** List the Grails domain classes, services, controllers, GSPs, DB tables, and Liquibase changesets for this context. Also list external Grails callers of the context's domain classes (`grep` for `Foo.get`, `Foo.findBy*`, `foo.someColumn`). **Audit cross-context atomic writes touching this slice using the explicit checklist below; for each finding, apply the §4.3 coverage policy.** Document each in the slice's commit message.<br><br>**Cross-context atomic-write audit checklist** (run all four greps against the slice's source package; mark each finding against §4.3's resolution patterns):<br>a. `.addTo<X>(` / `.removeFrom<X>(` on instances of domain classes owned by OTHER γ services<br>b. `<DomainClass>.delete(` / `<lowercase>.delete(` where the DomainClass is owned by another γ service<br>c. `new <DomainClass>(` followed by `.save(` where the DomainClass is owned by another γ service<br>d. `def <otherDomain>Service` / `@Autowired <Other>Service` injections crossing γ boundaries; for each, inspect call sites for writes (not just reads) |
| 2 | **Create Spring Boot module** at `services/{context}-service/`. Spring Boot 3.x, Java 21, springdoc-openapi, jjwt 0.12+ for cookie validation. |
| 3 | **Port domain to JPA entities.** Rewrite Grails domain classes as Java JPA entities against the existing shared DB tables. Explicit `@Column`/`@Table` annotations match the existing schema so Hibernate 6's stricter naming doesn't conflict. Port GORM constraints to Bean Validation. |
| 4 | **Port services.** Rewrite Grails services as Java Spring `@Service`s. Same business rules. Cross-context reads against not-yet-extracted services stay as direct JDBC against the shared DB (note these as TODOs that will be revisited when that context's service exists). |
| 5 | **Port controllers.** Grails `*ApiController` → Spring `@RestController` annotated for springdoc-openapi. Generated OpenAPI spec is the slice's contract. |
| 6 | **Move table ownership.** Take the slice's Liquibase changesets out of `grails-app/migrations/` and into `services/{context}-service/src/main/resources/db/changelog/`. New service runs them at startup. **Additive-only constraint:** while external Grails callers of the slice's domain classes remain (Step 8b path), new migrations are restricted to additive changes only — new tables, new nullable columns with default values, new indexes. No column renames, no column removals, no type narrowings, no new NOT NULL or FK constraints on existing columns. The restriction lifts when Step 10 deletes the Grails domain class. |
| 7 | **Wire JWT validation + saga infrastructure (Phase 7+).** Service validates `obx_token` cookie via shared HMAC secret. Phase 7+ services also include outbox writer, relay, and subscriber framework per §4.5. |
| 8a | **Update React frontend.** Existing React code for this context calls the new URLs (`/api/{context}/...`). Any GSP-only screens for this context get React equivalents now. |
| 8b | **Handle external Grails callers.** For each call site outside the slice that uses `Foo.get(id)` or reads `foo.columns`: either migrate it to HTTP against the new service (preferred when caller count is small, e.g., the 7 callers of Document), or leave the Grails domain class alive until the caller migrates in its own later slice. Document this decision in the slice's commit message. |
| 9 | **Update nginx.** Add `location /api/{context}/ { proxy_pass http://{context}-service:80XX/; }` at the top of the location list. Remove the corresponding `/api/{context}/...` mapping from Grails `UrlMappings.groovy` if no callers remain. |
| 10 | **Delete Grails counterparts.** Conditional: delete only artifacts no remaining Grails code references. If a domain class still has Grails callers (per 8b), leave it; tag those callers with a `// TODO(migrate-to-{context}-service)` comment. If all callers are gone, delete the Grails domain class, service, controller, GSPs, and taglibs for the context. Grep verifies no remaining references. |
| 11 | **Tests.** Playwright E2E added for the new flow (happy path + 2-3 error paths). Existing Spock integration tests for the slice's logic ported to JUnit/Spock on the Spring Boot side, or deleted if obsolete. For phases with saga events: integration test the event emission + subscriber idempotency. |
| 12 | **Soak.** Run the new state against realistic test data for the chosen interval. No regressions before next phase opens. For saga-using phases: verify outbox lag stays bounded and no events dead-letter. |
| 13 | **Tag.** `phase-N-{context}` git tag on `main`. Phase can be reverted with one revert during soak. |

## 9. Phase 1 detail — Document slice

The first slice. Estimated 1-2 weeks.

**Scope.**
- Tables: `document`, `document_type`
- Domain classes: `Document.groovy`, `DocumentType.groovy`, `DocumentCode` enum (if separate)
- Service: `DocumentService.groovy`
- Controller: `DocumentController.groovy` + any `DocumentApiController.groovy`
- Views: `grails-app/views/document/`
- External Grails callers (7+): `DataExportController` (lines 23, 28-34), `TemplateService` (lines 20-21), `StockMovementService` (lines 3301, 3455-3458), `InvoiceController` (lines 142, 161), `ProductController` (lines 548, 607)

**Module.** `services/document-service/` — Spring Boot 3.x, Java 21, Gradle 8.

**HTTP surface.**
- `GET /api/documents/{id}` — metadata
- `GET /api/documents/{id}/content` — binary (streams `fileContents` bytes)
- `POST /api/documents` — multipart upload (replaces the StockMovementService write path)
- `GET /api/documents?code={DocumentCode}` — filter by document code (replaces `findAllByDocumentCode`)
- `DELETE /api/documents/{id}`

**Caller migrations.**
- `DataExportController.list` (line 23) → `GET /api/documents?code=DATA_EXPORT`
- `DataExportController.download` (line 28) → `GET /api/documents/{id}/content` (stream to response)
- `TemplateService.renderTemplate` (lines 20-21) → fetch via HTTP, render as before
- `StockMovementService.uploadDocument` (lines 3455-3458) → POST multipart to document-service
- `StockMovementService.requisitionTemplates` (line 3301) → `GET /api/documents?code=REQUISITION_TEMPLATE`
- `InvoiceController` + `ProductController` `Document.get(params.id)` → HTTP fetch (`GET /api/documents/{id}/content`)

**Grails deletion.** After all 7 callers migrated and tests green: delete `Document.groovy`, `DocumentType.groovy`, `DocumentService.groovy`, `DocumentController.groovy`, `grails-app/views/document/`, and the corresponding URL mappings.

**E2E tests.** Playwright tests for: upload via the React UI, download via UI, list-by-code via UI, deletion. Plus regression for the migrated caller flows (Data Export, template rendering, stock movement upload).

## 10. Verified assumptions

The following load-bearing assumptions were verified against the codebase before this spec was committed.

**A1–A20** (from prior brainstorming; verified pre-commit and re-confirmed in CDR Rounds 1, 2, and 3 §1):

| # | Assumption | Result | Key evidence |
|---|---|---|---|
| A1 | webpack outputs to `src/main/webapp/webpack`; generates two GSPs | ✅ | `webpack.config.js:7,87-98,108-123` |
| A2 | Webpack 5 + DefinePlugin can inject `process.env.REACT_APP_*` | ✅ | `webpack.config.js:101-107` already does this for Sentry vars |
| A3 | React-owned URL paths enumerable | ✅ with caveat | `src/js/components/Router.jsx:254-294` — all routes use `**/` prefix because they're also Grails routes; Grails controllers `render(view:'/common/react')` to host React |
| A4 | Java runtime version | ⚠️ Forced decision resolved | `docker/Dockerfile:1` — Java 8 Temurin. Resolution: jjwt 0.11.x on Grails side (Java 8 compatible); Java 21 on Spring Boot services |
| A5 | AuthController.handleLogin shape | ✅ | `AuthController.groovy:117` — single success branch where JWT cookie issuance plants |
| A6 | SecurityInterceptor is single auth chokepoint | ✅ | Zero `@Secured`/`@PreAuthorize` annotations anywhere; `SecurityInterceptor.groovy:27` `matchAll()` is the sole hook |
| A7 | AuthService entry points | ✅ | `AuthService.groovy:24,37` — `setCurrentUser(User)`, `setCurrentLocation(Location)` via ThreadLocal |
| A8 | No existing JWT infra to conflict | ✅ | Zero `jwt`/`jjwt`/`oauth`/`Bearer` matches in build.gradle, grails-app, src/main/groovy |
| A9 | Gradle version + Spring Boot 3 requirement | ⚠️ Forced decision resolved | `gradle-wrapper.properties` — 4.10.3. Resolution: two-wrapper layout — Grails keeps 4.10, new `services/` uses Gradle 8.x |
| A10 | Document is a real bounded context | ✅ structurally | `Document.groovy`, `DocumentService.groovy`, `DocumentController.groovy`, `views/document/` all present |
| A11 | Document referenced only by ID | ❌ Surfaced design change | 7+ external callers read internals (`fileContents`, `name`, `contentType`, `filename`); resolved via Step 8b in per-slice template |
| A12 | User/Role/Person form coherent identity context | ✅ with caveat | `User.groovy:17` extends `Person`; `User.groovy:34` `locationRoles` couples to Location; identity-service owns user_role + location_role tables, Location reference becomes FK |
| A13 | "Current location" fits as JWT claim | ✅ | `SecurityInterceptor:38-39` reads session.warehouse; translates to JWT `loc` claim, re-issued on `chooseLocation` |
| A14 | GORM features have JPA equivalents | ✅ | `User.groovy` uses hasMany, mapping, joinTable, cascade, sqlType, inheritance — all portable to JPA annotations |
| A15 | Shared DB works with two Hibernate clients | ✅ | Hibernate 5.2 (Grails) and Hibernate 6 (Spring Boot) coexist; Liquibase owns DDL; explicit `@Column` annotations resolve naming-strategy differences |
| A16 | Existing Geb suite targets GSP | ⚠️ Smaller than expected | Geb dependency present (`build.gradle:645`) but base `IntegrationSpec` extends `Specification` (Spock), not GebSpec; only one file matches GebSpec pattern. Playwright is purely additive |
| A17 | Liquibase split feasible | ✅ | `LiquibaseUtil.groovy` tracks per-file via `DATABASECHANGELOG.filename` SUBSTRING; per-service changelogs coexist as long as filenames are namespaced |
| A18 | All React API calls go through apiClient | ✅ except 2 | `src/js/api/services/ProductApi.js:15` raw axios — fixed in Phase 0.4; `SupportButton.jsx:21` calls 3rd-party HelpScout — stays as-is |
| A19 | No external dependency on generated GSPs | ❌ Surfaced design change | 8+ controllers `render(view: "/common/react")`. Resolution: defer frontend decoupling to Phase 12 (by then most of these controllers will already have been deleted) |
| A20 | Jobs / BootStrap don't depend on session auth | ✅ | All 13 jobs declare `def sessionRequired = false`; BootStrap.groovy has zero session/request references |

**A21–A30** (verified during brainstorming Round 2 — empirical audit of cross-context atomic writes and service-boundary feasibility):

| # | Assumption | Result | Key evidence |
|---|---|---|---|
| A21 | No cross-context atomic writes in Quartz jobs or GORM events; controllers' cross-context writes either delegate to audited services or are surfaced per-phase via §8 Step 1 | ⚠️ Partial | Jobs and domain events clean; controllers contain at least three direct cross-context writes not delegated (`InventoryItemController.groovy:936` inventory→shipping, `DocumentUploadController.groovy:20` shipping→document, `InvoiceController.groovy:166` billing→document). Absorbed by §11 policy-based coverage + §8 Step 1 per-phase audit |
| A22 | M:N relationships InvoiceItem↔OrderItem / OrderAdjustment / ShipmentItem are implemented as join tables | ✅ | `InvoiceItem.groovy:hasMany + mapping` — three `joinTable` declarations (`order_invoice`, `shipment_invoice`, `order_adjustment_invoice`); confirmed bidirectionally on `OrderItem`, `OrderAdjustment`, `ShipmentItem` |
| A23 | `OrderService.saveOrderShipment` atomically writes Shipment + ShipmentItem + InventoryItem across what will become 3 services | ❌ Surfaced design change | `OrderService.groovy:300-340` — single method creates Shipment + N ShipmentItems + N InventoryItems all atomically. Resolution: 3-step choreography via chained outbox events (§4.5); lightweight orchestrator in ordering-service for rollback if downstream rejects |
| A24 | `StockMovementService` has dense cross-context coupling with shipping-service | ⚠️ Larger than expected | `StockMovementService.groovy` — 20+ call sites to `shipmentService.*` (sendShipment, createShipmentEvent, rollbackLastEvent, etc.) plus 6+ call sites to `inventoryService.findOrCreateInventoryItem`. Resolution: per-method 2-step saga, surfaced and resolved during Phase 8 (Shipping) scoping |
| A25 | `ProductMergeService` has no callers outside catalog/ | ✅ | Only `ProductController.groovy:1196,1208` invokes it. Safe to move implementation to inventory-service at Phase 6 |
| A26 | `ProductMergeService` Product entity writes are minimal | ✅ | `ProductMergeService.groovy:459-463` — only `obsolete.active = false`, `obsolete.save()`, `primary.save()`. Single sync HTTP call from inventory-service back to catalog-service post-merge handles this |
| A27 | `InventoryService` import flow is admin-scale | ✅ | `InventoryService.processData()` (lines 2300+) iterates `command?.data?.each` with per-row category-find-or-create + product-find-or-create + inventory writes. Per-row sync HTTP to catalog-service is consistent with existing per-row processing |
| A28 | Grails `core/` package is a shared kernel containing entities owned by multiple services | ✅ Required handling | `grails-app/domain/org/pih/warehouse/core/` contains User (→ identity), Location/LocationGroup (→ location), GlAccount/PaymentTerm/BudgetCode (→ billing), and many others. Resolution: §4.3 entity-list maps by NAME; at each slice extraction, the relevant `core/` entities migrate to that service's module; `core/` shrinks each slice; deleted in Phase 12 |
| A29 | Domain classes have cross-package field references that become HTTP calls at extraction | ⚠️ Per-slice work | `Order.groovy` imports `invoice.InvoiceItem`, `shipping.Shipment`; `Shipment.groovy` imports `inventory.InventoryItem`, `order.Order`; `OrderAdjustment.groovy` imports `invoice.InvoiceItem`. Each cross-package field reference becomes an HTTP call or shared-DB read at extraction time per the per-slice template (§8). Field-level resolution is per-entity work during each phase |
| A30 | Reporting-service is read-only on other services | ✅ | grep of `grails-app/services/org/pih/warehouse/report/` for `.save`/`.delete` patterns — only intra-reporting writes (DateDimension.save in ReportService); no cross-service writes back to operations/billing/catalog |

## 11. Known issues / accepted as out of scope

- **Java 8 EOL on Grails container.** Grails stays on Java 8 until Phase 12. Java 8 has been EOL upstream since 2022 (Oracle commercial support). Eclipse Temurin still provides Java 8 builds. Accepted because (a) no live users — security exposure is local-only; (b) Grails 3.3.16 + Groovy 2.4 fights Java 11+ in subtle ways; (c) the problem deletes itself in Phase 12.
- **Gradle 4.10.3 on Grails container.** Same logic — stays until Phase 12.
- **Liquibase `LiquibaseUtil` versioning lives until Grails dies.** New services use their own changelogs and runners; LiquibaseUtil keeps owning the Grails `0.X.x/` folders. The version-folder scheme retires when Grails does.
- **`SupportButton.jsx` calls HelpScout directly with raw axios.** Out of scope; third-party API; no auth required.
- **Webpack continues to write GSPs through Phase 11.** Cosmetic only — the GSPs are generated automatically; no manual maintenance.
- **No observability / metrics infrastructure called out.** Out of scope. Add when there's a real consumer; for a single-developer / no-live-users setup, Spring Boot Actuator on each service + outbox lag/dead-letter endpoints (per §4.5) is enough.
- **No multi-tenant / external OIDC consideration.** Out of scope; HMAC JWT is fit-for-purpose. Replace with OIDC if/when multi-tenancy is real.
- **Slice phase ordering after Phase 2 is provisional.** Real ordering will be informed by Phase 1 and Phase 2 experience.
- **Cross-context atomic-write coverage is policy-based, not exhaustive.** §4.3 enumerates the currently-known cases; additional writes surface per-phase scoping and resolve via the documented policy. The design intentionally does NOT claim "single eventual-consistency exposure" — that claim is what failed in prior γ design rounds.

## 12. Risks

- **Phase 1 turns out to be larger than 1-2 weeks.** Document's 7+ callers are an estimate; some (e.g., StockMovementService's upload path) may be more entangled than they look. Mitigation: scope the work in detail before starting Phase 1; if it exceeds 3 weeks, reconsider whether PaymentTerm is a better Phase 1.
- **GORM-to-JPA mapping for inheritance (User extends Person) is non-trivial.** Phase 2 hits this first. Mitigation: budget extra time for the first JPA inheritance mapping; the pattern is reused for the few other inheritance cases in the domain.
- **Hibernate 5 / Hibernate 6 sharing a schema may surface naming-strategy gotchas.** Mitigation: explicit `@Column` and `@Table` on every JPA entity matching the existing column names.
- **External Grails callers may cascade.** Migrating a caller in Phase N to use the Phase 1 service may surface its OWN consumers; the migration tree could grow. Mitigation: when 8b reveals deep chains, accept "keep Grails counterpart alive" and migrate later; don't expand slice scope unboundedly.
- **One-developer pace.** 13 phases is a multi-year undertaking (~35 weeks of focused work). Mitigation: each phase has independent value (each one shrinks Grails); pausing between phases is fine; nothing forces continuous work.
- **Saga infrastructure complexity in Phase 7.** Phase 7 ships outbox + relay + subscriber framework + 3-step choreography + lightweight orchestrator alongside the ordering-service extraction. ~6-8 weeks vs. ~3 for a normal phase. Mitigation: Phases 8-10 reuse the framework; the upfront cost amortizes across 4 phases.
- **Cross-context writes surface during phase scoping.** §4.3's policy handles whatever surfaces, but if a phase finds many more writes than estimated, the phase could expand. Mitigation: per-slice template Step 1 explicitly audits cross-context writes; if a phase's audit finds 20+ surprises, pause and revisit boundaries before committing to extraction.
- **3-step choreography failure modes.** `saveOrderShipment`'s 3-step chain has compensation needs that the lightweight orchestrator must handle correctly. Mitigation: integration tests for the chain (Step 11 of per-slice template); soak before declaring done; specific test for partial-failure (shipping-service rejects ShipmentItem, ordering-service must roll back Order's "received" status).
