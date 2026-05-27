---
date: 2026-05-27
phase: 2 (Identity slice)
tag: phase-2-identity
commit_range: 7f86af15c..cc747188a
plan: docs/plans/2026-05-26-phase-2-identity-service-implementation-plan.md
spec_section: §8 (Phase 2)
---

# Phase 2 Identity Slice — Retrospective

## TL;DR

Phase 2 shipped identity-service as the authoritative HTTP backend for authentication + user/role lifecycle: login, logout, chooseLocation, password change (self-service + admin), forgot-password/reset-password flow, signup, and a /me endpoint. All Grails controllers route to identity-service through `IdentityClient`; the obx_token JWT cookie now carries location + roles claims; password state machine auto-migrates SHA-1+Base64 → BCrypt on successful login; RoleTypeCache + ThreadLocal-via-shim bridge admin-write endpoints to session.user until Phase 12. 32 commits landed (tagged `phase-2-identity` at 899dd4a63; 7 additional back-port commits followed). The codebase exits Phase 2 in a **hybrid state** — User/Person/Role GORM domain classes remain on Grails side as read-only bridges; identity-service owns persistence via JPA entities under JOINED inheritance; ~54 `session.user` readers in GSPs + controllers depend on AuthController/ApiController shims populating session from identity-service responses. Two-stage review held across 18 tasks; deferred-followups table closed 14 items in T18 hardening; 7 T19 back-ports applied post-tag (admin seed fix, Liquibase shadow cleanup, AuthService stray-check removal, plan clarifications, CI healthcheck + diagnostic extensions). Phase 2 uncovered 6+ codebase gotchas (Spring 4.3.30 HttpClientErrorException subclass absence, Liquibase bit(1) valueBoolean bug, ObjectNotFoundException 500-vs-404 API-wide mismatch, session.timezone taglib dependency) that Phase 3+ should anticipate.

## What worked

- **JOINED inheritance for Person ← User** worked first try across Grails GORM and identity-service JPA with zero mapping friction. Person is the parent table (id + active + profile fields); User is the child (same id as FK + PK, username + password). Hibernate's `@Inheritance(strategy = InheritanceType.JOINED)` + Grails GORM's `tablePerHierarchy false` produced identical schema. Both stacks read/write the same rows cleanly; no dual-write coordination needed because identity-service owns mutations and Grails only reads for session-shim purposes.

- **Password state machine + auto-migration** (SHA-1+Base64 → BCrypt via PasswordMigrator @Transactional(REQUIRES_NEW)) shipped without a single password-locked-out incident across 24 E2E tests + dev-DB admin migration. OpenboxesPasswordEncoder.matches() checks BCrypt first, falls back to SHA-1 verify, then PasswordMigrator upgrades the row in a separate transaction. ThreadLocal userId context prevents recursion. The pattern is a known-good strangler-fig technique for password hash rollover.

- **RoleTypeCache (refresh-on-miss, Map<String, RoleType>)** eliminated N+1 role-lookup queries in admin-permission checks. AuthService.adminChangePassword() calls `roleTypeCache.hasAnyType(callerRoleIds, ROLE_ADMIN, ROLE_SUPERUSER)`; cache loads role table once on first call, then serves from Map. Single-node safe (no distributed-cache complexity). Phase 3+ should use this pattern for any frequently-read enum-like tables.

- **Grails shim controllers (AuthController.handleLogin, ApiController.login/logout/chooseLocation)** + `session.user` / `session.warehouse` / `session.timezone` population preserved backward compatibility with ~54 `session.user` readers across GSPs + controllers without forcing a Phase 2 rewrite of every caller. The shims call identityClient → identity-service, then load User.findByUsername + Location.get to populate GORM proxies into session. ThreadLocal AuthService.setSessionUser(userId) bridges admin-write endpoints. Phase 12 will delete the ThreadLocal + session-shim once all callers migrate to JWT-only.

- **JWT obx_token cookie shape stable across Phase 1 + Phase 2.** Phase 1's JwtService issued HS256 HMAC cookies with user.id claim; Phase 2 extended to location + roles claims. Existing document-service JwtCookieAuthFilter worked without modification (roles claim extraction was dead code in Phase 1, went live in Phase 2). Shared OPENBOXES_JWT_SECRET env var still works; JWKS-based RS256 + identity-service as issuer queued for Phase X identity-service decoupling.

