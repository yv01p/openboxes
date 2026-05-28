---
date: 2026-05-28
phase: 3 (Location slice)
tag: phase-3-location
commit_range: 2e70b7c91..626b2ff3c
plan: docs/plans/2026-05-28-phase-3-location-service-implementation-plan.md
spec_section: §8 (Phase 3)
---

# Phase 3 Location Slice — Retrospective

## TL;DR

Phase 3 shipped location-service as the authoritative READ-ONLY HTTP backend for `Location` + `LocationGroup` + `LocationType`: 7 GET endpoints (`/api/location/{id}`, `/api/location`, `/api/location/supportedActivities`, `/api/location/group{,/id}`, `/api/location/type{,/id}`), backed by JPA entities with FK-only mappings, a `LocationTypeCache` (refresh-on-miss Map), and a `LocationFilterService` that excludes internal types (BIN/INTERNAL) by default while preserving ZONE as a real-location per Grails parity (FD#2 pick a). All Grails write paths stay on Grails — `/api/locations/*` (plural) still routes to Grails; the new `/api/location/*` (singular) routes to location-service via nginx exact-match + prefix rules. DTOs are flat FK-only (FD#3 pick c — no nested `LocationType` / `Organization` / `Address` inflation) departing from Grails inflated shape. 17 commits landed (3 spec/CDR + 1 plan + 2 CIR fixes + 11 SDD task commits, tagged `phase-3-location` at `626b2ff3c`). CI green at 11m50s. SDD per-task with **user-enforced stop-after-each-task gate** held cleanly across 12 dispatched tasks; two-stage review (spec-compliance + code-quality) surfaced 4 implementer hand-fixes (T2 Liquibase auto-config, T5 SecurityConfig 401-vs-403, T7 EAGER fetch for cached detached entity, T10 stale MeResponse field) + 1 done-gate hotfix (T12 nginx exact-match) that all turned out justified. 17 retro candidates accumulated + 4 spec/plan back-ports for Phase X / hygiene.

## What worked

- **Read-only pivot** (decided during brainstorming via F5 event-cascade discovery) was the right call. `Location.afterInsert/Update/Delete` fires `productAvailabilityService.updateProductAvailability()` + `inventorySnapshotService.updateInventorySnapshots()` inline; moving writes off Grails would have orphaned the cascade and produced silent inventory drift. Read-only deferred the cascade-decoupling problem to Phase X (post-Phase 6 inventory-service for `LocationChangedEvent` saga) without blocking Phase 3's read-side value.

- **Flat FK-only DTOs (FD#3 pick c)** avoided 6+ days of LazyInitializationException + cross-service `Organization` / `User` / `Address` fetching infrastructure. `LocationDto(id, name, locationTypeId, locationTypeCode, locationTypeName, locationGroupId, parentLocationId, zoneId, organizationId, managerId, addressId, active, sortOrder, latitude, longitude, identifier)` with FK IDs as scalars. T1 live-probe captured the Grails inflated shape (`{data: {locationType: {...}, organization: {...}, ...}}`) as regression baseline at `/tmp/grails-location-response.json`; T10 Playwright Test #3 asserts the Grails plural endpoint still returns `body.data` (no regression) while location-service returns flat. The cost: one explicit spec §15 behavioral departure that all 16 React caller sites + downstream code must eventually adapt to during Phase X consolidation.

- **SDD per-task + user-enforced stop-gate** (overriding SDD skill's "Continuous execution" default per `superpowers:using-superpowers` priority "User's explicit instructions — highest priority") held across T1-T13 without slowing throughput. Each implementer dispatch → spec-reviewer + quality-reviewer in parallel → controller synthesis → user gate question → user decision. Surfaced 5 nontrivial hand-fixes (T2/T5/T7/T10 implementer fixes + T12 done-gate hotfix) that all turned out correct. The stop-gate cost was small (single AskUserQuestion per task) and prevented the autopilot risk of compounding deviations across tasks.

- **JwtCookieAuthFilter + JwtService subset copy from identity-service** (T5) worked first try with package rename. Omitted `JwtService.issue()` (location-service issues no tokens). Implementer hand-fix added `.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))` line 26 that plan template omitted — without it, Spring Security 6 default `Http403ForbiddenEntryPoint` would have returned 403 not 401, failing CIR R1#4 done-state. Implementer cross-referenced `services/identity-service/src/main/java/org/openboxes/identity/security/SecurityConfig.java:29` to find the missing line. Pattern of "follow plan verbatim except cross-reference Phase N-1 working code for framework-default-dependent claims" became the session's default discipline.

- **Liquibase shadow changelogs with `tableExists` precondition** (T3) ran cleanly on first boot (MARK_RAN for all 5 tables since Grails Liquibase already created them). CIR R1 deliberately chose `tableExists` over Phase 2's `columnExists` per-column pattern — works uniformly for entity + M:N join tables (`location_supported_activities`, `location_type_supported_activities`). Zero changeset checksum churn this phase (vs Phase 2's BP-2 `UPDATE DATABASECHANGELOG SET MD5SUM = NULL` recovery).

- **TestContainers JUnit suite (15 tests)** caught nothing in committed code but exercised every controller path + the internal-filter logic + the cache lifecycle. CIR R2 fix (`spring.jpa.defer-datasource-initialization=true` in `@DynamicPropertySource`) prevented the "Table doesn't exist" cascade that would have failed all 15 tests at `ApplicationContext` init. Phase 2 `IdentityServiceIntegrationTest.java:79-84` precedent + the CIR R2 catch combined to make the test suite work on the first run.

- **Two-stage CDR + CIR + SDD-per-task** held across 12 SDD-dispatched tasks (T1 audit + T2-T12 implementation/verification; T13 retro doc-only). CDR R1+R2 + CIR R1+R2 caught structural design + plan bugs before code touched the repo. Quality-reviewer "Critical" findings on T6 cache patterns (thundering-herd in `getById()` refresh-on-miss + unused `ReentrantReadWriteLock`) and T9 test order dependency were correctly deferred as spec-faithful (Phase 2 `RoleTypeCache` parity) — same finding-class as Phase 1's "spec said X, implementation does Y" patterns. The reviewer-finds + controller-defers + user-confirms loop matched Phase 1+2 cadence.

## Codebase / env gotchas (Phase 4+ should know)

### Build & deploy

- **Spring Boot `LiquibaseAutoConfiguration` auto-activates on classpath presence regardless of YAML config.** If `liquibase-core` is on the classpath (which it is via `services/location-service/build.gradle`), Spring Boot tries to bootstrap Liquibase even if the YAML has no `spring.liquibase:` block. Default master path is `db.changelog-master.yaml`. T2 hit this immediately — bare service couldn't boot without a master. Resolution: T2 set `spring.liquibase.enabled: false`; T3 flipped it back to `true` + added `change-log: classpath:db/changelog/db.changelog-master.xml` after creating the 5 shadow changelogs. Future-phase services should set `enabled: false` in their initial bootstrap commit, flip to `true` in the next commit that adds master.xml.

- **docker-compose 1.29.2 `KeyError: 'ContainerConfig'` on `up --build` after every container rebuild** (carried forward from Phase 2). Workaround applied 5 times this session: `sudo docker-compose stop <service> && sudo docker-compose rm -f <service> && sudo docker-compose up -d --build <service>`. `docker-compose down -v && up -d --build` (full-stack rebuild used in T12 done-gate) avoids the bug because containers are destroyed first. CI uses `docker compose` (v2) and doesn't hit it.

### Code-level

- **Spring Security 6 default unauthenticated response is 403, NOT 401.** With `anyRequest().authenticated()` + no JWT cookie, the default `AuthenticationEntryPoint` is `Http403ForbiddenEntryPoint`. To return 401 you MUST wire `.exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))`. The plan T5 template + CIR R1#4 "Proposed fix" (which only updated verification text, not the SecurityConfig code that produces it) both omitted this. T5 implementer found by cross-referencing identity-service `SecurityConfig.java:29`. Phase 4+ security wiring should copy this line verbatim from Phase 2 or Phase 3 identity/location SecurityConfig.

- **`spring.jpa.defer-datasource-initialization=true` is REQUIRED with `spring.sql.init.*` + `ddl-auto: create`** in test contexts. Default ordering runs `spring.sql.init.*` BEFORE Hibernate `ddl-auto: create` → seed.sql INSERTs hit non-existent tables → `ApplicationContext` init fails → all tests fail. CIR R1#3 added `spring.sql.init.*` properties; CIR R2 caught that `defer-datasource-initialization=true` was missing in the same block. Phase 2 `IdentityServiceIntegrationTest.java:79-84` has the working precedent. Future-phase test classes using TestContainers + JPA must include all three properties.

- **Caching JPA entities with `@ElementCollection(fetch=LAZY)` (Hibernate default) triggers LazyInitializationException on access via detached entities.** `LocationTypeCache.refresh()` loads via `repo.findAll()` inside `@PostConstruct`, then the entities are detached after method exit. T7 controllers call `LocationTypeDto.from(lt)` → access `lt.getSupportedActivities()` on detached entity → throws. Spring's `OpenEntityManagerInViewInterceptor` doesn't help — cache miss runs outside request scope. T7 implementer hand-fix added `fetch = FetchType.EAGER` to `LocationType.@ElementCollection supportedActivities`. Acceptable cost (~50 rows total: 10-15 LocationTypes × 3-5 activities). Alternative for Phase X cleanup: cache DTOs instead of entities (cleaner separation, eliminates LAZY/EAGER concerns).

- **Hibernate `ddl-auto: validate` with ZERO `@Entity` classes is safe** (vacuous validation passes). T2 verified this — bare service booted at port 8083 with `ddl-auto: validate` + no entities yet. Phase 4 bootstrap can mirror.

- **`ddl-auto: validate` is forgiving of UNDECLARED columns in the schema.** location-service declares 13 of `location` table's 22 columns; the 9 undeclared columns (`version`, `date_created`, `last_updated`, `logo_url`, `logo`, `managed_locally`, `bg_color`, `fg_color`, etc.) are silently ignored. Validation only fails when DECLARED columns are missing from schema. Safe pattern for read-only slices that only need a subset of columns.

### nginx routing

- **nginx prefix `location /api/X/` does NOT match the bare path `/api/X`** (no trailing slash, no segments). The route `/api/location/` matches `/api/location/{id}` ✅ but NOT `/api/location?type=DEPOT` (path is `/api/location` without trailing slash). Without a complementary exact-match rule, the bare-path LIST endpoint falls through to the `/api/` catch-all → goes to Grails → returns 301. T8 quality-reviewer flagged this as "Minor"; I dismissed it; T12 done-gate smoke-test caught it. Fix: pair `location = /api/X { ... }` (exact match) with `location /api/X/ { ... }` (prefix). The exact-match doesn't collide with sibling `/api/Xs/` (plural) because exact-match only matches exact equality. See `docker/nginx/conf.d/app.conf:25-37` for the working pattern. Phase 4 services with list endpoints accessed via query-string-only must include both rules.

- **`location /api/location/` prefix correctly avoids collision with `/api/locations/` (plural)** even though the prefix string `/api/location` is a substring of `/api/locations`. nginx longest-matching-prefix evaluates the trailing slash as part of the rule — `/api/location/` only matches paths starting with `/api/location/` (i.e., `/api/location/{anything}`), NOT `/api/locations/{x}` (which would need `/api/locations/` as the rule). Per nginx longest-prefix-wins, the exact-match `=` modifier takes absolute precedence even over longer prefixes; prefix order in the config file doesn't matter for `=` rules.

### Container / runtime

- **location-service `port 8083` is `expose:` only, NOT `ports:`.** Reachable from inside the docker network + inside the container, NOT from the host. Host-side `curl http://localhost:8083/actuator/health` fails; correct equivalent is `sudo docker exec openboxes-location-service curl -sf localhost:8083/actuator/health`. Intended security posture — all external traffic goes through nginx `/api/location/{,/...}`. Mirrors document-service :8081 + identity-service :8082 patterns exactly.

- **nginx `depends_on: location-service condition: service_healthy`** adds ~30-60s to stack startup (location-service start_period + initial healthcheck cycles) but produces a cleaner failure mode (nginx never routes to an unhealthy backend). T8 + T12 done-gate rebuild both verified the chain works. CI runner cold start: ~15-20s for location-service to be healthy, total stack-up ~60-90s.

## Process / meta-lessons

1. **TWP should verify code-block claims against Phase N-1 precedent files, not paraphrase from spec text.** This session surfaced 3 plan-template gaps where the plan paraphrased instead of copying verbatim:
   - **T5 SecurityConfig** missing `.exceptionHandling(...HttpStatusEntryPoint(UNAUTHORIZED))` — Spring Security 6 default is 403 (RC-1)
   - **T9 `@DynamicPropertySource`** missing `spring.jpa.defer-datasource-initialization=true` — required with `sql.init.*` + `ddl-auto: create` (caught by CIR R2)
   - **T10 Playwright** referenced non-existent `me.locationId` instead of `me.location?.id` per actual `MeResponse(user, location, roleIds)` record (RC-16)

   CIR catches what it asks about; if it doesn't verify framework defaults or DTO field paths against precedent, gaps slip through to implementer time. A future `verify-plan-against-precedent` sub-skill for TWP would prevent this class of error — grep working precedent (Phase 2 identity-service files; Phase 1 document-service files) for relevant patterns (security config, @DynamicPropertySource, controller signatures, DTO field shapes, nginx config) and include verbatim blocks in the plan, not paraphrase.

2. **CIR R1 + UIP + CIR R2 partial-fix gap pattern repeated this session** (also seen in Phase 3 CDR R1+R2). CIR R1#3 fixed the @Sql annotation issue by switching to `spring.sql.init.*`; UIP applied the fix; CIR R2 then caught that the related `defer-datasource-initialization` property was missing — UIP inherited R1's omission of the parallel update. Pattern: **fixes that span multiple coordinated edits in different files are vulnerable to partial-fix gaps; always do a confirmation pass (R2) after UIP**. This session ran CIR R1 → UIP → CIR R2 → manual 1-line fix → SDD; the R2 confirmation caught what R1 missed.

3. **Don't dismiss "Minor" reviewer findings without verifying impact.** T8 code-quality-reviewer flagged the nginx bare-path issue as Minor; I judged it as edge-case. T12 done-gate Step 4 smoke-test caught it as a real-bug-affecting-LIST-endpoint. Lesson: when a reviewer surfaces a routing/contract concern even at low severity, run an actual probe against the boundary before dismissing. Adds ~30s of validation per Minor finding; would have caught the gap 4 tasks earlier.

4. **Per-task stop-gate (user override of SDD continuous-execution default) is high-leverage.** 12 stops × ~30s each = 6 minutes of "controller surfaces finding, user picks disposition" overhead, in exchange for 4 hand-fix decisions + 1 hotfix decision + 1 done-gate scope decision being made deliberately rather than autopilot-rolled. Hit rate: 5/12 stops produced a finding worth a real decision; 7/12 were "Continue" rubber-stamps. The 5 substantive decisions all changed code paths (T2 application.yml, T5 exceptionHandling, T7 EAGER, T10 MeResponse field, T12 nginx hotfix). Worth the cost; should be the default for SDD execution against unfamiliar precedent territory.

5. **Reviewer-finding-deferral table (17 RC items) needs aggressive triage at Phase 4 kickoff.** Many RC items are Phase 2-inherited cache patterns (thundering-herd, unused lock) that affect BOTH identity-service and location-service identically; a single Phase X cache-cleanup task can batch RC-3, RC-6, T9 timing, T10 BASE_URL pattern, T10 cookie-pattern divergence. Don't carry as individual rows.

6. **`down -v` in T12 done-gate is the right discipline** despite wiping the DB. Verifies the full Liquibase bootstrap path works from cold start; catches "this worked because the existing schema happened to match" false-positives. Took ~5 min for the full rebuild + ~3 min for Grails Liquibase + ~30s for location-service healthcheck. Cheap insurance.

## Forward to Phase 4 (next slice)

The next slice's identity is open — parent design § enumerates candidates (Organization slice, Inventory slice, Product slice). Phase X items 1-6 (write decoupling, plural→singular consolidation, identity-service `Location.java` removal, bin/zone admin UI) require Phase 6+ inventory-service for the `LocationChangedEvent` saga, so they cannot ship as a Phase 4 follow-on.

- **JwtCookieAuthFilter + JwtService subset pattern is now triplicated** (document/identity/location). Phase 4 service that needs JWT validation should copy the same pattern — but the third copy strongly motivates extracting to a `services/jwt-auth-common/` shared library before Phase 5 (RC-RCX equivalent across all services).

- **FK-only flat DTO pattern (FD#3 pick c)** is the new norm for read-only slices. Phase 4 slices that READ existing entities should mirror — record-type DTOs with FK IDs as scalars + `from(entity)` static factory with defensive null-guards (see `LocationDto.from():32-43`).

- **17 RC items + 4 spec/plan back-ports** (listed below) are the Phase 4+ starting backlog. Many overlap with Phase 2 backlog (cache pattern cleanups, JWT shared library); a single Phase X hygiene sprint can batch.

## Retrospective candidates (RC-1 through RC-17)

Surfaced this session by implementer hand-fixes, spec-reviewer findings, quality-reviewer findings, and the T12 done-gate hotfix. Numbered for back-reference from future TWPs.

| # | Severity | Description | Disposition |
|---|----------|-------------|-------------|
| RC-1 | Important | Plan T5 SecurityConfig template omitted `.exceptionHandling(...HttpStatusEntryPoint(UNAUTHORIZED))`; Spring Security 6 default = 403 not 401 | Hand-fixed at `services/location-service/src/main/java/org/openboxes/location/security/SecurityConfig.java:26`; back-port to TWP template |
| RC-2 | Important | Spring Boot `LiquibaseAutoConfiguration` auto-activates on classpath presence regardless of YAML | T2 + T3 worked around; Phase 4 services should set `enabled: false` from bootstrap, flip true when master.xml lands |
| RC-3 | Carry-forward | `LocationTypeCache` + `RoleTypeCache` both have thundering-herd race in refresh-on-miss path | Phase X cache cleanup across both services |
| RC-4 | Carry-forward | docker-compose v1.29.2 KeyError 'ContainerConfig' bug hits every container rebuild | Workaround documented; upgrade to compose v2 in dev box is the proper fix |
| RC-5 | Carry-forward | Entity-cache + `@ElementCollection` LAZY trap; T7 chose EAGER fix; alternative is cache DTOs not entities | Phase X cache cleanup; evaluate DTO-cache when caching more entities with collections |
| RC-6 | Carry-forward | `LocationTypeCache.getAll()` has no refresh-on-empty guard (only `getById()` does); test workaround masks the gap | Phase X cache cleanup; mirror getById's refresh-on-miss pattern |
| RC-7 | Carry-forward | Unbounded `findAll()` in `LocationGroupController.list()` + `LocationTypeController.list()` | Pagination concerns deferred until tables grow beyond ~100 rows |
| RC-8 | Carry-forward | `LocationTypeCode.valueOf(type)` throws `IllegalArgumentException` → 500 (should be 400) on invalid input | Phase X API-wide error mapping (Phase 2 BP-11 same pattern) |
| RC-9 | Minor | JPQL `:param IS NULL OR ...` doesn't handle empty-string inputs; `LocationController.list()` vulnerable | Normalize empty-to-null before repo calls; back-port to TWP template |
| RC-10 | Minor | Empty 404 response bodies (`ResponseEntity.notFound().build()`) — matches Phase 2 UserLookupController | Phase X API-wide error body schema |
| RC-11 | Minor | Trailing-slash convention inconsistent across nginx blocks (identity/documents no slash, location/) | Phase X nginx config rationalization |
| RC-12 | Minor | nginx blocks lack comments explaining URI-rewriting behavior of `proxy_pass` with/without URI component | Add one-line comments per block during Phase X cleanup |
| RC-13 | Carry-forward | `LocationTypeCache.getAll()` refresh-on-empty gap (same as RC-6; restated from T9 reviewer angle) | Merge with RC-6 |
| RC-14 | Carry-forward | Test order dependency in `cacheLoadsOnFirstCall_thenHits` (caused by RC-6 root cause + manual `cache.refresh()` workaround) | Resolves when RC-6 fixed |
| RC-15 | Minor | JWT test-helper duplicates crypto logic; document why location-service test mints tokens inline (no `JwtService.issue()`) | Add comment to test file |
| RC-16 | Important | Plan T10 paraphrased `MeResponse` shape (`me.locationId` instead of `me.location?.id`) | Hand-fixed at `e2e/tests/location-service.spec.ts:21,42`; back-port to TWP template |
| RC-17 | Carry-forward | Cookie-handling pattern divergence in `e2e/tests/` (15 tests auto-propagate; T10 uses manual extraction) | Phase X E2E pattern unification |

## Plan / spec back-ports (4 items for parent design / spec corrections)

- **LocationStatus enumeration** in parent design `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` §4.3 line 43 doesn't exist as a Grails domain class (only the `LocationStatus.groovy` enum at `src/main/groovy/...:3-6` exists). Back-port: update parent design to enumerate only the 3 real entities (Location, LocationGroup, LocationType).
- **ActivityCode count off-by-one** in spec §6 (says 31; actual is 30 per `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy`). Back-port: update spec §6 count.
- **nginx `proxy_pass` trailing-slash** in spec §8 line 170 still shows the buggy `http://location-service:8083/` (with trailing slash, would strip the path). The plan + implementation correctly omit it per CIR R1#1 fix. Back-port: update spec §8 to remove trailing slash. T8 spec-reviewer rediscovered this — known item.
- **nginx exact-match block for bare LIST path** (`location = /api/location`). Neither spec §8 nor plan T8 included this. T12 done-gate hotfix added it (commit `626b2ff3c`). Back-port: update spec §8 + plan T8 template to include both exact-match + prefix blocks for future-phase services with query-string-only LIST endpoints.

## Phase X carry-forward (deferred until post-Phase 6)

Inherited from spec §14:

1. **Inventory snapshot saga (Phase 6 prerequisite).** location-service emits `LocationChangedEvent` to outbox; inventory-service consumes + runs `productAvailabilityService.updateProductAvailability(...)` + `inventorySnapshotService.updateInventorySnapshots(...)`. Replaces the in-JVM `Location.afterInsert/Update/Delete` cascade.
2. **Shipping-workflow internal-location creation (Phase 8 prerequisite).** `LocationService.findOrCreateInternalLocation` migrates to shipping-service + sync HTTP call to location-service POST endpoint.
3. **CSV bulk import migration.** `LocationImportDataService` moves to location-service `POST /api/location/importCsv` (or stays Grails until Phase 12).
4. **Bin/zone configuration UI rewrite.** 6 React modals + 2 controllers (`AddBinModal`, `AddZoneModal`, `AddLocationGroupModal`, `ImportBinModal`, `LocationDetails`, `ZoneAndBinLocations`); rewrite as React + location-service POST endpoints OR delete in Phase 12 cleanup.
5. **`/api/locations/*` (plural) → `/api/location/*` (singular) consolidation.** Migrate 16 React files + ~200 Grails `Location.get(id)` callsites; delete Grails LocationApiController/LocationController/LocationGroupApiController/LocationService/Location.groovy/LocationGroup.groovy/LocationType.groovy + views + URL mappings.
6. **identity-service `Location.java` entity removal.** Once location-service has POST endpoints and identity-service can HTTP-call location-service for chooseLocation's `location.active` check.

## Artifacts

- **Plan**: `docs/plans/2026-05-28-phase-3-location-service-implementation-plan.md` (1632 lines; CIR R1+R2-clean; post-T13)
- **Design spec**: `docs/specs/2026-05-27-phase-3-location-service-design.md` (355 lines; CDR R1+R2-clean)
- **Parent migration design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` (shared with Phases 0-12)
- **Tag**: `phase-3-location` at `626b2ff3c` (CI-green; local + remote)
- **Commit range** (Phase 3): `2e70b7c91..626b2ff3c` (17 commits: 3 spec/CDR + 1 plan + 2 CIR fixes + 11 SDD tasks)
- **Critical reviews** (gitignored): 4 files in `docs/criticalreviews/` (CDR R1, CDR R2, CIR R1, CIR R2)
- **Live-probe baseline** (T1 audit; regression fixture): `/tmp/grails-location-response.json` + `-2.json`
- **CI run** (T12 done-gate green): GitHub Actions run ID `26594784047` (11m50s; success at HEAD `626b2ff3c`)
- **Phase 2 retrospective** (predecessor): `docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`
- **Phase 1 retrospective** (predecessor): `docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`
- **Handoff docs** (session continuity): `handoffs/2026-05-28_15-29-52_phase-3-cir-r1-next-uip.md` (entry) + `handoffs/2026-05-28_17-05-56_phase-3-sdd-t6-done-t7-next.md` (mid-session)
- **Carried-forward backlog**: 17 RC items + 4 plan/spec back-ports above
- **Deferred phase**: Phase X (spec §14) — post-Phase 6 once inventory-service exists
