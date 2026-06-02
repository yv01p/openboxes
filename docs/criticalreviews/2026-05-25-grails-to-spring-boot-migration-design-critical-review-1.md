# Critical Design Review: 2026-05-25-grails-to-spring-boot-migration-design (Round 1)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
**Verified Assumptions section:** present (§10 of spec)

## 1. Verified-assumptions cross-check

All 20 assumptions reconfirmed under fresh read of the cited evidence:

- **A1** (webpack output + GSP generation): `webpack.config.js:7,87-98,108-123` matches spec claim.
- **A2** (DefinePlugin available): `webpack.config.js:101-107` already injects `process.env.REACT_APP_*` for Sentry vars.
- **A3** (React routes enumerable): `src/js/components/Router.jsx:254-294` matches.
- **A4** (Java 8): `docker/Dockerfile:1` — `FROM eclipse-temurin:8-jre-jammy` confirmed.
- **A5** (handleLogin shape): `AuthController.groovy:117` has the cited success branch. (See §2.1 for an issue in how the spec USES this assumption.)
- **A6** (SecurityInterceptor single chokepoint): zero `@Secured`/`@PreAuthorize` annotations confirmed.
- **A7** (AuthService entry points): `setCurrentUser` at line 24, `setCurrentLocation` at line 37, via ThreadLocal.
- **A8** (no existing JWT infra): confirmed.
- **A9** (Gradle 4.10.3): `gradle/wrapper/gradle-wrapper.properties` confirms.
- **A10** (Document is a real context): `Document.groovy`, `DocumentType.groovy`, `DocumentService.groovy`, `DocumentController.groovy`, `grails-app/views/document/` all present.
- **A11** (7+ Document callers): all citations confirmed (DataExportController:23,28-34; TemplateService:20-21; StockMovementService:3301,3455-3458; InvoiceController:142,161; ProductController:548,607).
- **A12** (User/Role/Person coherent): `User.groovy:17` extends Person; `User.groovy:34` locationRoles confirmed.
- **A13** (current-location as JWT claim): `SecurityInterceptor:38-39` reads session.warehouse; mechanism is portable.
- **A14** (GORM features portable): User.groovy uses portable features.
- **A15** (shared DB with two Hibernates): plausible per Liquibase ownership of DDL.
- **A16** (Geb suite smaller than expected): `IntegrationSpec` extends Spock Specification, not GebSpec.
- **A17** (Liquibase split feasible): `LiquibaseUtil.groovy` per-filename scheme supports namespacing.
- **A18** (apiClient chokepoint + 2 exceptions): ProductApi.js:15 + SupportButton.jsx:21 confirmed.
- **A19** (8+ controllers render react.gsp): grep evidence confirmed.
- **A20** (jobs `sessionRequired = false`): all 13 jobs confirmed.

## 2. Literal-wrongness findings

### 2.1 Phase 0 JWT issuance plants in the wrong place — React login bypasses it

**Description.** Phase 0.2 specifies that JWT cookie issuance plants in `AuthController.handleLogin` (line 117 success branch). That action is the **GSP form-submit-redirect** login path — used by users hitting `/openboxes/auth/login` and submitting the form. The **React LoginModal** does not call this path. It POSTs to `/api/login`, which is `ApiController.login()` (line 41 of `ApiController.groovy`) — a completely separate action, mapped via URL mappings to the `api` controller.

The spec's done-gate "API calls from the SPA succeed (cookie sent automatically)" passes in Phase 0 only because Grails still accepts `JSESSIONID` (the React user has a session cookie from `ApiController.login()` even with no JWT). The failure surfaces in **Phase 1**, where document-service expects the `obx_token` cookie for authentication and the React user doesn't have one — document-service rejects every request.