- **Two-stage CDR + CIR + SDD-per-task** again held across 18 dispatched tasks. Spec-compliance reviewers caught URL-only-vs-verb-change ambiguity (T15), missing SecurityInterceptor allowlist (T15 followup), false "password" vs "cleartext" fixture name mismatch (T16). Code-quality reviewers caught RestTemplate socket leak (T12 pre-dispatch), FQN collision between grails.validation.ValidationException and org.pih.warehouse.auth.ValidationException (T13), stray AuthService.login location.active check (T18, back-ported as BP-8). Deferred-followups table absorbed 21 items; T18 hardening closed 14 in 4 focused commits.

- **TestContainers + JUnit for identity-service** (18 test methods, MariaDB container, spring.sql.init.data-locations seed.sql, MockBean JavaMailSender) caught password-migration edge cases (cleartext rejection, SHA-1 → BCrypt verify path, null-active account rejection) before E2E. Playwright E2E (24 tests in 2.4s) validated end-to-end Grails shim + identity-service + nginx routing. The dual-layer test strategy (JUnit unit + Playwright integration) is the right cost for a strangler-fig migration.

## Codebase / env gotchas (Phase 3+ should know)

### Build & deploy

- **identity-service source NOT compiled inside container** (same pattern as document-service Phase 1). Source-on-host → bootJar-on-host → COPY into image. No volume mount. Every Java edit needs `cd services && ./gradlew :identity-service:bootJar` + `cd docker && sudo docker-compose up -d --build identity-service`. Grails app edits still need the full `prepareDocker -Dgrails.env=prod` + `docker-compose up -d --build app` cycle.

- **nginx caches upstream IPs across container rebuilds** (inherited from Phase 1). Rebuilding just identity-service assigns new internal IP; nginx on its original IP cache returns 502s to `/api/identity/*` until `sudo docker exec openboxes-nginx nginx -s reload`. The full `down && up -d` cycle avoids the issue. Phase 1 retrospective flagged this for all services; still relevant.

- **docker-compose 1.29.2 KeyError: 'ContainerConfig' on `up --build` after bootJar** — intermittent Docker API version mismatch when recreating identity-service container. Workaround: `docker-compose stop identity-service && docker-compose rm -f identity-service && docker-compose up -d identity-service` (stop + rm + up instead of `up --build` alone). Root cause unknown; likely Docker client version skew on dev box.

- **depends_on chain matters for Liquibase bootstrap order.** identity-service depends_on app; nginx depends_on identity-service. Without this, identity-service Liquibase races Grails Liquibase to CREATE TABLE person/user/role → Liquibase "table already exists" exception kills boot. Mirrors document-service depends_on app pattern exactly. Phase 2 commit fca04f28d added the chain; fresh-DB deploys now deterministic.

### Code-level

- **Spring 4.3.30 lacks HttpClientErrorException nested subclasses** (`.Unauthorized`, `.Forbidden`, `.BadRequest`, `.Conflict`). Grails 3 ships Spring Boot 1.5 vintage; those subclasses were added in Spring 5. IdentityClient + Grails shim controllers must catch parent `HttpClientErrorException` + check `e.statusCode == HttpStatus.UNAUTHORIZED`. Plan T12+T13 now documents this pattern after T13 code-quality review flagged it. Affects every Grails-side RestTemplate caller to identity-service or document-service.

- **Liquibase `valueBoolean: "true"` doesn't work for bit(1) columns** in Grails Liquibase Groovy DSL. `insert(tableName: "person") { column(name: "active", valueBoolean: "true") }` silently writes NULL to a `bit(1)` column instead of 1. Use `valueNumeric: "1"` instead. BP-1 back-port fixed the admin seed at `grails-app/migrations/install/changelog-insert-data.groovy:217`. Symptom: fresh-DB admin login returned 403 AccountDisabled because person.active was NULL.

- **Liquibase changeset checksum validation breaks on changeset body edits.** BP-2 converted identity-service changelogs from `<not><tableExists/>` + `<createTable>` to document-service shadow pattern (`<columnExists>` + empty body). Existing DATABASECHANGELOG rows had checksums for the old `<createTable>` body; boot failed with ValidationFailedException. Fix: `UPDATE DATABASECHANGELOG SET MD5SUM = NULL WHERE ID IN (...)` before restart. Lesson: shadow changelogs should be pure shadows from Day 1 to avoid checksum churn.

