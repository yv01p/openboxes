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

**Strangler-fig with vertical slices.** Each phase is one bounded context migrated end-to-end: schema ownership moves, Spring Boot service stands up, React frontend talks to the new service, Grails counterparts deleted (or marked for later deletion if external Grails callers remain). The Grails monolith shrinks every phase, never grows. Total: one foundation phase + ~12 slice phases + one cleanup phase.

Alternatives considered and rejected:

- **Horizontal layers** (infrastructure → all backends → frontend): phase boundaries too large to soak meaningfully; "extract every backend service in one phase" is a flag-day, not a phase.
- **Parallel greenfield** (build new system alongside, sync data, cut over): too much dual-maintenance work for one developer; multi-year data-sync project; original Grails app keeps developing during the sync so parity is a moving target.

## 4. Resolution of the four forced decisions from the arch review

### 4.1 Migration strategy (§2.1 of arch review)
**Strangler-fig with vertical slices.** Each Phase 1..N is a strangler step that removes Grails code as it adds Spring Boot code.

### 4.2 Sequencing (§2.2)
**Vertical slices.** Each slice migrates one context's backend AND frontend together. React-on-Grails screens migrate slice-by-slice as their backend contexts are extracted; GSP-only screens get React frontends as part of their context's slice.

### 4.3 Data ownership during transition (§2.3)
**Shared MariaDB during transition; each new service owns its tables; cross-service reads via direct JDBC from the new service into the shared DB until the table-owning service exists.** When a cross-context dependency's owning service exists (e.g., the Location service is migrated), the read switches from direct JDBC to a service-to-service HTTP call as part of that later slice. Identity and reference data (Location, Product) are prioritized so they don't sit as direct-JDBC dependencies for long.

### 4.4 Auth during coexistence (§2.4)
**JWT issued by Grails in Phase 0, served as `obx_token` HttpOnly SameSite=Strict cookie alongside the existing `JSESSIONID`. Grails `SecurityInterceptor` accepts both during transition. Phase 2 moves issuance to identity-service; Grails then validates JWTs only. Final state: no session cookies anywhere.** No external OIDC provider; HMAC-HS256 with secret in env var `OPENBOXES_JWT_SECRET`.

## 5. Tech choices

| Concern | Choice |
|---|---|
| **Backend services** | Spring Boot 3.x, Java 21 LTS, Spring Data JPA + Hibernate 6 |
| **Grails container runtime** | Stays on Java 8 until Phase 13 deletes Grails entirely |
| **Build** | Two Gradle wrappers: existing `gradle/wrapper` at 4.10.3 for Grails (unchanged); new `services/gradle/wrapper` at 8.x for Spring Boot services |
| **Module layout** | All services in this repo under `services/{context}-service/` as Gradle sub-modules of the new `services/` build |
| **Routing / gateway** | nginx (already in `docker/docker-compose.yml`); one `location` block per service; no separate gateway process |
| **Auth** | jjwt 0.11.x (Java 8 compatible) on Grails side; matching jjwt 0.12+ on Spring Boot side (Java 21); HMAC-HS256; HttpOnly SameSite=Strict cookie |
| **Inter-service comms** | Synchronous REST over the same nginx routing as the frontend uses; no message broker until a real async use case appears |
| **API contracts** | springdoc-openapi on each Spring Boot service; React generates clients from the generated OpenAPI spec |
| **Per-service schema migrations** | Each service has its own Liquibase changelog under `services/{context}-service/src/main/resources/db/changelog/`; runs on service startup; shares MariaDB's `DATABASECHANGELOG` table (filename-scoped per service) |
| **E2E tests** | Playwright (JS/TS, lives next to React in `e2e/`); existing Spock integration tests (`src/integration-test/`) stay in place as service-level regression coverage |
| **Per-slice tag** | `phase-N-{context}` git tag on `main` at each "done" gate |

## 6. Phase structure

