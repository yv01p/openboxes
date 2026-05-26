---
date: 2026-05-26
phase: 1 (Document slice)
tag: phase-1-document
commit_range: d079b4f..a7af228
plan: docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md
spec_section: §8 (Phase 1)
---

# Phase 1 Document Slice — Retrospective

## TL;DR

Phase 1 shipped a working strangler-fig: document-service is the authoritative HTTP backend for `Document` + `DocumentType` CRUD, all ~20 Grails callers route to it through `DocumentClient`, the obx_token JWT cookie is now validated by both Java filters and Grails interceptors, and nginx fronts both stacks with `/api/documents/` routing. 39 commits landed (tagged `phase-1-document`). The codebase exits Phase 1 in a deliberate **hybrid state** — the Grails `Document.groovy` / `DocumentType.groovy` / `DocumentController.groovy` / `views/document/` files are still on disk because Task 10's "delete the Grails counterparts" step was deferred to Phase X. That deferral was the session's key architectural call: the migration-time recon found the consumer surface is 2-3x what the spec enumerated, the deliberate `Document.load(id)` bridge pattern at 7 sites means the migration is not "clean" in the way the plan's `Task 10` guardrail required, and 6 architectural questions (join-table ownership, `findByParent` API surface, template-rendering controller fate, the supplier-organization Criteria query, top-nav menu, DocumentType ownership) need a focused brainstorming pass before deletion is safe. The hybrid stop-point is the conventional strangler-fig terminus. SDD-per-task + two-stage review held up cleanly across 11 tasks; deferred-followups table closed 8 items in Task 11 hardening; 36 rows remain in the backlog as Phase X / Phase 2+ / hygiene.

## What worked

- **SDD-per-task structure** held across 11 dispatched tasks. Each implementer subagent received a self-contained spec, a verbatim plan excerpt, and dispatch-time corrections. Two-stage review (spec-compliance reviewer + code-quality reviewer) caught real issues at every checkpoint without slowing throughput.
- **Strangler-fig stopping at hybrid state** was the right call. The plan called Task 10 "Conditional — only if Task 8b migration is clean"; pre-dispatch recon confirmed the migration is not clean (7 deliberate `Document.load(id)` bridges feeding Grails-managed join-table mutators). Phase X deferral preserved Phase 1's done-gate without forcing premature decoupling under unanswered architectural questions.
- **JWT cookie sharing between Grails and Spring Boot** worked first try. The Phase 0 `obx_token` HS256 HMAC cookie issued by Grails `ApiController.login` validates cleanly in document-service via `JwtCookieAuthFilter`. Shared `OPENBOXES_JWT_SECRET` env wired in `docker/docker-compose-base.yml:18,37`. Spring Boot 3.3.5 + jjwt 0.12.6 against Grails 3 / jjwt 0.7.x interoperated without any signature-algorithm friction.
- **Deferred-followups table as single source of truth.** Reviewers wrote rows; implementers closed them. Task 11 hardening alone closed 8 of them (T2-M2, T2-M3, T7-M1, T8b-I3, T8b-I4, T8b-M3, T8b-I5, T9-M1) in 4 focused commits. The convention "fix-in-task if possible, otherwise file a row pointing at the right future task" kept reviews moving without leaving silent debt.
- **TestContainers BOM-override workaround** for the Docker API version mismatch (see gotcha (d)) is a known good pattern. Pinning `docker-java` versions in `services/document-service/build.gradle` survived BOM upgrades cleanly through the phase.
- **Two-stage CDR + CIR design loops** (carried over from Phase 0) again surfaced design-quality issues — the GET handler ambiguity (T5-I1, T5-I2) and the documentType-null bug (T5 CR fix) — before they reached the integration boundary.

## Codebase / env gotchas (Phase 2+ should know)

### Build & deploy

- **`services/document-service/Dockerfile` COPIES the JAR.** The Dockerfile does `COPY build/libs/document-service-*.jar app.jar`. If you run `sudo docker-compose up --build` without first running `cd services && ./gradlew :document-service:bootJar`, the build context contains the *stale* JAR from the last bootJar run (or empty `build/libs/`), and Docker layer-cache happily reuses it. Spec Task 13 Step 1 codifies the correct order: `bootJar` first, *then* `docker-compose up --build`.

- **document-service source is NOT compiled inside the container.** Source-on-host → bootJar-on-host → COPY into image. No volume mount, no in-container compile. Every Java edit needs the bootJar + rebuild cycle.

- **Grails app source NOT mounted into container either.** Every Groovy / GSP / `runtime.groovy` / interceptor / taglib edit requires the full cycle:
  ```bash
  ./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
  cd docker && sudo docker-compose up -d --build app
  ```
  (Phase 0 retrospective already flagged the absent `build:` directive; Task 2 of Phase 1 added one at `docker-compose-base.yml`, so `--build app` now picks up local changes. No more retag-as-`:latest` workaround.)