- **ObjectNotFoundException → 500 status (not 404) is OpenBoxes API-wide.** `ApiController.chooseLocation` + `StockMovementItemApi` + `CategoryApi` + `BinLocationApi` all return 500 when a domain-class ID lookup misses (e.g., `Location.get(badId)` throws ObjectNotFoundException, Grails UrlMappings.groovy:1120 handleNotFound maps to 500). This is pre-existing API-wide behavior, NOT a Phase 2 regression. T18 §2.5c probe initially misdiagnosed it; broader API error-mapping cleanup queued for Phase X hygiene. Don't special-case identity-service for 404-on-missing; match existing API contract.

- **session.timezone taglib dependency** was undocumented pre-Phase 2. `FormatTagLib.groovy:46` + `DateTagLib.groovy:77-78` read `session.timezone` for date rendering. Without it, dates render in JVM default TZ until user opens profile preferences and the natural Grails session population occurs. BP-10 back-port added `session.timezone = userInstance?.timezone` to AuthController.handleLogin shim at line 82 (next to session.user/userName population). Phase 3+ shims should populate session.timezone on any login-flow controller.

- **grails.validation.ValidationException vs org.pih.warehouse.auth.ValidationException FQN collision.** T13 code-quality review caught a latent bug: IdentityClient.signup's `catch (HttpClientErrorException.BadRequest e) { throw new ValidationException(...) }` would have thrown the wrong class if BadRequest fired (Grails auto-imports grails.validation.ValidationException; org.pih.warehouse.auth.ValidationException needed explicit import). Symptom: uncaught exception, 500 instead of 400. BP-7 back-port added "verify exception catch-block FQNs match throwing class" directive to code-quality-reviewer-prompt.md. Watch for this in any Grails controller/service with custom exception classes.

### Container / runtime

- **identity-service port 8082 is `expose:` only, NOT `ports:`.** Reachable from inside docker network + inside container, NOT from host. Host-side `curl http://localhost:8082/actuator/health` fails; correct equivalent is `sudo docker exec openboxes-identity-service curl -sf localhost:8082/actuator/health`. Docker's `(healthy)` flag is the reliable host-side indicator. This is intended security posture — all external traffic goes through nginx `/api/identity/*`. Mirrors document-service :8081 pattern exactly.

- **identity-service shutdown emits HHH000478 ERROR lines during JUnit teardown** (same benign noise as document-service Phase 1). When test JVM shuts down before TestContainers can cleanly issue schema-drop DDL, Hibernate logs `Unsuccessful: alter table if exists X drop foreign key ...`. The container is about to be killed anyway; don't grep `ERROR` in TestContainers shutdown logs without filtering these out. Phase 1 retrospective already flagged this.

- **CI E2E workflow needs init-baseline.sql safety net until BP-1 lands in fresh DBs.** `.github/workflows/e2e-tests.yml` line 53 applies `docker/init-baseline.sql` after boot to fix admin seed (cleartext password → SHA-1+Base64, person.active=NULL → 1, missing location_role row). BP-1 back-port fixed the Liquibase seed itself, but init-baseline.sql remains as idempotent safety net for any pre-BP-1 DB snapshots or demo-data imports. Don't delete the CI step.

## Process / meta-lessons

1. **Per-task smoke claims must include actual 200 success from known-good fixture.** Phase 2 Tasks 10-16 reported "end-to-end smoke confirmed" but were silently receiving 401/error responses from admin login (cleartext password + person.active=NULL rejected by identity-service). T17 Playwright was the first task to achieve a 200 login. The gap: "smoke" meant "no stack trace in logs," not "200 OK from /api/identity/login." Lesson: **smoke-test claims need a specific success criterion** (e.g., "curl POST /api/identity/login returns HTTP 200 + obx_token Set-Cookie header"). T17 uncovered the admin-seed regression; BP-1 + docker/init-baseline.sql back-ported the fix.

2. **Two-stage review (spec-compliance + code-quality) has non-overlapping failure modes, again.** Spec-compliance caught plan/spec ambiguity (T15 false verb-change diffs, T16 "password" vs "cleartext" fixture name mismatch, T15 missing SecurityInterceptor allowlist). Code-quality caught lifecycle/concurrency/FQN bugs spec was silent on (T12 RestTemplate socket leak, T13 ValidationException FQN collision, T18 stray AuthService.login location.active check). Either alone would have missed a non-trivial fraction. The dual-review cost is justified; Phase 3+ should continue the pattern.