| Phase | Context | Done gate |
|---|---|---|
| 0 | Foundations: nginx routing layer confirmed; JWT alongside JSESSIONID; Playwright E2E harness; ProductApi.js fixed to use apiClient | Existing app works identically; JWT cookie issues on login; Playwright tests green for login, React nav, API auth, GSP regression |
| 1 | **Document / Attachment** (with 7-callsite migration folded in) | document-service serves Document + DocumentType; React docs-related UI talks to it; 7 Grails callers migrated to HTTP; Grails Document.groovy + service + controller deleted |
| 2 | **Identity** (User, Role, Person, LocationRole, auth issuance) | identity-service mints and validates JWTs; Grails delegates to it; `SecurityInterceptor` gone or trivial; React LoginModal posts to identity-service |
| 3 | **Location** (Location, LocationGroup, LocationRole, LocationType, LocationStatus) | location-service owns location tables; other services that read location columns switch from direct JDBC to HTTP call |
| 4 | **Organization** (Organization, Party, PartyRole, PartyType, Supplier, Shipper, Address) | organization-service stands up; admin screens go React |
| 5 | **Product** (Product, ProductAttribute, ProductPackage, ProductSupplier, ProductCatalog, Category, Tag, UnitOfMeasure, UnitOfMeasureClass, Synonym) | product-service stands up; widest fan-in; biggest reference slice |
| 6 | **Inventory core** (Inventory, InventoryItem, InventoryLevel, InventorySnapshot) | inventory-service owns inventory state; reads Product+Location via HTTP |
| 7 | **CycleCount** (CycleCount, CycleCountItem, CycleCountRequest, CycleCountCandidate, CycleCountProductSummary) | cyclecount-service replaces existing partially-React-owned cycle count flow |
| 8 | **Shipment + Receiving + PutAway** (Shipment, ShipmentItem, ShipmentType, ShipmentMethod, ShipmentWorkflow, Receipt, PutAway) | shipping-service stands up; Receiving and PutAway React flows talk to it |
| 9 | **StockMovement + StockTransfer + LocalTransfer + Replenishment** (and the partialReceiving controller paths) | stock-movement-service stands up; the most-React-heavy area moves to its own backend |
| 10 | **Order + Requisition + Fulfillment** (Order, OrderItem, OrderType, OrderAdjustment, OrderSummary, Requisition, RequisitionItem, Fulfillment, FulfillmentItem) | order-service stands up; PurchaseOrder is included if it doesn't warrant its own slice (revisit at the time) |
| 11 | **Invoice + Finance** (Invoice, InvoiceItem, InvoiceType, GlAccount, GlAccountType, PaymentTerm, PaymentMethodType, BudgetCode) | finance-service stands up; depends on Order + Shipment already extracted |
| 12 | **Reporting + dimensional models** (DateDimension, LocationDimension, ProductDimension, TransactionTypeDimension, LotDimension; reporting endpoints; expirationHistory; reorderReport) | reporting-service stands up; read-only consumer of every other service |
| 13 | Cleanup: by this point every Grails controller that `render(view:'/common/react')` has been deleted as part of its slice (Phases 1-12), so the webpack-generated GSPs have no consumers. Switch webpack output to a standalone `index.html` + `frontend-dist/`; serve via nginx static. Delete `grails-app/`, `grailsw*`, root `build.gradle` (Grails parts), `gradle/wrapper` at 4.10.3, the Grails Docker image, `src/main/groovy/`, `src/integration-test/groovy/`, `src/main/webapp/`. Repo collapses to `services/` + React. Promote `services/gradle/wrapper` to root. | Repository is Java + Spring Boot + React only; CI builds without Grails toolchain; docker-compose runs only Spring Boot services + React (via nginx) + MariaDB |