- **nginx caches Grails upstream IP across `app` container rebuilds.** Rebuilding just the `app` container assigns it a new internal IP; nginx (still running on its original IP cache) returns 502s until reloaded. Fix: `sudo docker exec openboxes-nginx nginx -s reload` after any `app`-only rebuild. The full `down && up -d` cycle in Task 13 Step 1 avoids the issue because all containers come up together against fresh IPs.

- **TestContainers requires sudo for the docker socket** — dev box user is not in the `docker` group. The blessed test command is `cd services && sudo -E ./gradlew :document-service:test` (`-E` preserves Gradle's wrapper config).

### Code-level

- **`format:metadata` taglib at `grails-app/taglib/org/pih/warehouse/FormatTagLib.groovy:212` silently degrades on Map inputs.** The taglib was written for Grails Document instances; after Task 8b's switch to `Map` (the `DocumentClient.fetchById` return type), `documentMap.properties` returned the *class* metaproperties (`["class": ..., "metaClass": ...]`) instead of map keys. Closed under T8b-I2 (commit `1bc868d`) by branching on `instanceof Map`. Symptom: GSP renders showed `class=class java.util.LinkedHashMap` in metadata blocks. Watch for this pattern anywhere a former domain-class arg is replaced by a Map.

- **TestContainers 1.19.x ships docker-java 3.3.6, which only speaks Docker API 1.32.** Docker Engine 29 on the dev box requires Docker API 1.44 minimum. Symptom: `unsupported API version` from MariaDBContainer.start(). Workaround: override `docker-java-api` / `docker-java-transport-zerodep` to 3.4.0 in `services/document-service/build.gradle`. Re-test on every TestContainers BOM bump.

- **`RestTemplateBuilder` is unavailable in Grails 3 Spring Boot.** Grails 3 ships Spring Boot 1.5 vintage; `RestTemplateBuilder.setConnectTimeout(Duration)` was added later. Use `SimpleClientHttpRequestFactory` directly and call `setConnectTimeout(int millis)` / `setReadTimeout(int millis)`. Pattern lives in `grails-app/services/org/pih/warehouse/core/DocumentClient.groovy` constructor after T8b-I4 landed (commit `ef2b069`).

- **`OncePerRequestFilter.logger` is the inherited Apache commons-logging `Log`** — no SLF4J `{}` placeholder support. String concatenation works; placeholders silently print the literal string. T11-M4 (still open) tracks switching `JwtCookieAuthFilter` to a private SLF4J `Logger` for consistency.

- **`Document.load(id)` is a deliberate bridge pattern, NOT dead code.** 7 production sites (ProductController:467,505,538; InvoiceController:168; ShipmentController:930; OrderController:561; DocumentUploadController:31; StockMovementService:3465) load a Hibernate proxy from the Grails domain class purely to feed the Grails-managed `parent.addToDocuments(doc)` / `removeFromDocuments(doc)` mutators. The proxy is never dereferenced (no field access). 6 of 7 carry `// TODO Phase 2+` markers; T8b-M5 tracks adding the missing marker on InvoiceController:168. These bridges must survive until Phase X redesigns join-table ownership.

### Container / runtime

- **document-service `port 8081` is `expose:` only, NOT `ports:`.** Reachable from inside the docker network and from inside the container, NOT from the host. The spec's `curl http://localhost:8081/actuator/health` line in Task 13 Step 1 actually never works from the host; the correct equivalent is `sudo docker exec openboxes-document-service wget -qO- http://localhost:8081/actuator/health`. Docker's `(healthy)` flag on the container is the reliable host-side indicator. This is the intended security posture — all external traffic goes through nginx `/api/documents/`.

- **doc-service shutdown emits HHH000478 ERROR lines during JUnit teardown.** When the test JVM shuts down before TestContainers can cleanly issue the schema-drop DDL, Hibernate logs `Unsuccessful: alter table if exists document drop foreign key ...`. Benign — the container is about to be killed anyway — but noisy. Don't grep `ERROR` in TestContainers shutdown logs without filtering these out.

## Process / meta-lessons

1. **Phase X deferral was the session's key call, and worked because the deferred-followups table absorbed the consequence.** When pre-dispatch recon for Task 10 found the consumer surface 2-3x larger than the plan's spec enumerated (spec listed 5 `Document.load(id)` bridge sites; actual was 7 production + 4 in DocumentController. Spec listed 3 `hasMany Document` domains; actual was 5 — also Order and Shipment. 15+ GSPs read `parent.documents` directly), the right move was to update the plan (`419a2e2`) to defer Task 10 to a new "Phase X: Document slice decoupling" section with the 6 unresolved architectural questions enumerated explicitly. The plan's `:1479-1499` block reads as future-Phase-X starting material. Critical lesson: **plans that under-count consumer surface should be allowed to admit it and defer cleanly; not every plan needs to be executed verbatim.**

2. **The caller-graph under-count was symptomatic.** Plan-time enumeration via grep can find `Document.load`, but the join-table mutator pattern (`parent.addToDocuments` / `removeFromDocuments`) is a Grails GORM-generated dynamic method that doesn't show up in source-level grep against `Document`. A Phase 2+ TWP / plan-time recon for any GORM-domain-removal should specifically grep for dynamic mutator names per the `hasMany` declarations, not just for the entity name. Same lesson for `getImages()` / `getOrderDocuments()` — domain-class-level convenience methods iterate the collection without naming `Document` in their source.

3. **The `Document.load(id)` bridge pattern is a stable strangler-fig technique** and worth naming. Pattern: foreign service owns the entity's persistence, but the parent domain class still has a `hasMany` association whose mutators expect a managed proxy of the foreign-owned entity. `EntityClass.load(id)` (Grails) / `entityManager.getReference(...)` (JPA) returns an unfetched proxy that satisfies the mutator's type signature without actually loading the row. The proxy is consumed-and-forgotten — never dereferenced. This lets the parent's GORM-managed join table accept references to rows that no longer live in the parent's repository's mental model. Phase X is the right point to dissolve it, by either moving join-table ownership to document-service or reversing the FK direction.

4. **Two-stage review per task is the right cost.** Spec-compliance review and code-quality review have non-overlapping failure modes. Spec-compliance catches "spec says X, implementation does Y" (e.g., Task 5 GET handler ambiguity flagged a route the spec named but the implementer split). Code-quality catches lifecycle / concurrency / error-handling bugs the spec was silent on (e.g., T8b-I3 RestTemplate socket leak; T8b-I4 missing timeouts; T8b-I5 upload NPE; T9-M1 nginx body size). Either alone would have missed a non-trivial fraction.

5. **Deferred-followups table needs aggressive pruning, not just appending.** 36 rows remain. Many are "Any time" hygiene that should be batched, not held individually. A Phase 2 / Phase X kickoff should triage the table: close anything pre-empted by the new phase, batch the hygiene rows into a single "phase-1 cleanup" sprint, and leave only the high-signal items as carried-forward.

## Forward to Phase 2 (identity-service)

- **The JWT-validation-on-Spring-Boot pattern is directly reusable.** `JwtCookieAuthFilter` (HS256 HMAC, shared `OPENBOXES_JWT_SECRET`, optional cookie, anonymous-passthrough for unauthenticated routes, role-claim → `SimpleGrantedAuthority` mapping) is the template. Phase 2 identity-service should formalize the role-claim format (currently dead code at `JwtCookieAuthFilter.java:47-50` per T7-M2; raw entity IDs like `R001`, NOT `ROLE_*` prefixed per T7-M3). Once identity-service owns issuance, Grails `JwtService.groovy:37` and the `OPENBOXES_JWT_SECRET` shared-secret pattern can be replaced with JWKS-based RS256 validation against identity-service.

- **`AuthController.login` is the natural extraction point.** Phase 2 should plan around moving `/openboxes/api/login` from Grails into identity-service, keeping the obx_token cookie shape stable so existing Grails interceptors and document-service filters keep working. The cookie shape is the public contract; the issuer is internal.

- **Consider a session-attached JWT renewal endpoint** before deleting Grails login. Browsers sit on long-lived sessions; current obx_token cookie has no renewal flow documented (Phase 0 / Phase 1 didn't need it for short test runs). Phase 2 should resolve the renewal model before flipping the issuer.

- **The 36 carried-forward backlog rows** (plan `:155-200`) are the Phase 2 / Phase X starting backlog. T7-M2, T7-M3, T7-M4, T11-M4 specifically target Phase 2; T6-M2 targets pre-production deploy.

## Phase X: Document slice decoupling (deferred)

Documented in detail at plan `:1479-1499`. Six architectural questions block dispatch:
1. Join-table ownership (`invoice_document`, `order_document`, `product_document`, `shipment_document`, `shipment_workflow_document`)
2. `findByParent` API surface on document-service if joins migrate
3. Template-rendering surface (28+ GSPs use `DocumentController.groovy` for Word/Excel/Zebra output)
4. `getAllDocumentsBySupplierOrganization()` Criteria query fate
5. Top-nav "Documents" menu (`conf/runtime.groovy:453`)
6. DocumentType domain ownership (Grails vs Java vs split)

Plan's preserved Task 10 spec at lines `:1279-1334` is starting material — do NOT take at face value; it under-counts. Re-recon required at Phase X dispatch time.

## Artifacts

- **Plan**: `docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md` (final state post-Task-13)
- **Design spec**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` (shared with Phase 0)
- **Audit doc**: `docs/audits/2026-05-26-phase-1-document-scope-audit.md` (Task 1 output)
- **Tag**: `phase-1-document` at `a7af228` (local; push pending)
- **Commit range** (Phase 1): `d079b4f..a7af228` (39 commits)
- **Phase 0 retrospective** (predecessor): `docs/retrospectives/2026-05-26-phase-0-foundations-retrospective.md`
- **Carried-forward backlog**: 36 rows in plan §"Deferred follow-ups" (`:155-200`)
- **Deferred phase**: Phase X (plan `:1479-1499`)