3. **Deferred-followups table needs aggressive pruning between phases.** 21 rows remain after T18 hardening closed 14 (T7-M2/M3/M4 JWT roles-claim format cleanup, T8b-I2 format:metadata Map handling, T11-M4 JwtCookieAuthFilter SLF4J logger, T12-M1 IdentityClient timeout config, T13-M2/M3/M4/M5 exception handling + imports, T15-M1/M2 LoginModal "Forgot password?" link, T16-M1 seed.sql BCrypt hash recipe, T18-I1/I2/I3 chooseLocation null-checks + admin password change endpoint). Many "Any time" hygiene rows should be batched into a single "phase-2 cleanup" sprint rather than held individually. Phase 3 kickoff should triage: close anything pre-empted, batch hygiene, leave only high-signal items as carried-forward.

4. **Spring 4.x vs Spring 5+ API gaps deserve plan-level pre-warning.** The HttpClientErrorException nested-subclass absence (BP-6 back-port) was caught by code-quality review in T13, but should have been flagged earlier in plan T12+T13 as "Pre-Work: Spring 4.3.30 catch pattern." Phase 2 plan now documents it; Phase 3+ plans should similarly call out Grails 3 / Spring Boot 1.5 vintage API limits (no RestTemplateBuilder.setConnectTimeout(Duration), no OncePerRequestFilter SLF4J logger placeholders, etc.) before implementers write code.

5. **Liquibase shadow changelogs should be pure shadows from Day 1.** BP-2 converted identity-service changelogs from race-prone `<not><tableExists/>` + `<createTable>` to document-service shadow pattern (`<columnExists>` + empty body + comment). The conversion triggered Liquibase checksum-validation failures; required `UPDATE DATABASECHANGELOG SET MD5SUM = NULL` to clear. Lesson: **write shadows as shadows initially**; don't start with real DDL then convert post-hoc. Phase 3+ should use document-service/identity-service shadow pattern from T4 onward.

6. **JOINED inheritance is a stable strangler-fig technique for entity ownership transition.** Person/User pattern (Grails reads, identity-service owns mutations, both map to same schema) is the template for Phase 3 Location slice. Parent table on shared side (Person), child table on strangler side (User via JOINED), no dual-write coordination. Hibernate ddl-auto=validate proves entity-mapping correctness. Phase 3 can apply the same pattern to Location parent + Grails-side join-table vs identity-service-side ownership questions.

## Forward to Phase 3 (Location slice)

- **location_role join-table ownership precedent.** Phase 2 left location_role on Grails side as GORM List mapping (`User.hasMany = [locationRoles: ...]`); identity-service maps it as `@OneToMany` read-only. Phase 3 should decide: (a) migrate location_role ownership to identity-service + add `PUT /api/identity/users/{id}/locationRoles` endpoint, OR (b) keep GORM ownership + identity-service reads via native query for chooseLocation validation. Option (a) is cleaner long-term but requires rewriting ~12 Grails admin-UI controllers that mutate locationRoles; option (b) defers to Phase X.

- **identity-service depends_on app + nginx depends_on identity-service chain is the template.** Phase 3's hypothetical location-service (if Location slice spawns a separate service instead of extending identity-service) must `depends_on: app` so Grails Liquibase runs first. nginx must `depends_on: location-service`. Otherwise Liquibase races Grails to CREATE TABLE location → boot fails. BP-2 + commit fca04f28d documented this for Phase 2; Phase 3 should mirror exactly.

- **docker/init-baseline.sql idempotent-safety-net pattern** is reusable. If Phase 3 discovers a fresh-DB seed regression (e.g., location.active=NULL, missing default locationGroup), write an idempotent UPDATE + INSERT IGNORE script, apply it in CI via `docker exec -i openboxes-db mariadb ... < docker/init-phase3-baseline.sql`, then back-port the proper Liquibase seed fix. The idempotent script stays as safety net for pre-fix DBs.

- **RoleTypeCache refresh-on-miss pattern** is the template for any frequently-read enum-like table. If Phase 3 needs LocationTypeCache or LocationGroupCache, mirror RoleTypeCache: `Map<String, LocationType>` loaded once, `@PostConstruct refresh()` + `refresh()` fallback on cache-miss. Single-node safe; no distributed-cache complexity.

- **21 carried-forward backlog rows** (plan deferred-followups table) are the Phase 3+ starting backlog. T7-M2/M3 (JWT roles-claim format ROLE_* prefix + entity ID structure) + T11-M4 (JwtCookieAuthFilter SLF4J logger) specifically target Phase X identity-service decoupling. T13-M2/M3/M4 (IdentityClient exception handling cleanup) + T15-M1 (LoginModal "Forgot password?" link) target pre-production deploy hygiene.