**Evidence.**
- `src/js/components/LoginModal.jsx:25` — `const url = '/api/login'; apiClient.post(url, payload)`
- `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy:41` — `def login() { ... }` (the action that handles `/api/login`)
- `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy:55` — `def chooseLocation()` (also called by React via `/api/chooseLocation/{id}` per LoginModal.jsx:41)
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy:117` — `redirect(controller: 'dashboard', action: 'index')` — confirms this is a server-side redirect flow, not a JSON API
- `grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy:19` — `actionsWithAuthUserNotRequired` contains `'json'` (used by the API path), distinct from `'handleLogin'` (the GSP path) — these are two parallel login mechanisms

**Proposed fix.** Phase 0.2 plants JWT issuance in BOTH places:
1. `AuthController.handleLogin` success branch (line 117) — for GSP login.
2. `ApiController.login` success branch (line 41ish, wherever it sets `session.user`) — for React login.
3. `ApiController.chooseLocation` (line 55) — re-issue JWT with updated `loc` claim when the React app changes location.

Same `JwtService` helper called from all three plant points. The done-gate Playwright test must specifically log in via the React LoginModal (not just via a GSP form post) and confirm the `obx_token` cookie is set.

### 2.2 Per-slice Step 8b silently requires additive-only schema migrations in the new service

**Description.** Step 8b allows leaving the Grails domain class alive when external callers remain after the slice extracts. The new Spring Boot service owns the tables (Step 6 — Liquibase ownership moves), so future migrations originate from the new service. But Grails GORM is still actively querying and writing those tables via the surviving Grails domain class — and GORM expects specific column names, types, and constraints that the spec doesn't lock down.

The asked-for behavior of Step 8b — "the Grails domain class continues to work until its callers migrate" — fails silently the first time the Spring Boot service ships a non-additive change (column rename, column removal, type change, NOT NULL addition without default, FK constraint addition). GORM throws at the first query that hits the changed column; the surviving Grails callers break in production. The spec does not name this constraint, so a developer following the spec faithfully will hit this.

**Evidence.**
- Spec §8 Step 6: "Take the slice's Liquibase changesets out of `grails-app/migrations/` and into `services/{context}-service/src/main/resources/db/changelog/`."
- Spec §8 Step 8b: "leave the Grails domain class alive until the caller migrates in its own later slice."
- Spec §8 Step 10: "If a domain class still has Grails callers (per 8b), leave it; tag those callers with a `// TODO(migrate-to-{context}-service)` comment."
- Nothing in §8 or §9 constrains what the Spring Boot service is allowed to change about the schema while the Grails domain class is still alive.

**Proposed fix.** Add an explicit constraint to Step 6 (or Step 8b): *"While external Grails callers of the slice's domain class remain (Step 8b path), the Spring Boot service's schema migrations are restricted to additive changes only — new tables, new nullable columns with default values, new indexes. No column renames, no column removals, no type narrowings, no new NOT NULL or FK constraints. The restriction lifts only after Step 10 deletes the Grails domain class."* This converts a silent breakage into an explicit slice-scope rule.

For the Document slice (Phase 1) specifically: the spec migrates all 7 callers in Phase 1 itself, so the Grails Document class is deleted at Phase 1 end and the constraint doesn't bite. But starting at Phase 2 onwards, any slice that defers caller migration runs into this.

## 3. Forced decisions

### 3.1 Cross-service WRITE strategy

**The choice.** Spec §4.3 addresses cross-service READS (HTTP calls between services). It does not address cross-service WRITES. Today OpenBoxes routinely performs multi-table writes that span what will become service boundaries, in single Grails transactions. After Phase 6+, those writes can no longer be a single transaction.

Concrete cross-slice write flows the spec must account for:
- **Order placement** (Phase 10) — creates `Order`, creates `OrderItem`, may reserve `Inventory` (Phase 6). Today: one transaction.
- **Shipment receiving** (Phase 8) — creates `Shipment`/`ShipmentItem`, creates `InventoryItem` (Phase 6), increments `Inventory` levels (Phase 6). Today: one transaction.
- **Cycle count approval** (Phase 7) — creates `CycleCountItem` adjustments, modifies `Inventory` levels (Phase 6). Today: one transaction.
- **Invoice posting** (Phase 11) — updates `Invoice`, updates referenced `Order` status (Phase 10). Today: one transaction.

