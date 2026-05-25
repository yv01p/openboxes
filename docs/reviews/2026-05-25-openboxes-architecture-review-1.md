# Architecture Review: openboxes (Round 1)

**Repo:** /home/yv01p/openboxes
**Brief (verbatim):** "I want to modernize this application by moving away from grails. The new application should be java based using microservices based on spring boot and a react frontend. It seems some migration work has started."

## 1. Literal-wrongness findings

### 1.1 No backend migration has actually started; what exists is a React frontend embedded inside the unchanged Grails monolith

**Description.** The brief states "some migration work has started" and the target is "java based using microservices based on spring boot." Both halves of that premise are false against the codebase. There is no Java source code anywhere in the repository, no standalone Spring Boot service, no `pom.xml`, no Gradle subproject for any extracted module — only the original Grails 3.3.16 monolith plus a React SPA wedged into it. The Grails framework itself transitively packages Spring Boot 1.5.x (which is end-of-life), so a casual reader might read "we use Spring Boot already" — but that is not what the brief asks for.

**Evidence.**
- `find /home/yv01p/openboxes -name "*.java" -not -path "*/node_modules/*" -not -path "*/.gradle/*" -not -path "*/build/*"` returns zero files.
- `find /home/yv01p/openboxes -name "pom.xml" -not -path "*/node_modules/*"` returns zero files.
- `settings.gradle` (15 lines) defines no subprojects; only `rootProject.name = 'openboxes'` plus build-cache config.
- `gradle.properties:1` — `grailsVersion=3.3.16` (Grails 3 is end-of-life upstream; ships Spring Boot 1.5.22.RELEASE, also EOL).
- `build.gradle:104` — single `mainClassName = 'org.pih.warehouse.Application'` (the Grails app's entry point).
- `grails-app/` still contains 162 controllers (31,405 LOC), 150 services (41,110 LOC), 116 domain classes (13,589 LOC), 608 GSP views (77,284 LOC). Nothing has been removed.

**Proposed fix.** Recalibrate the project's mental model and roadmap before further work. The brief assumes momentum that does not exist on the backend. State explicitly: "Backend migration has not started. The React frontend is the only modernization in progress, and it currently runs as a tenant of the Grails app." Decide whether the first backend step is (a) extract one bounded context into a Spring Boot service behind an API gateway (strangler-fig), or (b) green-field a parallel Spring Boot system that data-syncs from Grails. Both are valid; the codebase currently picks neither (see §2.1).

### 1.2 The React frontend is structurally welded to the Grails app and is not a deployable artifact on its own

**Description.** The brief targets "a react frontend" as part of a microservices architecture, which implies a frontend that can be served and deployed independently of any one backend service. The current React app cannot. Its webpack build emits Groovy Server Pages directly into the Grails view tree; it cannot be deployed except as part of a Grails WAR; and at runtime it depends on `window.CONTEXT_PATH` injected by Grails, plus Grails session cookies for auth. When Grails is removed, the React app as currently built is removed with it.

**Evidence.**
- `webpack.config.js:7` — `const WEBPACK_OUTPUT = path.resolve(ROOT, 'main/webapp/webpack');` (output writes into the Grails WAR resource tree).
- `webpack.config.js:88` — `filename: ${COMMON_VIEW}/react.gsp` (HtmlWebpackPlugin generates `grails-app/views/common/react.gsp`).
- `webpack.config.js:109` — same plugin emits `grails-app/views/partialReceiving/create.gsp`.
- `src/js/api/urls.js:7` — `const { CONTEXT_PATH } = window;` and several URL builders prepend it (`LOCATION_TEMPLATE`, `INVENTORY_ITEM`, etc.). The variable is set in the generated GSP at render time.
- `src/js/utils/apiClient.jsx:15` — `const apiClient = axios.create({});` with no `Authorization` header logic; authentication rides on the Grails `JSESSIONID` cookie via `withCredentials` default browser behavior on same-origin requests.
- `src/js/utils/apiClient.jsx:34-36` — TODO comment in production code: "flattenRequest was specifically for the Grails 1. Temporary return unflattened data, but when rebase process will be finished clean up and remove this util" — confirms the React layer is in a known-incomplete state mid-flight.

**Proposed fix.** Treat the React app as a deployment-independent artifact: change webpack to emit a plain `index.html` + static bundle at a non-Grails path; replace `window.CONTEXT_PATH` with a build-time or runtime `API_BASE_URL` env (e.g., `process.env.REACT_APP_API_BASE`); replace cookie-implicit auth with an explicit token in `apiClient` interceptors. Make this decoupling a prerequisite for any backend extraction — until it is done, no Spring Boot service can ship to production usefully because the only React app in existence cannot be pointed at it without a Grails redeploy.

### 1.3 Roughly three-quarters of the UI is still Grails GSP, not React

**Description.** The brief's outcome ("react frontend," singular) implies React is *the* UI, not a partial overlay. Today the GSP view tree is ~3-5× larger than the React codebase and still owns the navigation shell, the bulk of forms, and most legacy modules. The brief's outcome requires porting 608 GSP files; the migration has touched the easy parts (cycle count, invoices, purchase orders, stock movement, receiving) and left the rest.

**Evidence.**
- `grails-app/views/` — 608 `.gsp` files, ~77,284 lines.
- `grails-app/taglib/` — 23 Grails-specific taglibs containing presentation logic embedded in the GSPs (these have no React equivalent; each call site needs hand translation).
- `src/js/components/` directory tree (sampled) — React covers: cycle count, invoice (create/list), location chooser, locations-configuration, purchase order, put-away, receiving, replenishment, reporting, returns (inbound/outbound), stock-list, stock-movement (and its wizard variants), stock-transfer, products, productSupplier, user, dashboard. Notably absent or partial: admin screens, order processing, requisition, shipment workflow, person management, role/security admin, settings, reports beyond reorder/expiration history, mobile views.
- `grails-app/controllers/` — 162 controllers, of which only ~50 are `*ApiController.groovy`; the remaining ~110 are page-rendering controllers backing GSPs.

**Proposed fix.** Inventory the remaining GSP screens against the React port plan and either (a) commit to porting them all (~3-5× the work already done on the frontend) before retiring Grails, or (b) accept that retiring Grails requires accepting a feature-loss for screens no one has ported. Neither has been chosen in evidence; the brief should pick.

### 1.4 116 domain classes live in one shared MySQL schema with no enforced bounded-context boundaries — the data layer literally will not split

**Description.** "Microservices" is plural and load-bearing in the brief. Independently deployable services require independent data ownership (private schemas or private databases, contract-based reads/writes across boundaries). The OpenBoxes data layer is the opposite: every domain class lives in one GORM persistence unit against one MySQL/MariaDB instance, with pervasive cross-aggregate references and joins (Order ↔ OrderItem ↔ Shipment ↔ Inventory ↔ Product ↔ Location, plus the same for Requisition, Invoice, CycleCount). Splitting this without first decomposing the schema yields a distributed monolith (multiple Spring Boot services all hitting the same DB) — which is worse than the current state, not better.

**Evidence.**
- `grails-app/domain/` — 116 `.groovy` domain classes, single package tree under `org.pih.warehouse.*`.
- `grails-app/conf/application.yml:11` — single GORM configuration block; no multi-datasource declaration.
- `docker/docker-compose.yml` — single MariaDB 10 instance.
- `grails-app/services/` — 150 services, 41,110 LOC, freely calling `Order.get(...)`, `Inventory.findBy*(...)`, `executeQuery(...)` across what would become service boundaries (97 instances of raw SQL/HQL via `executeQuery` / `createSQLQuery` per the survey).
- `grails-app/conf/application.groovy:7` — `'*'(cascadeValidate: 'none')` is applied globally; the domain model assumes a single transactional unit.

**Proposed fix.** Before extracting any Spring Boot service, decide the data ownership model (see §2.3) and pick a first bounded context whose data can plausibly be isolated. The natural early candidates from the domain inventory are CycleCount (already partially React-owned, mostly self-contained reads against Product/Location/Inventory) and Invoice (read-mostly, integrates with external accounting). Migrating these requires either (a) replicating their read dependencies (Product, Location) into the new service via events, or (b) keeping shared-DB reads as a transitional crutch with a documented retirement date. Either way, this decision precedes the first extraction; it cannot be deferred.

### 1.5 No DTO / contract seam between domain model and HTTP — service extraction will break callers on every cut

**Description.** Implicit dependency of microservices migration: services need stable, versioned contracts so consumers (other services, the React app, GSP pages) don't shatter when one is rewritten. Today the API controllers return GORM domain objects (or maps assembled from them) directly; the React client has no schema definitions of what comes back, only URL strings. When a controller moves to a Spring Boot service the JSON shape will drift by accident, and there is no test or generator that catches it.

**Evidence.**
- `src/js/api/urls.js` — pure URL string constants; no request/response types.
- `src/js/api/services/` — directory exists but is a thin wrapper over URL constants (no schemas, no Zod validators except for forms).
- `package.json:119` — `"zod": "3.22.4"` is present but, per the survey, used only for `react-hook-form` form validation — not as an HTTP-contract gate.
- 50 `*ApiController.groovy` files — none use any contract-generation annotation (the survey found no Swagger/OpenAPI evidence).
- No `openapi.yaml`, `swagger.yaml`, or `springdoc` / `springfox` dependency in `build.gradle`.

**Proposed fix.** Before extracting the first Spring Boot service, define a contract artifact for it (OpenAPI is the conventional choice for REST). Generate the React client from the contract (e.g., `openapi-typescript-codegen`) so that an extraction that breaks the shape breaks the build. Optionally backfill OpenAPI specs for the existing Grails `*ApiController` endpoints; this is also the input artifact you need to know what each future service must implement.

### 1.6 Authentication is a Grails-session cookie validated by a single `SecurityInterceptor.matchAll()`; it does not survive crossing a service boundary

**Description.** Implicit dependency: every Spring Boot microservice has to authenticate incoming requests, and the React app has to send credentials a foreign service can validate. The current scheme is a server-rendered login (`auth/login` action) that establishes a `JSESSIONID` cookie, validated on every request by an in-process Grails interceptor that calls `AuthService` and consults `LocationStatus`. None of that translates to a request hitting `inventory-service.example.com` from a React app served at a different origin.

**Evidence.**
- `grails-app/controllers/.../SecurityInterceptor.groovy:18` — `matchAll().except(uri: '/static/**').except(controller: "errors").except(uri: "/info").except(uri: "/health")` — a hardcoded interceptor running in-process.
- `SecurityInterceptor.groovy:20` — `controllersWithLocationNotRequired = ['categoryApi', 'productApi', 'genericApi', 'api']` — auth coupling to a per-user "current location" concept that lives in the Grails session.
- `src/js/utils/apiClient.jsx:80-83` — on 401, opens a `LoginModal` rather than initiating an OAuth/OIDC flow — confirming session-cookie assumption.
- No Spring Security Core / Shiro / Keycloak / OIDC client dependency in `build.gradle` (the project uses Grails' own `AuthService`).

**Proposed fix.** Introduce a token-based auth mechanism (OIDC provider — Keycloak, Auth0, or similar — issuing JWTs) before the first Spring Boot service ships. Teach the Grails app to accept tokens in parallel with its existing session cookie (dual-mode for the migration window). Replace the React `apiClient`'s implicit cookie auth with an `Authorization: Bearer` header and a refresh flow. The "current location" concept needs to migrate to a token claim or a separate per-request header. This work is independent of and prerequisite to backend service extraction.

### 1.7 Liquibase changelog is structurally tied to the monolith's release version — concurrent schema evolution by independent services is unsupported

**Description.** Implicit dependency of microservices: each service owns and evolves its own schema on its own cadence. The current Liquibase setup pins schema evolution to the Grails app's release version via `LiquibaseUtil.ALL_VERSIONS`, `CLEAN_INSTALL_VERSION`, and a single entrypoint `changelog.groovy` that includes per-release folders (`0.5.x/`, `0.6.x/`, …, `0.9.x/`). Two services concurrently modifying schema corrupt this sequence; the model assumes one app owns the whole DB.

**Evidence.**
- `grails-app/migrations/` — folder layout: `0.5.x/`, `0.6.x/`, `0.7.x/`, `0.8.x/`, `0.9.x/`, `install/`, `views/`, `extensions/`, plus `changelog.groovy`.
- `grails-app/migrations/changelog.groovy:32-49` — `currentVersion = LiquibaseUtil.getCurrentVersion()`; flow includes `install/changelog.xml` only on fresh install, then walks per-release folders.
- `grails-app/conf/application.yml:42-44` — automatic migrations disabled in favor of `BootStrap.groovy` running them at app start.
- `grails-app/migrations/views/drop-all-views.xml` — every migration run drops all SQL views first; assumes single owner of the schema.

**Proposed fix.** Either (a) keep a single shared schema and a single migration owner (the legacy Grails app or a dedicated "schema authority" service) for the duration of the migration, with new services treating the schema as read/write via well-defined access — accepting the distributed-monolith cost as transitional; or (b) decompose changelogs along the chosen service boundaries before the first extraction, accept owning one Liquibase project per service, and break the `LiquibaseUtil` versioning chain. The brief implies (b) is the eventual end state; (a) may be the safer transitional step. The decision needs to be made before the first Spring Boot service touches the database.

## 2. Forced decisions

### 2.1 Migration strategy: strangler-fig vs. parallel rewrite vs. hybrid

**The choice.** At ~163k lines of server-side Groovy plus ~77k lines of GSP plus 280 Liquibase changesets against a single shared MySQL schema, this is too large to flag-day-rewrite in any plausible business timeline. Three patterns are viable; the codebase picks none.

**Why it's forced.** The first concrete extraction (whichever bounded context you pick) has to fit into one of these patterns from day one — it determines whether you need an API gateway in front of Grails (strangler-fig), a green-field schema and a sync pipeline (parallel rewrite), or a way to deploy a Spring Boot service that the existing Grails app calls *out to* for new functionality (hybrid). Each implies different infrastructure that has to exist before extraction-one ships.

**Options.**
- (a) **Strangler-fig per bounded context.** Stand up an API gateway in front of the Grails monolith. Pick one bounded context (CycleCount and Invoice are the strongest first candidates, per §1.4), build a Spring Boot service for it, route gateway traffic away from Grails to the new service for that context's endpoints. Repeat. Slow but de-risked; each extraction is independently reversible.
- (b) **Parallel rewrite.** Build a green-field Spring Boot system alongside Grails. Dual-write or replicate data from Grails. Cut over per facility once feature parity is reached on a slice. Faster per-slice, but the dual-write/sync layer is a large project of its own and consistency bugs span both systems for the duration.
- (c) **Hybrid: greenfield-only services.** Leave Grails running for everything it already does. New functionality goes only into new Spring Boot services. Grails dies down by attrition over years. Smallest initial investment, slowest end-to-end, may never actually decommission Grails.

### 2.2 Sequencing: frontend-first vs. backend-first vs. vertical slices

**The choice.** The current trajectory (React added without backend extraction) is implicitly frontend-first, but it's not clear that was a chosen strategy versus inertia — the brief should make it explicit because the next phase of work depends on it.

**Why it's forced.** "Finish the React port, then extract Spring Boot services behind it" and "extract Spring Boot services first, then port the GSP UI to React against the new services" have radically different intermediate states. The first leaves you with a 100% React frontend talking to a still-Groovy backend for years; the second leaves you with Spring Boot services serving both new React pages and existing GSPs (via internal HTTP) for years. The codebase does not pick.

**Options.**
- (a) **Frontend-first.** Port the remaining ~75-85% of GSPs to React against the existing Grails APIs. Once the React frontend is complete, start backend extraction. Pro: end users see a unified UI sooner; backend extractions don't break the UX layer. Con: you're investing further in coupling React to Grails endpoints that will be retired.
- (b) **Backend-first.** Freeze the React port; extract Spring Boot services that serve both the existing GSPs (via HTTP from Grails) and the React app. Once core domains are extracted, resume porting GSPs to React against the new services. Pro: stops further coupling; gets to the architectural target state. Con: in-flight GSPs gain HTTP latency they didn't have before; React port pauses indefinitely.
- (c) **Vertical slices.** Pick one bounded context (e.g., CycleCount) and migrate its backend and frontend together to the target stack. Repeat per context. Pro: each slice reaches end state. Con: requires the React app to know which contexts have migrated and which haven't, plus interim coexistence with the Grails layout/navigation shell.

### 2.3 Data ownership during transition

**The choice.** The first Spring Boot service has to read `Product`, `Location`, and `Organization` — these are reference data referenced by nearly every domain object. Where do those reads come from?

**Why it's forced.** Three concrete options exist; each has consequences that span the entire migration; none is reversible cheaply once chosen.

**Options.**
- (a) **Private schema per service; cross-service reads via API.** Each service owns its data fully. Cross-service reads happen via HTTP/gRPC calls to the owner. Implies an API gateway, service discovery, and accepts inter-service latency on every read. Reference data (Product, Location) probably becomes a "platform" service everyone calls.
- (b) **Shared DB during transition; separate schemas as stretch goal.** Spring Boot services read/write directly against the shared MariaDB. Fast to ship the first service. Becomes a distributed monolith; backing out is painful; concurrent schema evolution conflicts (see §1.7).
- (c) **Event-driven replication.** A change-data-capture or domain-event pipeline replicates reference data (Product, Location, Organization, User) into each service's private store. Eventual-consistency reads on the consumer side. Highest upfront infrastructure investment, cleanest end state.

### 2.4 Authentication during the coexistence window

**The choice.** From the first Spring Boot service ship-date until the Grails monolith is decommissioned (likely years), the React app has to authenticate against both. The codebase does not have a token-based auth scheme; introducing one is itself a project.

**Why it's forced.** A Spring Boot service cannot validate a Grails `JSESSIONID` cookie without sharing the Grails session store or re-implementing `AuthService`. The React frontend has to make calls to both backends without re-prompting the user. The user has to be authenticated once and authorized everywhere.

**Options.**
- (a) **Introduce an OIDC provider (e.g., Keycloak) and teach Grails to accept JWTs in parallel with its existing session cookie.** New services validate JWTs natively. React replaces `JSESSIONID`-implicit auth with `Authorization: Bearer` for all calls (Grails and new services). Largest upfront work; cleanest end state.
- (b) **Put a reverse proxy / API gateway in front that translates between session and token.** Browser keeps the Grails session cookie; gateway swaps it for a JWT when proxying to new Spring Boot services. Hides the auth scheme change from the React app and from new services. Adds a critical-path component.
- (c) **Share the session store.** Move Grails session storage to a shared backend (e.g., Redis); have Spring Boot services read and validate the same session token. Minimal change to React; ties new services to Grails's session model (which the brief is trying to retire) for the duration.

## 3. Recommendation

🛑 **Surface forced decisions to user.** §1 identifies seven literal-wrongness gaps against the brief — collectively, they say the migration has not actually begun on the backend and the React frontend that has been built cannot be lifted off Grails as-is. §2 identifies four forced decisions (strategy, sequencing, data ownership, auth) that have to be made before the first concrete migration step can be taken. Make those four decisions first; the §1 findings then translate into a concrete prerequisite work list (decouple webpack from GSP, introduce OIDC, define the first OpenAPI contract, plan schema decomposition for the first bounded context) that can be sequenced against the chosen strategy.