## Phase X: Identity-service decoupling (deferred)

Documented in spec §16 (plan :2303-2327 mirrors it). Six unresolved questions block full decoupling:

1. **Admin UI fate:** `grails-app/views/user/`, `UserController.groovy`, `RoleController.groovy` — 12+ admin-focused GSPs + controllers for User/Role CRUD. Move to identity-service as Thymeleaf templates + Spring MVC controllers, OR keep on Grails + identity-service exposes full CRUD API?

2. **Bulk import paths:** `UserService.saveUser()`, `grails-app/services/org/pih/warehouse/importer/UserImporterService.groovy` — CSV/Excel bulk user import flows call Grails domain-save methods. Migrate to identity-service `/api/identity/users/import` endpoint + batch validation?

3. **User.findByUsername read bridges:** 8+ production sites load User.findByUsername purely to check existence or read email/name for display (not to mutate). Replace with identity-service GET /api/identity/users?username=X endpoint, OR leave as Grails read-only bridge until Phase 12?

4. **AuthService ThreadLocal setSessionUser/clearSessionUser:** T13 introduced ThreadLocal shim so admin-write endpoints (UserService.changePassword, UserController admin actions) can call identity-service with caller userId context. Phase X should delete ThreadLocal + migrate all callers to JWT-only (extract userId from obx_token instead of session.user). ~12 admin controllers affected.

5. **JWT issuance ownership + JWKS-based RS256 validation:** Phase 2 kept HS256 HMAC + shared OPENBOXES_JWT_SECRET. Phase X should migrate to identity-service as RS256 issuer + JWKS endpoint, Grails + document-service + nginx as RS256 validators fetching public key from `/.well-known/jwks.json`. Spring Security OAuth2 Resource Server library has built-in JWKS support.

6. **session.user / session.warehouse / session.timezone shim deletion:** ~54 `session.user` readers in GSPs + controllers. Phase X should rewrite to JWT-only (extract user.id from obx_token claim, fetch user display-name via `GET /api/identity/me` on first request, cache in session as `session.meResponse`). Alternatively, render user-info server-side in a Grails taglib that calls identity-service `/me` once per session.

Plan's preserved §16 at lines :2303-2327 is starting material — DO NOT take verbatim; re-recon required at Phase X dispatch time.

## Artifacts

- **Plan**: `docs/plans/2026-05-26-phase-2-identity-service-implementation-plan.md` (final state post-Task-19 back-ports)
- **Design spec**: `docs/specs/2026-05-26-phase-2-identity-service-design.md`
- **Scope audit**: `docs/audits/2026-05-26-phase-2-identity-scope-audit.md` (Task 3 output)
- **Tag**: `phase-2-identity` at `899dd4a63` (CI-green; local + remote)
- **Commit range** (Phase 2 core): `7f86af15c..899dd4a63` (24 commits)
- **Commit range** (Phase 2 + T19 back-ports): `7f86af15c..cc747188a` (32 commits)
- **Phase 1 retrospective** (predecessor): `docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`
- **Handoff docs** (3 sessions): `docs/handoffs/2026-05-26-phase-2-identity-handoff-{1,2,3}.md`
- **Carried-forward backlog**: 21 rows in plan §"Deferred follow-ups" (post-T18 state)
- **Deferred phase**: Phase X identity-service decoupling (spec §16, plan :2303-2327)

## Carry-forward backlog (T19 back-port items not committed to repo)

- **BP-7 location:** Code-quality-reviewer-prompt.md FQN-matching directive applied to `/home/yv01p/.claude/plugins/cache/claude-plugins-official/superpowers/5.1.0/skills/subagent-driven-development/code-quality-reviewer-prompt.md` (outside git repo; survived session but not committed). Future sessions can re-apply via `Edit` tool or document in local `.claude/` config if needed.

- **BP-11 (documented-only):** ObjectNotFoundException → 500-vs-404 status mismatch is OpenBoxes API-wide (not Phase 2 regression). Mentioned in this retrospective "Code-level gotchas" section. Broader API error-mapping cleanup queued for Phase X hygiene; no immediate action.

- **BP-14 (documented-only):** Per-task smoke claims must include actual 200 success (not just "no errors in logs"). Mentioned in this retrospective "Process / meta-lessons" section. T17 Playwright was first to achieve 200 login; gap documented for Phase 3+ awareness.