After service extraction, the first part runs in one service's transaction; the cross-service part is an HTTP call that succeeds or fails independently. Without a strategy, the system can land in inconsistent states (Order created but Inventory not reserved; Shipment received but InventoryItem not created).

**Why it's forced.** From Phase 6 onward, every slice that participates in a cross-write flow has to commit to a mechanism. The choice cannot be deferred — the first cross-service write that ships has already picked one (or has a silent bug).

**Options.**
- **(a) Saga pattern with compensation.** Each cross-service write step is its own transaction; if step 2 fails, step 1 is compensated by a reverse operation. Strong consistency at the boundary, complex to design per-flow.
- **(b) Eventual consistency with reconciliation.** Each service writes its piece; a background reconciler detects inconsistencies and resolves them (or alerts). Simplest forward path; hardest to reason about and to recover from edge cases.
- **(c) Keep cross-write contexts in the same service.** Don't actually split Inventory from the contexts that write to it most heavily — group Inventory + StockMovement + Receiving + CycleCount into one service (or accept one larger "operations-service" boundary). Reduces granularity to preserve transaction boundaries.
- **(d) Distributed transactions (2PC).** Spring Boot 3 can drive XA against a single MariaDB instance technically, but cross-service XA across HTTP is essentially unsupported; would require an XA-aware messaging layer (Atomikos / Narayana). Heaviest infrastructure.

### 3.2 Service-to-service / Grails-to-service authentication

**The choice.** Spec §7.2 and §8 Step 7 describe how the React app authenticates to Spring Boot services (via the `obx_token` cookie). The spec does not describe how OTHER callers authenticate:
- **Grails-to-service calls** start at Phase 1. When `DataExportController.download` is migrated to call `document-service` over HTTP (per the Phase 1 caller-migration list in §9), the request originates server-side in the Grails JVM. The Grails controller has access to the user's incoming cookies (forwardable), but no design decision is made.
- **Service-to-service calls** start at Phase 3+ (per §4.3, "the read switches from direct JDBC to a service-to-service HTTP call as part of that later slice"). When `inventory-service` calls `product-service`, there may or may not be an originating user request to forward credentials from (e.g., scheduled jobs migrated out of Grails would have no user context).

**Why it's forced.** Phase 1's caller migration forces a choice the first time `DataExportController` makes an HTTP call to `document-service`. Phase 3+ forces it again for genuinely service-to-service calls. The choices have meaningfully different infrastructure and security properties; the spec needs to pick before Phase 1 ships.

**Options.**
- **(a) Forward the user's `obx_token` cookie.** Caller (Grails controller or upstream Spring Boot service) reads the incoming cookie and forwards it on outbound calls. Works for user-initiated flows; fails for jobs / scheduled work with no user context. Simplest auth model.
- **(b) Per-service identity JWT.** Each service holds a long-lived signed JWT (or signs short-lived tokens) identifying itself as the caller. Receiver validates `iss` and `sub` claims. Works for jobs and user-initiated calls uniformly. More moving parts (token issuance, service identity provisioning).
- **(c) Network-trust (no service-to-service auth).** Internal docker-compose network is trusted; only the perimeter (nginx) enforces auth. Simplest infrastructure; relies entirely on network isolation; no per-service authorization for service-to-service operations.

## 5. Recommendation

🛑 **Surface forced decisions to user.** §2 has two literal-wrongness fixes that should land in the spec before implementation (the JWT plant points and the additive-only schema rule). §3 has two forced decisions (cross-service writes, service-to-service auth) that must be picked before Phase 1 work begins — the first one bites at Phase 6, the second one bites at Phase 1.

Recommended next step after resolving these: update the design spec, then move to `superpowers:writing-plans` (or `thorough-writing-plans`) for Phase 0.