The Phase 3+ ordering is a recommendation. After Phase 2 the developer will have first-hand slice experience and may re-order; the per-slice template and the data-ownership model don't depend on the specific order, only on dependencies being satisfied (a slice can't extract before its data dependencies have either been extracted or accepted as shared-DB direct reads).

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
- `AuthController.handleLogin` (line 117 success branch): after `session.user = userInstance`, mint a JWT and set the HttpOnly SameSite=Strict cookie `obx_token` on the response.
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
- One commit tagged `phase-0-foundations` on `main`. No Grails domain code deleted. Rollback by reverting the commit.

### 7.6 Explicitly NOT in Phase 0

- **No frontend build decoupling** (webpack continues to generate `common/react.gsp` and `partialReceiving/create.gsp`; React continues to be hosted by Grails). The 8+ Grails controllers that `render(view: "/common/react")` continue to work unchanged. Decoupling happens late, when most of those controllers have been deleted as part of their slice migrations — see Phase 13.
- **No OpenAPI spec for existing Grails API**. Grails endpoints are terminal — being deleted. springdoc-openapi gets added per slice on the Spring Boot side.
- **No shared JWT validation library**. Phase 1 establishes its shape based on one real consumer.
- **No external OIDC / Keycloak**. HMAC JWT is enough for one developer / no live users.
- **No refresh tokens, no token revocation, no JWKS**. Long-lived token, env-secret, single signing key.
- **No schema decomposition**. Each slice decomposes its tables when it extracts; Phase 0 doesn't touch the DB.
- **No deletion of any Grails code**. Phase 0 is purely additive.
- **No Java upgrade for the Grails container**. Stays Java 8.

## 8. Per-slice template (Phases 1..12)

Every slice does these steps, in order:

| Step | What |
|---|---|
| 1 | **Identify scope.** List the Grails domain classes, services, controllers, GSPs, DB tables, and Liquibase changesets for this context. Also list external Grails callers of the context's domain classes (`grep` for `Foo.get`, `Foo.findBy*`, `foo.someColumn`). |
| 2 | **Create Spring Boot module** at `services/{context}-service/`. Spring Boot 3.x, Java 21, springdoc-openapi, jjwt 0.12+ for cookie validation. |
| 3 | **Port domain to JPA entities.** Rewrite Grails domain classes as Java JPA entities against the existing shared DB tables. Explicit `@Column`/`@Table` annotations match the existing schema so Hibernate 6's stricter naming doesn't conflict. Port GORM constraints to Bean Validation. |
| 4 | **Port services.** Rewrite Grails services as Java Spring `@Service`s. Same business rules. Cross-context reads against not-yet-extracted services stay as direct JDBC against the shared DB (note these as TODOs that will be revisited when that context's service exists). |
| 5 | **Port controllers.** Grails `*ApiController` → Spring `@RestController` annotated for springdoc-openapi. Generated OpenAPI spec is the slice's contract. |
| 6 | **Move table ownership.** Take the slice's Liquibase changesets out of `grails-app/migrations/` and into `services/{context}-service/src/main/resources/db/changelog/`. New service runs them at startup. |
| 7 | **Wire JWT validation.** Service validates `obx_token` cookie via shared HMAC secret. Phase 1 creates the validator code; Phase 2+ reuses it as a small shared library if it's grown beyond ~50 lines. |
| 8a | **Update React frontend.** Existing React code for this context calls the new URLs (`/api/{context}/...`). Any GSP-only screens for this context get React equivalents now. |
| 8b | **Handle external Grails callers.** For each call site outside the slice that uses `Foo.get(id)` or reads `foo.columns`: either migrate it to HTTP against the new service (preferred when caller count is small, e.g., the 7 callers of Document), or leave the Grails domain class alive until the caller migrates in its own later slice. Document this decision in the slice's commit message. |
| 9 | **Update nginx.** Add `location /api/{context}/ { proxy_pass http://{context}-service:80XX/; }` at the top of the location list. Remove the corresponding `/api/{context}/...` mapping from Grails `UrlMappings.groovy` if no callers remain. |
| 10 | **Delete Grails counterparts.** Conditional: delete only artifacts no remaining Grails code references. If a domain class still has Grails callers (per 8b), leave it; tag those callers with a `// TODO(migrate-to-{context}-service)` comment. If all callers are gone, delete the Grails domain class, service, controller, GSPs, and taglibs for the context. Grep verifies no remaining references. |
| 11 | **Tests.** Playwright E2E added for the new flow (happy path + 2-3 error paths). Existing Spock integration tests for the slice's logic ported to JUnit/Spock on the Spring Boot side, or deleted if obsolete. |
| 12 | **Soak.** Run the new state against realistic test data for the chosen interval. No regressions before next phase opens. |
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

The following load-bearing assumptions were verified against the codebase before this spec was committed. Evidence in `docs/specs/` history; key findings:

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
| A19 | No external dependency on generated GSPs | ❌ Surfaced design change | 8+ controllers `render(view: "/common/react")`. Resolution: defer frontend decoupling to Phase 13 (by then most of these controllers will already have been deleted) |
| A20 | Jobs / BootStrap don't depend on session auth | ✅ | All 13 jobs declare `def sessionRequired = false`; BootStrap.groovy has zero session/request references |

## 11. Known issues / accepted as out of scope

- **Java 8 EOL on Grails container.** Grails stays on Java 8 until Phase 13. Java 8 has been EOL upstream since 2022 (Oracle commercial support). Eclipse Temurin still provides Java 8 builds. Accepted because (a) no live users — security exposure is local-only; (b) Grails 3.3.16 + Groovy 2.4 fights Java 11+ in subtle ways; (c) the problem deletes itself in Phase 13.
- **Gradle 4.10.3 on Grails container.** Same logic — stays until Phase 13.
- **Liquibase `LiquibaseUtil` versioning lives until Grails dies.** New services use their own changelogs and runners; LiquibaseUtil keeps owning the Grails `0.X.x/` folders. The version-folder scheme retires when Grails does.
- **`SupportButton.jsx` calls HelpScout directly with raw axios.** Out of scope; third-party API; no auth required.
- **Webpack continues to write GSPs through Phase 12.** Cosmetic only — the GSPs are generated automatically; no manual maintenance.
- **No observability / metrics infrastructure called out.** Out of scope. Add when there's a real consumer; for a single-developer / no-live-users setup, Spring Boot Actuator on each service is enough.
- **No multi-tenant / external OIDC consideration.** Out of scope; HMAC JWT is fit-for-purpose. Replace with OIDC if/when multi-tenancy is real.
- **Slice phase ordering after Phase 2 is provisional.** Real ordering will be informed by Phase 1 and Phase 2 experience.

## 12. Risks

- **Phase 1 turns out to be larger than 1-2 weeks.** Document's 7+ callers are an estimate; some (e.g., StockMovementService's upload path) may be more entangled than they look. Mitigation: scope the work in detail before starting Phase 1; if it exceeds 3 weeks, reconsider whether PaymentTerm is a better Phase 1.
- **GORM-to-JPA mapping for inheritance (User extends Person) is non-trivial.** Phase 2 hits this first. Mitigation: budget extra time for the first JPA inheritance mapping; the pattern is reused for the few other inheritance cases in the domain.
- **Hibernate 5 / Hibernate 6 sharing a schema may surface naming-strategy gotchas.** Mitigation: explicit `@Column` and `@Table` on every JPA entity matching the existing column names.
- **External Grails callers may cascade.** Migrating a caller in Phase N to use the Phase 1 service may surface its OWN consumers; the migration tree could grow. Mitigation: when 8b reveals deep chains, accept "keep Grails counterpart alive" and migrate later; don't expand slice scope unboundedly.
- **One-developer pace.** 13 phases is a multi-year undertaking. Mitigation: each phase has independent value (each one shrinks Grails); pausing between phases is fine; nothing forces continuous work.
