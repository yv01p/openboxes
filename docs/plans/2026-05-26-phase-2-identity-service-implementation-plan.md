# Phase 2 Identity-service Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Source spec:** `docs/specs/2026-05-26-phase-2-identity-service-design.md` (commit SHA: `f3f4fc2dbfa5eaa75a351bb259335e81b5cedc06`)

**Goal:** Extract the auth slice (user / person / role / user_role / location_role tables + JWT issuance) from Grails into a standalone Spring Boot `identity-service`, following the Phase 1 document-service strangler-fig precedent and the parent spec's per-slice template §8.

**Architecture:** Spring Boot 3.3.5 + Java 21 module under `services/identity-service/` mirroring the document-service layout. JOINED inheritance maps `User extends Person`. Identity-service owns user-initiated writes (login, signup, password change/reset, chooseLocation) and JWT issuance. Grails domain classes stay alive as read-bridges through GORM against the shared DB; Grails-side shim controllers POST to identity-service and forward `Set-Cookie`. SecurityInterceptor reduces to a pure JWT consumer (issue + buildSetCookieHeader deleted).

**Tech stack:** Spring Boot 3.3.5, Java 21, jjwt 0.12.5, Spring Security 6.x (BCrypt cost 10), Spring Data JPA + Hibernate 6, Liquibase, mariadb-java-client 3.4.1, spring-boot-starter-mail (SMTP), springdoc-openapi 2.5.0, Testcontainers 1.21.3 (BOM override), MariaDB 10.6, Playwright (e2e).

---

## File Structure

### Create — identity-service module (Tasks 2–10)

- `services/identity-service/build.gradle` — Gradle deps (mirrors document-service)
- `services/identity-service/Dockerfile` — multi-stage temurin:21-jre + apt-get curl + non-root spring user
- `services/identity-service/src/main/java/org/openboxes/identity/IdentityServiceApplication.java` — Spring Boot entry point
- `services/identity-service/src/main/java/org/openboxes/identity/entity/{Person,User,Role,LocationRole,RoleType,PasswordResetToken}.java` — JPA entities; JOINED inheritance for User↔Person
- `services/identity-service/src/main/java/org/openboxes/identity/repository/{PersonRepository,UserRepository,RoleRepository,LocationRoleRepository,PasswordResetTokenRepository}.java` — Spring Data JPA repositories
- `services/identity-service/src/main/java/org/openboxes/identity/password/{OpenboxesPasswordEncoder,PasswordMigrator,PasswordComplexityValidator}.java` — custom PasswordEncoder + auto-migrate + complexity rules
- `services/identity-service/src/main/java/org/openboxes/identity/security/{RoleTypeCache,JwtCookieAuthFilter,SecurityConfig}.java` — admin-authz cache + JWT filter + filter chain
- `services/identity-service/src/main/java/org/openboxes/identity/service/{AuthService,JwtService,CookieService,SignupService,EmailService,RecaptchaService,PasswordResetService,UserLookupService}.java` — domain services
- `services/identity-service/src/main/java/org/openboxes/identity/controller/{AuthController,SignupController,PasswordController,UserLookupController}.java` — REST controllers
- `services/identity-service/src/main/java/org/openboxes/identity/dto/*.java` — request/response DTOs
- `services/identity-service/src/main/resources/application.yml` — Spring config (DB, mail, JWT secret)
- `services/identity-service/src/main/resources/db/changelog/identity-changelog-master.xml` + per-table shadow changelogs + new `password_reset_token` changelog
- `services/identity-service/src/main/resources/templates/{welcome.html,password-reset.html}` — email templates (Thymeleaf optional; can be inlined Strings if Thymeleaf not desired)
- `services/identity-service/src/test/java/org/openboxes/identity/IdentityServiceIntegrationTest.java` — TestContainers JUnit
- `services/identity-service/src/test/resources/test-data/*.sql` — fixture seed for tests

### Create — Grails-side IdentityClient (Task 12)

- `grails-app/services/org/pih/warehouse/auth/IdentityClient.groovy` — Grails service bean (~150 lines); mirrors `DocumentClient.groovy` pattern

### Create — forgot-password GSP flow (Task 15)

- `grails-app/views/auth/forgotPassword.gsp` — request-reset form
- `grails-app/views/auth/resetPassword.gsp` — confirm-reset form (reachable via email link with `?token=...`)

### Create — e2e specs (Task 17)

- `e2e/tests/identity-login.spec.ts`
- `e2e/tests/identity-logout.spec.ts`
- `e2e/tests/identity-choose-location.spec.ts`
- `e2e/tests/identity-grails-shim-regression.spec.ts`
- `e2e/tests/identity-password-change.spec.ts`
- `e2e/tests/identity-password-reset.spec.ts`
- `e2e/tests/identity-caller-regression.spec.ts`
- `e2e/tests/identity-signup.spec.ts` (optional; conditionally skipped when `OPENBOXES_SIGNUP_ENABLED=false`)

### Create — retrospective (Task 19)

- `docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`

### Modify

- `services/settings.gradle` — add `include 'identity-service'`
- `docker/docker-compose-base.yml` — add `identity-service` service entry below `document-service`; add `OPENBOXES_JWT_SECRET` + mail env vars
- `docker/nginx/conf.d/app.conf` — add `location /api/identity { proxy_pass http://identity-service:8082; ... }` block ABOVE `/api/documents`
- `grails-app/conf/spring/resources.groovy:23-24` — add `identityClient(org.pih.warehouse.auth.IdentityClient)` line after `documentClient`
- `grails-app/services/org/pih/warehouse/auth/JwtService.groovy` — delete `issue(User, Location)` + `buildSetCookieHeader(String, boolean)` methods; keep `validate(String)`, `COOKIE_NAME`, `TOKEN_LIFETIME_SECONDS`, `getSigningKey()`
- `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy:43-67` — `login()` becomes shim; `chooseLocation()` becomes shim
- `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy:258-272` — `logout()` becomes shim
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy:69-135` — `handleLogin()` becomes shim
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy:141-153` — `logout()` becomes shim
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy:178-222` — `handleSignup()` becomes shim
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy` — add `forgotPassword()`, `resetPassword()` actions as new shims (forgot-password flow)
- `grails-app/controllers/org/pih/warehouse/user/UserController.groovy:278-299` — `changePassword()` action routes to admin endpoint (target ≠ current user) or self-edit endpoint (target = current user)
- `grails-app/controllers/org/pih/warehouse/user/DashboardController.groovy:223-228` — delete the `user.lastLoginDate = new Date(); user.save(flush: true)` block (preserve the surrounding `if (user) { session.user = user }` wrapping)
- `grails-app/views/user/edit.gsp:132-160` — add `currentPassword` input field inside `#password-tab` (above the existing `password` field) for self-edit flow
- `src/js/components/LoginModal.jsx:24,41` — update URLs `/api/login` → `/api/identity/login`; `/api/chooseLocation/${id}` → `/api/identity/chooseLocation/${id}` (also change `apiClient.post` → `apiClient.put` at line 41)
- `src/js/actions/index.js:220` — update URL `/api/chooseLocation/${location.id}` → `/api/identity/chooseLocation/${location.id}` + verb change
- `src/js/components/LoginModal.jsx` — add "Forgot password?" link that navigates to `/openboxes/auth/forgotPassword` (Grails GSP, shim flow)

### Delete

- `grails-app/services/org/pih/warehouse/auth/UserSignupEventService.groovy` — welcome-email responsibility moves to identity-service's `EmailService`
- `src/main/groovy/org/pih/warehouse/auth/UserSignupEvent.groovy` — IF no other consumers (verify via grep at Task 14 time; if other consumers exist, keep the class and only delete the publishEvent call from AuthController.handleSignup since it's now a shim)

---

## Inherited from spec

The following assumptions were verified by `thorough-brainstorming` and remain ground truth (see spec §13, A1–A31). Not re-verified here.

- **A1.** nginx routing pattern accepts `/api/identity` block above `/api/documents` (`docker/nginx/conf.d/app.conf:11-23`).
- **A2.** Port 8082 unused (`docker-compose-base.yml` exposes 8080, 8081 only).
- **A3.** `services/settings.gradle:2` extension pattern (`include 'document-service'`).
- **A4.** `OPENBOXES_JWT_SECRET` env shared across services (`docker-compose-base.yml:18,37`).
- **A5.** Grails `SecurityInterceptor.groovy:38-59` JWT-validation block stays unchanged.
- **A6.** `jwtService.issue` callers limited to 3 plant points (`ApiController.groovy:55,69` + `AuthController.groovy:113`).
- **A7.** `ApiController.login` current shape: accepts JSON `{username, password, location}`, returns plain text; identity-service returns richer `{user, location}` JSON shape and shim populates `session.user/session.warehouse` from response body.
- **A8.** `chooseLocation` accepts id in path (`ApiController.groovy:64`).
- **A9.** `UserController.changePassword` currently lacks current-password check; design adds it for self-edit; admin-edit preserves no-current-password.
- **A10.** Forgot-password / reset-password actions do NOT exist in Grails today; Phase 2 builds these as NEW feature (2 endpoints + email + new `password_reset_token` table + GSP reset page).
- **A11.** `encodeAsPassword()` resolves to **SHA-1** + Base64 + no salt (`PasswordCodec.groovy:18` — `MessageDigest.getInstance('SHA')`). Cleartext fallback at `UserService.groovy:494` is DROPPED.
- **A12.** Person/User schema: User table has NO `version`, `date_created`, `last_updated`. `active` was MOVED from user to person. `@Version` on Person only.
- **A13.** JOINED inheritance: `Person.groovy:28` declares `tablePerHierarchy false`; person + user are separate tables sharing CHAR(38) ids.
- **A14–A19.** Role/LocationRole/user_role schema details (CHAR(38) ids, BIGINT version on Person/Role, INT version on LocationRole, no version on user_role, photo MEDIUMBLOB, dashboard_config LONGBLOB).
- **A20.** Hidden `lastLoginDate` writer at `DashboardController.groovy:225`; semantic moves to identity-service `/chooseLocation`; Grails block deleted. `JsonController.groovy:1609` only reads.
- **A21.** Admin write surface enumerated in §15 (UserController/RoleController/PersonController admin CRUD + ShipmentController/CreateShipmentWorkflowController Person-creation + import services + Sql.execute seed).
- **A22.** UserSignupEvent has single producer (`AuthController.groovy:209`) + single consumer (UserSignupEventService).
- **A23.** RecaptchaService single integration (only AuthController calls it).
- **A24.** Grails MailService config under `grails.mail.*` keys; identity-service uses Spring Boot `spring.mail.*` env keys with same values.
- **A25.** React URL constants are inline at 3 sites — no central constants file.
- **A26.** No L2 cache on User/Person/Role/LocationRole; no Phase 1-style cache flip needed.
- **A27.** TestContainers BOM 1.21.3 override + `api.version=1.44` + `ryuk.disabled=true` pattern from `services/document-service/build.gradle`.
- **A28.** Grails Spring DI wiring at `resources.groovy:23` (`documentClient(...)`); new `identityClient(...)` line added.
- **A29.** `response.setHeader('Set-Cookie', ...)` forwarding works (already used at `ApiController.groovy:56,70` + `AuthController.groovy:114,142`).
- **A30.** 5-container memory headroom OK (~3.7GB target vs Phase 1 baseline ~3GB).
- **A31.** Admin role-type detection from raw-ID JWT claims via in-memory `Map<roleId, RoleType>` populated by `SELECT id, role_type FROM role` at startup; refresh-on-cache-miss reload (per TWP decision; see P30 below).

---

## Verified plan-level assumptions

Newly introduced by this plan (paths, signatures, commands, ordering, consumer impact) and verified at plan-write time against the codebase + Phase 1 retrospective. Spec literal corrections noted explicitly.

| # | Cat | Assumption | Evidence |
|---|-----|------------|----------|
| P1 | path | `services/identity-service/` does NOT yet exist (Task 2 creates) | `ls services/` → only `build.gradle`, `document-service`, `gradle`, `gradlew*`, `settings.gradle` |
| P2 | path | `services/build.gradle` (root) pre-configures Spring Boot 3.3.5 + dependency-management 1.1.6 + Java 21 toolchain for all subprojects | `cat services/build.gradle` confirmed |
| P3 | path | `services/document-service/build.gradle` is the template for identity-service deps (Spring Boot starters web/data-jpa/security/validation/actuator, liquibase-core, springdoc-openapi 2.5.0, jjwt 0.12.5, mariadb-java-client 3.4.1, testcontainers BOM 1.21.3 override) | `cat services/document-service/build.gradle` confirmed |
| P4 | path | `services/document-service/Dockerfile` uses `apt-get install -y curl` (NOT wget) + non-root `spring` user — this is the template | `cat services/document-service/Dockerfile` confirmed. **SPEC CORRECTION (§5):** spec's "wget-based healthcheck" comment is outdated; document-service installs curl explicitly. Plan matches actual precedent. |
| P5 | path | `services/document-service/src/main/java/org/openboxes/document/security/{JwtCookieAuthFilter,SecurityConfig}.java` is the template for Task 10's identity-service filter chain | Files confirmed; SecurityConfig uses `SecurityFilterChain` bean + `HttpStatusEntryPoint(401)` + stateless session policy |
| P6 | path | `docker/docker-compose-base.yml` has `build:` directive at app:3-5 and document-service:27-29; same pattern for new identity-service entry | `grep -n "build:" docker/docker-compose-base.yml` returned both |
| P7 | path | `docker/nginx/conf.d/app.conf:11-23` has `/api/documents` block above `/api/`; new `/api/identity` block goes ABOVE `/api/documents` (most-specific-first) | `cat` confirmed |
| P8 | path | `grails-app/conf/spring/resources.groovy:23` has `documentClient(org.pih.warehouse.core.DocumentClient)`; new `identityClient(...)` line goes at line 24 | `cat` confirmed |
| P9 | path | `grails-app/services/org/pih/warehouse/auth/JwtService.groovy` has `issue(User, Location)` at line 31, `validate(String)` at line 45, `buildSetCookieHeader(String, boolean=false)` at line 58, `COOKIE_NAME='obx_token'` at line 19, `TOKEN_LIFETIME_SECONDS = 8*3600` at line 20 | `cat` confirmed |
| P10 | path | `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy` has `login()` at 43, `chooseLocation()` at 63, `logout()` at 258 | `grep -n "def login\|def chooseLocation\|def logout"` confirmed |
| P11 | path | `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy` has `handleLogin` at ~69-135, `logout` at ~141-153, `handleSignup` at ~178-222 | `sed -n` reads confirmed |
| P12 | path | `grails-app/controllers/org/pih/warehouse/user/UserController.groovy:278` has `changePassword()` action; signature takes `params.id, params.password, params.passwordConfirm` (no currentPassword) | `sed -n '275,302p'` confirmed |
| P13 | path | `grails-app/controllers/org/pih/warehouse/user/DashboardController.groovy:223-228` has the `if (user) { user.lastLoginDate = new Date(); user.save(flush: true); session.user = user }` block; Task 14 deletes only the `lastLoginDate` + `save` lines, preserves the `session.user = user` reassignment | `sed -n '223,230p'` confirmed |
| P14 | path | **SPEC CORRECTION (§8.5):** `UserSignupEventService.groovy` is at `grails-app/services/org/pih/warehouse/auth/UserSignupEventService.groovy` (NOT `core/` as spec §8.5 implies) | `find . -name "UserSignupEvent*"` returned the correct path |
| P15 | path | **SPEC CORRECTION (§8.5):** `UserSignupEvent.groovy` (event class) is at `src/main/groovy/org/pih/warehouse/auth/UserSignupEvent.groovy` (NOT in `grails-app/domain/`) | `find` confirmed |
| P16 | path | **SPEC CORRECTION (§8.2 + §6.2):** `grails-app/views/user/changePassword.gsp` does NOT exist. The change-password form is embedded in `grails-app/views/user/edit.gsp:132-160` under `#password-tab`. Task 13's `currentPassword` field addition modifies `edit.gsp`, not a separate `changePassword.gsp`. | `ls grails-app/views/user/` returned `edit.gsp`, `show.gsp`, etc. — no `changePassword.gsp`. `grep -n "password" grails-app/views/user/edit.gsp` confirmed the password-tab section at line 132. |
| P17 | path | `src/js/components/LoginModal.jsx:24,41` has the login URL + chooseLocation URL; current React code does NOT use the login response body (just calls `.then(() => setUserLocation())`) — switching response shape to JSON is safe | `sed -n '20,45p'` confirmed; spec §A7 noted this |
| P18 | path | `src/js/actions/index.js:220` has the chooseLocation URL | (Phase 1 confirmed; spec §A25) |
| P19 | path | `e2e/tests/` exists with 9 existing specs; `cd e2e && npm test` invokes Playwright | `ls e2e/` + `cat e2e/package.json` confirmed |
| P20 | sig | `User.findByUsernameOrEmail(String, String)` takes 2 args (`grails-app/controllers/.../ApiController.groovy:47` + `AuthController.groovy:70`) — identity-service `UserRepository` reimplements via JPA `Optional<User> findByUsernameOrEmail(String username, String email)` or equivalent query | `grep -n "findByUsernameOrEmail"` confirmed 3 callers |
| P21 | sig | `userService.authenticate(username, password)` returns boolean-equivalent (`def`, body returns true/false); 2 callers (`ApiController.groovy:46` + `AuthController.groovy:98`). Both callers become shims; **after Task 13 there are ZERO non-shim callers** — `authenticate` can be deleted in Task 14, OR kept as a Grails-only wrapper that internally calls `IdentityClient.login()`. Plan picks DELETE (smaller surface; no callers). | `grep -rn "userService\.authenticate\|userService.authenticate(" grails-app/`; body at `UserService.groovy:481-498`. **SPEC CLARIFICATION (§8.5):** the "could be deleted or kept" hedge is unnecessary — the empirical caller surface is zero post-Phase-2. |
| P22 | sig | `userService.assignDefaultRoles(User userInstance)` returns void; reads `grailsApplication.config.openboxes.signup.defaultRoles` (comma-separated RoleType names), looks up Role via `Role.findByRoleType(roleType)`, adds to `userInstance.roles`. Identity-service `SignupService` reimplements via Spring `@Value("${openboxes.signup.default-roles}")` + RoleRepository. | `sed -n '107,128p' UserService.groovy` confirmed |
| P23 | sig | `recaptchaService.validate(token)` at `grails-app/services/org/pih/warehouse/auth/RecaptchaService.groovy:16`. Identity-service `RecaptchaService` reimplements via Spring RestClient to Google's siteverify endpoint. | `grep` confirmed |
| P24 | sig | `JwtService.issue/validate/buildSetCookieHeader/COOKIE_NAME/TOKEN_LIFETIME_SECONDS` API matches what Tasks 13–14 reference | `cat JwtService.groovy` confirmed |
| P25 | sig | Spring Security 6.x `SecurityFilterChain` bean pattern (vs deprecated `WebSecurityConfigurerAdapter`) is the document-service precedent; identity-service mirrors it | `cat services/document-service/.../SecurityConfig.java` confirmed |
| P26 | sig | jjwt 0.12.x API (`Jwts.builder()`, `.subject()`, `.claim()`, `.signWith(SecretKey, MacAlgorithm)`, `.compact()` + `Jwts.parser().verifyWith(key).build().parseSignedClaims(token).getPayload()`) differs from Grails-side 0.11.x. Identity-service uses 0.12.x exclusively per `services/document-service/build.gradle` precedent (already uses 0.12.5) | document-service `JwtCookieAuthFilter.java:42-46` shows `Jwts.parser().verifyWith(signingKey)...` pattern |
| P27 | sig | `MessageDigest.getInstance("SHA").digest(rawPassword.getBytes(UTF_8))` works in Java 21 (the "SHA" alias still resolves to SHA-1 per Java's algorithm name conventions) | Java SDK docs; PasswordCodec.groovy:18 uses the same alias |
| P28 | sig | `org.springframework.security.crypto.password.PasswordEncoder` interface has `String encode(CharSequence)` + `boolean matches(CharSequence, String)`. Plan Task 5's `OpenboxesPasswordEncoder` implements this interface | Spring Security 6.x stable API |
| P29 | sig | `@Transactional(propagation = REQUIRES_NEW)` on `PasswordMigrator.migrate(userId, newBcryptHash)` opens a nested transaction isolated from the caller's tx — Spring routes through `TransactionInterceptor` correctly when injected as a separate bean (not a self-invocation on same bean). Task 5 puts `PasswordMigrator` in its own `@Service` class to avoid self-invocation pitfall. | Spring docs; standard pattern |
| P30 | sig | `RoleTypeCache` refresh-on-cache-miss: on `getRoleType(roleId)` miss, reload entire cache via `SELECT id, role_type FROM role` and retry once. If still missing → null (treated as non-admin). Resolves the spec §6.2 TWP-level decision | TWP user pick |
| P31 | cmd | `cd services && sudo -E ./gradlew :identity-service:test` runs TestContainers integration tests; mirrors Phase 1's `:document-service:test` (TestContainers needs docker socket; dev user not in docker group → sudo required) | Phase 1 retrospective §"Build & deploy" + `cat services/document-service/build.gradle` test block |
| P32 | cmd | `cd services && ./gradlew :identity-service:bootJar` builds the fat jar; MUST run BEFORE `cd docker && sudo docker-compose up -d --build identity-service` (Dockerfile COPIES the jar; stale cache pitfall per Phase 1 retro) | Phase 1 retrospective §"Build & deploy" |
| P33 | cmd | `./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain` rebuilds Grails WAR for shim controller changes; `cd docker && sudo docker-compose up -d --build app` restarts Grails container | Phase 1 retrospective §"Build & deploy" |
| P34 | cmd | `sudo docker exec openboxes-nginx nginx -s reload` reloads nginx config without container restart (Task 11 nginx route activation) | Phase 1 retrospective gotchas |
| P35 | cmd | `cd e2e && E2E_LOCATION_ID=1 npm test` runs all Playwright specs; `cd e2e && npx playwright test identity-` runs only identity specs | `cat e2e/package.json` + Phase 0/1 plan precedent |
| P36 | cmd | Phase 1 commit-message convention: `phase N: <description>` lowercase, NO Conventional-Commits prefix | `git log --oneline -15` |
| P37 | cmd | Tag pattern: `git tag phase-2-identity <SHA>` + `git push origin phase-2-identity` at clean Phase 2 HEAD BEFORE retrospective commit (Phase 1 precedent) | Phase 1 plan Task 13; spec §12.3 |
| P38 | order | Task 2 (module bootstrap) → Task 3 (entities, depend on module) → Task 4 (Liquibase, depends on entity schema understanding) → Tasks 5/6 (encoders/cache, depend on UserRepository/RoleRepository from Task 3) → Tasks 7/8/9/10 (controllers + filter chain, depend on services from Tasks 5-6 + Task 3 entities). All Spring-Boot-side tasks complete before Task 11 (nginx) | Sequential per spec §8 + standard Spring Boot layering |
| P39 | order | Task 11 (nginx) → Task 12 (IdentityClient — can smoke-test via in-network identity-service:8082 before nginx, but cleaner to wire after nginx) → Task 13 (shims, depend on IdentityClient bean) → Task 14 (JwtService cleanup + DashboardController + UserSignupEventService delete — depends on shims no longer calling deleted symbols) | Self-evident |
| P40 | order | Task 15 (React URLs + forgot-password GSP) → Task 16 (JUnit tests, depend on identity-service stable) → Task 17 (Playwright, depend on full stack live including shims) → Task 18 (done-gate + soak + tag) → Task 19 (retrospective) | Self-evident |
| P41 | impact | After Task 13 + 14, `userService.authenticate` has 0 callers → safe to delete in Task 14 (P21 finding). | `grep -rn "userService\.authenticate"` returned 2, both become shims |
| P42 | impact | After Task 14, `JwtService.issue` + `JwtService.buildSetCookieHeader` have 0 callers — Phase 2 done-gate grep §12.1 verifies this | spec §12.1 |
| P43 | impact | `UserSignupEvent.groovy` deletion is conditional on no other consumers — Task 14 must `grep -rn "UserSignupEvent\b" grails-app/ src/main/` and only delete if returns 1 hit (the publishEvent call in handleSignup, which itself becomes a shim) + the class declaration itself | Verified at task time |
| P44 | impact | DashboardController:223-228 block deletion preserves `session.user = user` reassignment (this line is independent of the lastLoginDate write — keeping it preserves the Grails session-resync behavior on chooseLocation) | `sed -n '223,228p'` confirmed |
| P45 | impact | `session.user`, `session.userName`, `session.warehouse` are read by ~54 files (per spec) — all populated by Phase 0's SecurityInterceptor JWT-decode block (`SecurityInterceptor.groovy:38-59`) PLUS by Phase 2 shims (which set them from identity-service response body). Continuity preserved. | spec §15 + SecurityInterceptor verified in CDR R1 |
| P46 | impact | `Set-Cookie` forwarding: identity-service issues `obx_token=<jwt>; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800` (matches Grails JwtService format). Grails shim does `response.setHeader('Set-Cookie', identityResponseSetCookieHeader)` to forward. Both Grails-served pages and React get the cookie. | Matches existing `response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader(token))` pattern at ApiController:56,70 + AuthController:114,142 |
| P47 | impact | nginx `/api/identity` block insertion BEFORE `/api/documents` preserves Phase 1 routing (`/api/documents` → document-service, `/api/` → Grails). nginx prefix-match longest-first means specificity is rule-order-dependent in conf.d files. | `cat docker/nginx/conf.d/app.conf` confirmed |
| P48 | cmd | **SPEC LITERAL NOTE (§12.1):** "OpenAPI 3.0.1 with the 11 endpoints listed" — actual endpoint count is **10**: login, logout, chooseLocation, me (4 in §6.1) + signup, password/change, users/{id}/password, password/reset-request, password/reset/{token} (5 in §6.2) + GET /users/{id} (1 in §6.3) = 10. Done-gate adjusts the count to 10. | Spec §6 enumeration |

---

## §8 per-slice template mapping

| §8 Step | Task |
|---------|------|
| Step 1: Cross-context audit + live-smoke-probe | Task 1 |
| Step 2: Bootstrap services/identity-service module + Dockerfile + compose | Task 2 |
| Step 3: Port domain to JPA entities | Task 3 |
| Step 4: Port business logic (encoders, cache, validators) | Tasks 5–6 |
| Step 5: Port REST controllers | Tasks 7–10 |
| Step 6: Move Liquibase changesets (shadow MARK_RAN) | Task 4 |
| Step 7: Wire JWT cookie validation | Task 10 (filter chain config) |
| Step 8a: Update React frontend | Task 15 (URL updates) |
| Step 8b: Migrate Grails callers via shims | Tasks 12 + 13 |
| Step 9: Add nginx route | Task 11 |
| Step 10: Delete Grails counterparts | **DEFERRED to Phase X** (per spec §15 + §16) — Phase 2 retains Grails domain classes as read-bridges |
| Step 11: Tests (JUnit + Playwright) | Tasks 16 + 17 |
| Step 12: Done-gate verification | Task 18 |
| Step 13: Soak + tag | Task 18 |

---

## Tasks

### Task 1: Scope audit + live-smoke-probe (§8 Step 1)

**Files:**
- Create: `docs/audits/2026-05-26-phase-2-identity-scope-audit.md` (committed for traceability)

- [ ] **Step 1: Enumerate Grails-side identity write surface.**
```bash
# All writers to user/person/role/user_role/location_role tables — verify against spec §15 carve-out list
grep -rn "\.save(\|\.delete()\|new User(\|new Person(\|new Role(\|new LocationRole(\|addToRoles\|removeFromRoles\|addToLocationRoles\|removeFromLocationRoles" grails-app/ | \
  grep -iE "user|person|role|locationrole" | sort
# Sql.execute paths touching identity tables
grep -rn "Sql\.execute\|sql\.executeUpdate\|sql\.execute" grails-app/ | grep -iE "user|person|role"
# Per-controller write surface
grep -rn "userInstance\.save\|personInstance\.save\|roleInstance\.save\|locationRoleInstance\.save" grails-app/
```
For each finding, classify against §15 carve-out:
- In spec §15 carve-out → leave as-is (documented hybrid state)
- NOT in §15 carve-out → flag to user as scope expansion (plan revision needed before proceeding)

- [ ] **Step 2: Enumerate `session.user` / `authService.currentUser` readers (the ~54 files).**
```bash
grep -rln "session\.user\|authService\.currentUser" grails-app/ | sort -u | wc -l
grep -rln "session\.user\|authService\.currentUser" grails-app/ | sort -u > /tmp/identity-readers.txt
```
Record count + first 20 entries in audit doc. These do NOT migrate in Phase 2 (they read from JWT-populated session per SecurityInterceptor unchanged behavior).

- [ ] **Step 3: Live-smoke-probe current Grails identity flows.**
With the current 4-container stack running:
```bash
# Probe 1: API login (current /api/login plain-text response)
JAR=$(mktemp); curl -s -c "$JAR" -X POST http://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password","location":"1"}' \
  -w "HTTP %{http_code}\n"
# Expect: HTTP 200 + obx_token cookie set in $JAR

# Probe 2: chooseLocation
curl -s -b "$JAR" -X POST http://localhost/api/chooseLocation/1 -w "HTTP %{http_code}\n"
# Expect: HTTP 200

# Probe 3: GSP login form (handleLogin path)
curl -s -L -X POST http://localhost/openboxes/auth/handleLogin \
  --data-urlencode "username=admin" --data-urlencode "password=password" \
  -o /dev/null -w "final URL: %{url_effective} HTTP %{http_code}\n"
# Expect: redirect to /openboxes/dashboard/index, HTTP 200

# Probe 4: Logout
curl -s -b "$JAR" -X POST http://localhost/api/logout -w "HTTP %{http_code}\n"
# Expect: HTTP 200 + clear-cookie Set-Cookie header

# Probe 5: Dashboard reachable (verifies session.user populated correctly post-login)
curl -s -b "$JAR" -X GET http://localhost/openboxes/dashboard/index -L -o /dev/null -w "HTTP %{http_code}\n"
# Expect: HTTP 200 (or redirect-to-login if cookie expired — re-login if so)

rm -f "$JAR"
```
Record each probe's status + response shape in the audit doc. Identity-service Phase 2 done-gate (Task 18) re-runs equivalent probes against the new shim path.

- [ ] **Step 4: Live-probe the database state for auth tables.**
```bash
# Verify table column shapes (confirms spec §A12-A19 against the current DB)
sudo docker exec openboxes-db mysql -uopenboxes -popenboxes openboxes -e "
  DESCRIBE \`user\`;
  DESCRIBE person;
  DESCRIBE role;
  DESCRIBE user_role;
  DESCRIBE location_role;
"
# Verify ROLE_ADMIN + ROLE_SUPERUSER role records exist (Task 6 RoleCache depends on this)
sudo docker exec openboxes-db mysql -uopenboxes -popenboxes openboxes -e "
  SELECT id, role_type, name FROM role WHERE role_type IN ('ROLE_ADMIN', 'ROLE_SUPERUSER');
"
# Verify password column for admin user (to confirm SHA-1 hash or BCrypt — affects Task 5 auto-migrate test)
sudo docker exec openboxes-db mysql -uopenboxes -popenboxes openboxes -e "
  SELECT id, username, LENGTH(password), LEFT(password, 4) AS prefix FROM \`user\` WHERE username = 'admin';
"
# Length 28 = SHA-1+Base64; length 60 + prefix '\$2a\$' = BCrypt
```
Record column-by-column shapes + role IDs + admin password format in audit doc.

- [ ] **Step 5: Commit audit.**
```bash
git add docs/audits/2026-05-26-phase-2-identity-scope-audit.md
git commit -m "phase 2 task 1: cross-context audit + scope confirmation for Identity slice"
```

### Task 2: Bootstrap `services/identity-service/` Gradle module + Dockerfile + docker-compose entry (§8 Step 2)

**Files:**
- Create: `services/identity-service/build.gradle`, `services/identity-service/Dockerfile`, `services/identity-service/src/main/java/org/openboxes/identity/IdentityServiceApplication.java`, `services/identity-service/src/main/resources/application.yml`
- Modify: `services/settings.gradle` — add `include 'identity-service'`
- Modify: `docker/docker-compose-base.yml` — add `identity-service` block after `document-service`

- [ ] **Step 1: Update `services/settings.gradle`.**
```groovy
rootProject.name = 'openboxes-services'
include 'document-service'
include 'identity-service'
```

- [ ] **Step 2: Create `services/identity-service/build.gradle`.** Copy `services/document-service/build.gradle` verbatim except: package coordinates (`openboxes.identity`), jar baseName, and add `spring-boot-starter-mail` to dependencies. Keep `ext['testcontainers.version'] = '1.21.3'`, `useJUnitPlatform()`, `api.version=1.44`, `testcontainers.ryuk.disabled=true` (Phase 1 T11-I1 lesson).
```groovy
dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.springframework.boot:spring-boot-starter-mail'
    implementation 'org.liquibase:liquibase-core'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client:3.4.1'
    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.3'
    testImplementation 'org.testcontainers:mariadb:1.21.3'
}
```

- [ ] **Step 3: Create `IdentityServiceApplication.java`.**
```java
package org.openboxes.identity;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class IdentityServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(IdentityServiceApplication.class, args);
    }
}
```

- [ ] **Step 4: Create `application.yml` (env-var driven, mirrors document-service).**
```yaml
server:
  port: 8082
spring:
  application:
    name: identity-service
  datasource:
    url: ${DATASOURCE_URL}
    username: ${DATASOURCE_USERNAME}
    password: ${DATASOURCE_PASSWORD}
    driver-class-name: org.mariadb.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate   # schema is owned by Liquibase + Grails shadow
    properties:
      hibernate.dialect: org.hibernate.dialect.MariaDBDialect
  liquibase:
    change-log: classpath:db/changelog/identity-changelog-master.xml
  mail:
    host: ${MAIL_HOST:smtp.example.com}
    port: ${MAIL_PORT:587}
    username: ${MAIL_USERNAME:}
    password: ${MAIL_PASSWORD:}
    properties:
      mail.smtp.auth: true
      mail.smtp.starttls.enable: true
management:
  endpoints:
    web:
      exposure:
        include: health,info
openboxes:
  jwt:
    secret: ${OPENBOXES_JWT_SECRET}
  mail:
    from: ${MAIL_FROM:openboxes@example.com}
  signup:
    enabled: ${OPENBOXES_SIGNUP_ENABLED:false}
    default-roles: ${OPENBOXES_SIGNUP_DEFAULT_ROLES:ROLE_BROWSER}
    recaptcha:
      enabled: ${OPENBOXES_SIGNUP_RECAPTCHA_ENABLED:false}
      secret: ${OPENBOXES_SIGNUP_RECAPTCHA_SECRET:}
```

- [ ] **Step 5: Create `Dockerfile`.** Copy `services/document-service/Dockerfile` verbatim except port (8082 instead of 8081), EXPOSE line, jar name, and unprivileged user. Curl is installed via apt-get (P4 — match document-service precedent).
```dockerfile
FROM eclipse-temurin:21-jre-jammy
RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*
EXPOSE 8082
WORKDIR /app
COPY build/libs/identity-service-*.jar /app/identity-service.jar
RUN useradd -r spring
USER spring
ENTRYPOINT ["java", "-jar", "/app/identity-service.jar"]
```

- [ ] **Step 6: Add identity-service entry to `docker/docker-compose-base.yml`.** Insert below the `document-service` block; reuse the shared env-var pattern (datasource, JWT secret) + add mail env vars + signup env vars.
```yaml
    identity-service:
      build:
        context: ../services/identity-service
        dockerfile: Dockerfile
      container_name: openboxes-identity-service
      expose:
        - "8082"
      environment:
        DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
        DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
        DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
        OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
        MAIL_HOST: ${MAIL_HOST:-localhost}
        MAIL_PORT: ${MAIL_PORT:-1025}
        MAIL_USERNAME: ${MAIL_USERNAME:-}
        MAIL_PASSWORD: ${MAIL_PASSWORD:-}
        MAIL_FROM: ${MAIL_FROM:-openboxes@example.com}
        OPENBOXES_SIGNUP_ENABLED: ${OPENBOXES_SIGNUP_ENABLED:-false}
        OPENBOXES_SIGNUP_DEFAULT_ROLES: ${OPENBOXES_SIGNUP_DEFAULT_ROLES:-ROLE_BROWSER}
        OPENBOXES_SIGNUP_RECAPTCHA_ENABLED: ${OPENBOXES_SIGNUP_RECAPTCHA_ENABLED:-false}
        OPENBOXES_SIGNUP_RECAPTCHA_SECRET: ${OPENBOXES_SIGNUP_RECAPTCHA_SECRET:-}
      healthcheck:
        test: "curl --fail --silent localhost:8082/actuator/health | grep '\"status\":\"UP\"' || exit 1"
        interval: 10s
        timeout: 5s
        retries: 5
        start_period: 30s
      depends_on:
        db:
          condition: service_healthy
```

- [ ] **Step 7: Build the empty Spring Boot app and confirm it boots.**
```bash
cd services && ./gradlew :identity-service:bootJar
cd ../docker && sudo docker-compose up -d --build identity-service
sleep 25  # boot
sudo docker ps --filter name=openboxes-identity-service --format "{{.Status}}"
# Expect: Up X seconds (healthy)
sudo docker exec openboxes-identity-service wget -qO- http://localhost:8082/actuator/health
# Expect: {"status":"UP"}
```

- [ ] **Step 8: Commit.**
```bash
git add services/settings.gradle services/identity-service/ docker/docker-compose-base.yml
git commit -m "phase 2 task 2: bootstrap identity-service module + Dockerfile + compose entry"
```

### Task 3: Port JPA entities (Person/User/Role/LocationRole/RoleType/PasswordResetToken) (§8 Step 3)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/entity/{Person,User,Role,LocationRole,PasswordResetToken,RoleType}.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/repository/{PersonRepository,UserRepository,RoleRepository,LocationRoleRepository,PasswordResetTokenRepository}.java`

- [ ] **Step 1: Read the actual schema** from `grails-app/migrations/install/changelog-create-tables.groovy` lines 1184 (location_role), 1750 (person), 2938 (role), 3644 (user), 3673 (user_role) — match column types/lengths exactly.

- [ ] **Step 2: Create `RoleType.java`.** Java enum mirroring `src/main/groovy/org/pih/warehouse/core/RoleType.groovy` (40+ constants — copy each `ROLE_*` name exactly; sortOrder/displayName fields can be ported as constructor args, OR identity-service can keep just the names since it only needs the names for authz). Plan picks: names only, no sortOrder (YAGNI — identity-service doesn't expand role hierarchies; that's a Grails-side concern).
```java
package org.openboxes.identity.entity;
public enum RoleType {
    ROLE_SUPERUSER, ROLE_ADMIN, ROLE_MANAGER, ROLE_ASSISTANT, ROLE_BROWSER,
    ROLE_AUTHENTICATED, ROLE_ANONYMOUS, ROLE_FINANCE, ROLE_INVOICE,
    ROLE_PRODUCT_MANAGER, /* ... all remaining values from RoleType.groovy ... */
}
```

- [ ] **Step 3: Create `Person.java`** (base entity for JOINED inheritance).
```java
package org.openboxes.identity.entity;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "person")
@Inheritance(strategy = InheritanceType.JOINED)
public class Person {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "first_name", nullable = false, length = 255) private String firstName;
    @Column(name = "last_name", nullable = false, length = 255) private String lastName;
    @Column(length = 255) private String email;
    @Column(name = "phone_number", length = 255) private String phoneNumber;
    @Column(name = "date_created", nullable = false) private Instant dateCreated;
    @Column(name = "last_updated", nullable = false) private Instant lastUpdated;
    @Column private Boolean active;

    @PrePersist void prePersist() {
        if (id == null) id = java.util.UUID.randomUUID().toString().replace("-", "") + "00";  // 32 hex + "00" → 34 char; Grails uses CHAR(38) with hyphens? Verify against existing data shape
        Instant now = Instant.now();
        if (dateCreated == null) dateCreated = now;
        lastUpdated = now;
    }
    @PreUpdate void preUpdate() { lastUpdated = Instant.now(); }
    // getters/setters omitted for brevity
}
```
**Note:** Grails generates IDs as `UUID.randomUUID().toString()` (36-char hyphenated UUID); the column is `CHAR(38)` to allow legacy ID formats (Phase 1 P18 lesson — document_type ids are mixed integer/UUID). Use the same generation pattern Grails used; verify Task 1 Step 4 DB probe shows existing id formats so the generator matches.

- [ ] **Step 4: Create `User.java`** extending Person via `@PrimaryKeyJoinColumn`. Note `user` is a MariaDB reserved word — backticks required.
```java
package org.openboxes.identity.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.Set;

@Entity
@Table(name = "`user`")
@PrimaryKeyJoinColumn(name = "id")
public class User extends Person {
    @Column(nullable = false, unique = true, length = 255) private String username;
    @Column(nullable = false, length = 255) private String password;
    @Column(length = 255) private String locale;
    @Column(length = 255) private String timezone;
    @Column(name = "last_login_date") private Instant lastLoginDate;
    @Column(name = "warehouse_id", columnDefinition = "CHAR(38)") private String warehouseId;
    @Column(name = "manager_id", columnDefinition = "CHAR(38)") private String managerId;
    @Column(name = "remember_last_location") private Boolean rememberLastLocation;
    @Lob @Column(columnDefinition = "MEDIUMBLOB") private byte[] photo;
    @Lob @Column(name = "dashboard_config", columnDefinition = "LONGBLOB") private byte[] dashboardConfig;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_role",
               joinColumns = @JoinColumn(name = "user_id"),
               inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<LocationRole> locationRoles;
}
```

- [ ] **Step 5: Create `Role.java` + `LocationRole.java` + `PasswordResetToken.java`** per spec §7.1 (verbatim; @Version Long on Role; @Version Integer on LocationRole; password_reset_token new entity).

- [ ] **Step 6: Create Spring Data JPA repositories.**
```java
package org.openboxes.identity.repository;
import org.openboxes.identity.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
public interface UserRepository extends JpaRepository<User, String> {
    Optional<User> findByUsername(String username);
    Optional<User> findByEmail(String email);
    // Combined lookup mirroring Grails User.findByUsernameOrEmail
    @org.springframework.data.jpa.repository.Query(
        "SELECT u FROM User u WHERE u.username = :s OR u.email = :s")
    Optional<User> findByUsernameOrEmail(String s);
}
```
PersonRepository, RoleRepository, LocationRoleRepository, PasswordResetTokenRepository all extend `JpaRepository<Entity, String>`; RoleRepository adds `Optional<Role> findByRoleType(RoleType)`; PasswordResetTokenRepository adds `Optional<PasswordResetToken> findByTokenAndUsedAtIsNull(String)` and `deleteByExpiresAtBefore(Instant)`.

- [ ] **Step 7: Rebuild + restart + JPA `validate` smoke-test.**
```bash
cd services && ./gradlew :identity-service:bootJar \
  && cd ../docker && sudo docker-compose up -d --build identity-service && sleep 25
sudo docker logs openboxes-identity-service 2>&1 | grep -iE "schema validation|error|exception" | head -10
# Expect: zero schema validation errors; service Up healthy
sudo docker ps --filter name=openboxes-identity-service --format "{{.Status}}"
```
If schema validation fails (e.g., column type mismatch), iterate on entity annotations until clean.

- [ ] **Step 8: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/entity/ \
        services/identity-service/src/main/java/org/openboxes/identity/repository/
git commit -m "phase 2 task 3: port Person/User/Role/LocationRole/PasswordResetToken JPA entities + JPA repositories"
```

### Task 4: Liquibase shadow changelog (MARK_RAN existing tables + new `password_reset_token`) (§8 Step 6)

**Files:**
- Create: `services/identity-service/src/main/resources/db/changelog/identity-changelog-master.xml`
- Create: `services/identity-service/src/main/resources/db/changelog/changelog-create-table-{person,user,role,user_role,location_role}.xml` (MARK_RAN shadows)
- Create: `services/identity-service/src/main/resources/db/changelog/changelog-create-table-password-reset-token.xml` (NEW)

- [ ] **Step 1: Create master changelog including all shadow + new changelogs.**
```xml
<?xml version="1.1" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <include file="db/changelog/changelog-create-table-person.xml"/>
    <include file="db/changelog/changelog-create-table-user.xml"/>
    <include file="db/changelog/changelog-create-table-role.xml"/>
    <include file="db/changelog/changelog-create-table-user-role.xml"/>
    <include file="db/changelog/changelog-create-table-location-role.xml"/>
    <include file="db/changelog/changelog-create-table-password-reset-token.xml"/>
</databaseChangeLog>
```

- [ ] **Step 2: Create each shadow changelog** using `preConditions: tableExists` so it MARK_RANs against existing Grails-created tables. Pattern per Phase 1 T6-M2 lesson — the changeset ID is FILENAME-namespaced via Liquibase's `logicalFilePath` to coexist with Grails's prior `DATABASECHANGELOG` rows.

Example for `person`:
```xml
<?xml version="1.1" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                   logicalFilePath="services/identity-service/db/changelog/changelog-create-table-person.xml">
    <changeSet id="phase2-shadow-create-person" author="openboxes-identity">
        <preConditions onFail="MARK_RAN">
            <not><tableExists tableName="person"/></not>
        </preConditions>
        <createTable tableName="person">
            <column name="id" type="CHAR(38)"><constraints primaryKey="true" nullable="false"/></column>
            <column name="version" type="BIGINT"><constraints nullable="false"/></column>
            <column name="first_name" type="VARCHAR(255)"><constraints nullable="false"/></column>
            <column name="last_name" type="VARCHAR(255)"><constraints nullable="false"/></column>
            <column name="email" type="VARCHAR(255)"/>
            <column name="phone_number" type="VARCHAR(255)"/>
            <column name="date_created" type="DATETIME"><constraints nullable="false"/></column>
            <column name="last_updated" type="DATETIME"><constraints nullable="false"/></column>
            <column name="active" type="BIT(1)"/>
            <!-- additional columns per actual schema; verify by Task 1 Step 4 DESCRIBE output -->
        </createTable>
    </changeSet>
</databaseChangeLog>
```
Repeat for `user`, `role`, `user_role`, `location_role` — verify column shapes against `DESCRIBE` output from Task 1 Step 4.

- [ ] **Step 3: Create the NEW `password_reset_token` changelog** (no preConditions; this is a fresh table identity-service owns).
```xml
<changeSet id="phase2-create-password-reset-token" author="openboxes-identity">
    <createTable tableName="password_reset_token">
        <column name="token" type="VARCHAR(64)"><constraints primaryKey="true" nullable="false"/></column>
        <column name="user_id" type="CHAR(38)"><constraints nullable="false"
            foreignKeyName="fk_password_reset_token_user" references="user(id)"/></column>
        <column name="expires_at" type="DATETIME"><constraints nullable="false"/></column>
        <column name="used_at" type="DATETIME"/>
        <column name="created_at" type="DATETIME"><constraints nullable="false"/></column>
    </createTable>
    <createIndex indexName="idx_password_reset_token_expires_at" tableName="password_reset_token">
        <column name="expires_at"/>
    </createIndex>
</changeSet>
```

- [ ] **Step 4: Rebuild + restart + verify Liquibase MARK_RAN.**
```bash
cd services && ./gradlew :identity-service:bootJar \
  && cd ../docker && sudo docker-compose up -d --build identity-service && sleep 25
sudo docker exec openboxes-db mysql -uopenboxes -popenboxes openboxes -e "
  SELECT id, author, filename, exectype, orderexecuted FROM databasechangelog
  WHERE filename LIKE 'services/identity-service/%' ORDER BY orderexecuted;
"
# Expect: 6 rows; 5 MARK_RAN (person, user, role, user_role, location_role); 1 EXECUTED (password-reset-token)
sudo docker exec openboxes-db mysql -uopenboxes -popenboxes openboxes -e "
  DESCRIBE password_reset_token;
"
# Expect: 5 columns (token, user_id, expires_at, used_at, created_at)
```

- [ ] **Step 5: Commit.**
```bash
git add services/identity-service/src/main/resources/db/changelog/
git commit -m "phase 2 task 4: Liquibase shadow changelog (MARK_RAN identity tables + create password_reset_token)"
```

### Task 5: Custom `PasswordEncoder` + auto-migrate via `REQUIRES_NEW` (§8 Step 4 — encoder)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/password/{OpenboxesPasswordEncoder,PasswordMigrator}.java`

- [ ] **Step 1: Create `PasswordMigrator.java`** as a separate Spring `@Service` (to avoid `@Transactional` self-invocation pitfall per P29).
```java
package org.openboxes.identity.password;

import org.openboxes.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Service
public class PasswordMigrator {
    private static final Logger log = LoggerFactory.getLogger(PasswordMigrator.class);
    private final UserRepository userRepository;

    public PasswordMigrator(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void migrateToBcrypt(String userId, String newBcryptHash) {
        try {
            userRepository.findById(userId).ifPresent(u -> {
                u.setPassword(newBcryptHash);
                userRepository.save(u);
                log.info("legacy SHA-1 password migrated to BCrypt for userId={}", userId);
            });
        } catch (Exception e) {
            log.warn("password migration write failed for userId={}: {}", userId, e.getMessage());
        }
    }
}
```

- [ ] **Step 2: Create `OpenboxesPasswordEncoder.java`** — custom PasswordEncoder (NOT Spring's DelegatingPasswordEncoder; per CDR R2 §2.1 — DelegatingPasswordEncoder requires `{id}` prefixes which our legacy hashes lack).
```java
package org.openboxes.identity.password;

import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;

@Component
public class OpenboxesPasswordEncoder implements PasswordEncoder {
    private final BCryptPasswordEncoder bcrypt = new BCryptPasswordEncoder(10);
    private final PasswordMigrator migrator;
    // Identity-service tracks the current user being verified via ThreadLocal so the
    // PasswordEncoder.matches(...) signature (no userId arg) can still trigger migration.
    private static final ThreadLocal<String> CURRENT_USER_ID = new ThreadLocal<>();

    public OpenboxesPasswordEncoder(PasswordMigrator migrator) {
        this.migrator = migrator;
    }

    public static void setCurrentUserId(String userId) { CURRENT_USER_ID.set(userId); }
    public static void clearCurrentUserId() { CURRENT_USER_ID.remove(); }

    @Override
    public String encode(CharSequence rawPassword) {
        return bcrypt.encode(rawPassword);   // new + changed passwords always BCrypt
    }

    @Override
    public boolean matches(CharSequence rawPassword, String storedHash) {
        if (storedHash == null) return false;
        if (storedHash.startsWith("$2a$") || storedHash.startsWith("$2b$") || storedHash.startsWith("$2y$")) {
            return bcrypt.matches(rawPassword, storedHash);
        }
        if (sha1Base64(rawPassword.toString()).equals(storedHash)) {
            String userId = CURRENT_USER_ID.get();
            if (userId != null) {
                migrator.migrateToBcrypt(userId, bcrypt.encode(rawPassword));
            }
            return true;
        }
        // No cleartext fallback (spec §10.1, §14).
        return false;
    }

    static String sha1Base64(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA")  // "SHA" alias → SHA-1, matches PasswordCodec.groovy:18
                .digest(raw.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA algorithm unavailable", e);
        }
    }
}
```
**Note on ThreadLocal:** A cleaner design would be a `matches(rawPassword, user)` overload, but `PasswordEncoder` is a Spring interface with a fixed contract. The ThreadLocal lets the calling service (`AuthService.login`) set the userId before `passwordEncoder.matches(...)` and clear it after. Set in a try/finally to avoid leaks.

- [ ] **Step 3: Add a JUnit test for the encoder.** (Part of Task 16 integration test class; placeholder here to track.)

- [ ] **Step 4: Rebuild + restart; no functional smoke (encoder not wired into endpoints yet).**

- [ ] **Step 5: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/password/
git commit -m "phase 2 task 5: custom PasswordEncoder + PasswordMigrator (BCrypt + SHA-1 verify + auto-migrate via REQUIRES_NEW)"
```

### Task 6: `RoleTypeCache` for admin authz + `PasswordComplexityValidator` (§8 Step 4 — supporting logic)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/security/RoleTypeCache.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/password/PasswordComplexityValidator.java`

- [ ] **Step 1: Create `RoleTypeCache.java`** with refresh-on-cache-miss reload (per TWP decision; spec A31).
```java
package org.openboxes.identity.security;

import jakarta.annotation.PostConstruct;
import org.openboxes.identity.entity.Role;
import org.openboxes.identity.entity.RoleType;
import org.openboxes.identity.repository.RoleRepository;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Component
public class RoleTypeCache {
    private final RoleRepository roleRepository;
    private volatile Map<String, RoleType> cache = Map.of();

    public RoleTypeCache(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    @PostConstruct void load() {
        cache = roleRepository.findAll().stream()
            .collect(Collectors.toUnmodifiableMap(Role::getId, Role::getRoleType));
    }

    /** Returns the RoleType for a role ID. On miss, reloads cache once and retries; returns null if still missing. */
    public RoleType getRoleType(String roleId) {
        RoleType type = cache.get(roleId);
        if (type != null) return type;
        load();   // refresh-on-miss
        return cache.get(roleId);
    }

    public boolean hasAnyType(Iterable<String> roleIds, RoleType... wanted) {
        var wantedSet = java.util.Set.of(wanted);
        for (String id : roleIds) {
            RoleType t = getRoleType(id);
            if (t != null && wantedSet.contains(t)) return true;
        }
        return false;
    }
}
```

- [ ] **Step 2: Create `PasswordComplexityValidator.java`** per spec §10.2.
```java
package org.openboxes.identity.password;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class PasswordComplexityValidator {
    private static final Pattern UPPER = Pattern.compile("[A-Z]");
    private static final Pattern LOWER = Pattern.compile("[a-z]");
    private static final Pattern DIGIT = Pattern.compile("\\d");
    private static final Pattern SPECIAL = Pattern.compile("[!@#$%^&*()_+\\-=\\[\\]{};':\"\\\\|,.<>/?]");

    /** Throws PasswordTooWeakException with a list of failed rules. */
    public void validate(String password) {
        if (password == null) throw new PasswordTooWeakException("password is required");
        var failures = new java.util.ArrayList<String>();
        if (password.length() < 8) failures.add("minSize 8");
        if (password.length() > 255) failures.add("maxSize 255");
        if (!UPPER.matcher(password).find()) failures.add("at least 1 uppercase");
        if (!LOWER.matcher(password).find()) failures.add("at least 1 lowercase");
        if (!DIGIT.matcher(password).find()) failures.add("at least 1 digit");
        if (!SPECIAL.matcher(password).find()) failures.add("at least 1 special character");
        if (!failures.isEmpty()) throw new PasswordTooWeakException("password fails: " + String.join(", ", failures));
    }
}
```
Plus `PasswordTooWeakException` (`extends RuntimeException`) in the same package.

- [ ] **Step 3: Rebuild + restart.**

- [ ] **Step 4: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/security/ \
        services/identity-service/src/main/java/org/openboxes/identity/password/
git commit -m "phase 2 task 6: RoleTypeCache (refresh-on-miss) + PasswordComplexityValidator"
```

### Task 7: Auth lifecycle endpoints (login, logout, chooseLocation, me) + JWT issuance (§8 Step 5 — auth)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/controller/AuthController.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/service/{AuthService,JwtService,CookieService}.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/dto/{LoginRequest,LoginResponse,UserDto,LocationDto,MeResponse,ChooseLocationResponse}.java`

- [ ] **Step 1: Create `JwtService.java`** using jjwt 0.12.5 API.
```java
package org.openboxes.identity.service;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.openboxes.identity.entity.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;

@Service
public class JwtService {
    public static final String COOKIE_NAME = "obx_token";
    public static final long TOKEN_LIFETIME_SECONDS = 8L * 3600L;
    private final SecretKey signingKey;

    public JwtService(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public String issue(User user, String locationId, List<String> roleIds) {
        Instant now = Instant.now();
        Instant exp = now.plusSeconds(TOKEN_LIFETIME_SECONDS);
        return Jwts.builder()
            .subject(user.getId())
            .claim("loc", locationId)
            .claim("roles", roleIds == null ? List.of() : roleIds)
            .issuedAt(Date.from(now))
            .expiration(Date.from(exp))
            .signWith(signingKey)
            .compact();
    }

    public Map<String, Object> validate(String token) {
        try {
            return Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Create `CookieService.java`** that builds `Set-Cookie` headers identical to Grails JwtService.buildSetCookieHeader (spec A29).
```java
package org.openboxes.identity.service;

import org.springframework.stereotype.Service;

@Service
public class CookieService {
    public String build(String token, boolean clear) {
        long maxAge = clear ? 0 : JwtService.TOKEN_LIFETIME_SECONDS;
        String value = clear ? "" : token;
        return JwtService.COOKIE_NAME + "=" + value
            + "; HttpOnly; SameSite=Strict; Path=/; Max-Age=" + maxAge;
    }
}
```

- [ ] **Step 3: Create `AuthService.java`** with login/chooseLocation/me business logic.
```java
package org.openboxes.identity.service;

import org.openboxes.identity.entity.User;
import org.openboxes.identity.entity.LocationRole;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, OpenboxesPasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public LoginResult login(String usernameOrEmail, String rawPassword, String locationId) {
        User user = userRepository.findByUsernameOrEmail(usernameOrEmail)
            .orElseThrow(() -> new BadCredentialsException("invalid credentials"));
        if (Boolean.FALSE.equals(user.getActive())) throw new AccountDisabledException("account disabled");
        OpenboxesPasswordEncoder.setCurrentUserId(user.getId());
        try {
            if (!passwordEncoder.matches(rawPassword, user.getPassword())) {
                throw new BadCredentialsException("invalid credentials");
            }
        } finally {
            OpenboxesPasswordEncoder.clearCurrentUserId();
        }
        List<String> roleIds = user.getRoles().stream().map(r -> r.getId()).collect(Collectors.toList());
        String token = jwtService.issue(user, locationId, roleIds);
        return new LoginResult(user, locationId, roleIds, token);
    }

    @Transactional
    public ChooseLocationResult chooseLocation(String userId, String locationId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("user not found"));
        // Validate user has a LocationRole for the location OR the location's roles allow this user
        boolean allowed = user.getLocationRoles().stream()
            .anyMatch(lr -> locationId.equals(lr.getLocationId()));
        if (!allowed) throw new AccessDeniedException("user lacks LocationRole for " + locationId);

        user.setLastLoginDate(Instant.now());  // preserves Grails DashboardController:225 semantic (spec §6.1 + A20)
        userRepository.save(user);

        // Effective roles = global user.roles + LocationRoles for this location
        Set<String> effective = new java.util.HashSet<>();
        user.getRoles().forEach(r -> effective.add(r.getId()));
        user.getLocationRoles().stream()
            .filter(lr -> locationId.equals(lr.getLocationId()))
            .forEach(lr -> effective.add(lr.getRole().getId()));
        List<String> roleIds = new java.util.ArrayList<>(effective);

        String token = jwtService.issue(user, locationId, roleIds);
        return new ChooseLocationResult(user, locationId, roleIds, token);
    }

    public MeResult me(String userId, String locationId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new BadCredentialsException("user not found"));
        Set<String> effective = new java.util.HashSet<>();
        user.getRoles().forEach(r -> effective.add(r.getId()));
        if (locationId != null) {
            user.getLocationRoles().stream()
                .filter(lr -> locationId.equals(lr.getLocationId()))
                .forEach(lr -> effective.add(lr.getRole().getId()));
        }
        return new MeResult(user, locationId, new java.util.ArrayList<>(effective));
    }
}
```
Plus result records (LoginResult, ChooseLocationResult, MeResult) and exception classes (BadCredentialsException → 401, AccountDisabledException → 403, AccessDeniedException → 403).

- [ ] **Step 4: Create `AuthController.java`** with the 4 endpoints.
```java
package org.openboxes.identity.controller;

import org.openboxes.identity.dto.*;
import org.openboxes.identity.service.*;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class AuthController {
    private final AuthService authService;
    private final CookieService cookieService;

    public AuthController(AuthService authService, CookieService cookieService) {
        this.authService = authService;
        this.cookieService = cookieService;
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(@RequestBody LoginRequest req) {
        var result = authService.login(req.username(), req.password(), req.location());
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieService.build(result.token(), false))
            .body(LoginResponse.from(result));
    }

    @PostMapping("/logout")
    public ResponseEntity<Object> logout() {
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieService.build("", true))
            .body(java.util.Map.of());
    }

    @PutMapping("/chooseLocation/{id}")
    public ResponseEntity<ChooseLocationResponse> chooseLocation(@PathVariable("id") String locationId,
                                                                  @RequestAttribute("userId") String userId) {
        var result = authService.chooseLocation(userId, locationId);
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookieService.build(result.token(), false))
            .body(ChooseLocationResponse.from(result));
    }

    @GetMapping("/me")
    public ResponseEntity<MeResponse> me(@RequestAttribute("userId") String userId,
                                          @RequestAttribute(value = "locationId", required = false) String locationId) {
        var result = authService.me(userId, locationId);
        return ResponseEntity.ok(MeResponse.from(result));
    }
}
```
`@RequestAttribute("userId")` reads what the `JwtCookieAuthFilter` (Task 10) stored after validating the cookie.

- [ ] **Step 5: Create DTOs.** Plain Java records (Java 21 feature).
```java
package org.openboxes.identity.dto;
public record LoginRequest(String username, String password, String location) {}
public record LoginResponse(UserDto user, LocationDto location) { /* from(LoginResult) static factory */ }
// ... UserDto, LocationDto, MeResponse, ChooseLocationResponse similarly
```

- [ ] **Step 6: Rebuild + restart; smoke-test via `docker exec`.**
```bash
cd services && ./gradlew :identity-service:bootJar \
  && cd ../docker && sudo docker-compose up -d --build identity-service && sleep 25
# Login probe (requires JwtCookieAuthFilter from Task 10 NOT yet wired, so /login should still be reachable via permitAll once Task 10 adds it)
# For Task 7's standalone smoke: hit /login from inside the container
sudo docker exec openboxes-identity-service wget -qO- --post-data='{"username":"admin","password":"password","location":"1"}' \
  --header='Content-Type: application/json' http://localhost:8082/api/identity/login
# At this point the endpoint is reachable but unsecured (Task 10 wires authn); just verify it returns LoginResponse JSON
```

- [ ] **Step 7: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/controller/AuthController.java \
        services/identity-service/src/main/java/org/openboxes/identity/service/{AuthService,JwtService,CookieService}.java \
        services/identity-service/src/main/java/org/openboxes/identity/dto/
git commit -m "phase 2 task 7: auth lifecycle endpoints (login, logout, chooseLocation, me) + JWT issuance"
```

### Task 8: Signup endpoint + `EmailService` + welcome email + `RecaptchaService` (§8 Step 5 — signup)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/controller/SignupController.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/service/{SignupService,EmailService,RecaptchaService}.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/dto/SignupRequest.java`

- [ ] **Step 1: Create `EmailService.java`** using Spring Boot mail.
```java
package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;

@Service
public class EmailService {
    private final JavaMailSender mailSender;
    private final String from;

    public EmailService(JavaMailSender mailSender, @Value("${openboxes.mail.from}") String from) {
        this.mailSender = mailSender;
        this.from = from;
    }

    public void sendWelcomeEmail(String to, String username) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("Welcome to OpenBoxes");
        msg.setText("Hi " + username + ",\n\nYour account has been created and is pending activation.\n\n— OpenBoxes");
        mailSender.send(msg);
    }

    public void sendPasswordResetEmail(String to, String resetLink) {
        SimpleMailMessage msg = new SimpleMailMessage();
        msg.setFrom(from);
        msg.setTo(to);
        msg.setSubject("OpenBoxes password reset");
        msg.setText("To reset your password, follow this link (valid 24 hours):\n\n" + resetLink + "\n\n— OpenBoxes");
        mailSender.send(msg);
    }
}
```

- [ ] **Step 2: Create `RecaptchaService.java`** — POSTs token to Google's siteverify endpoint.
```java
package org.openboxes.identity.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class RecaptchaService {
    private final boolean enabled;
    private final String secret;
    private final RestClient http = RestClient.create();

    public RecaptchaService(@Value("${openboxes.signup.recaptcha.enabled:false}") boolean enabled,
                            @Value("${openboxes.signup.recaptcha.secret:}") String secret) {
        this.enabled = enabled;
        this.secret = secret;
    }

    /** Returns true if reCAPTCHA is disabled OR token validates successfully. */
    public boolean validate(String token) {
        if (!enabled) return true;
        if (token == null || token.isBlank()) return false;
        Map<?, ?> response = http.post()
            .uri("https://www.google.com/recaptcha/api/siteverify?secret={s}&response={t}", secret, token)
            .retrieve().body(Map.class);
        return Boolean.TRUE.equals(response.get("success"));
    }
}
```

- [ ] **Step 3: Create `SignupService.java`** — validates, hashes, creates Person+User in single transaction, assigns default roles, sends welcome email.
```java
package org.openboxes.identity.service;

import org.openboxes.identity.entity.*;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.password.PasswordComplexityValidator;
import org.openboxes.identity.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

@Service
public class SignupService {
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final PasswordComplexityValidator validator;
    private final RecaptchaService recaptchaService;
    private final EmailService emailService;
    private final boolean signupEnabled;
    private final String defaultRoles;

    public SignupService(UserRepository userRepository, RoleRepository roleRepository,
                         OpenboxesPasswordEncoder passwordEncoder, PasswordComplexityValidator validator,
                         RecaptchaService recaptchaService, EmailService emailService,
                         @Value("${openboxes.signup.enabled:false}") boolean signupEnabled,
                         @Value("${openboxes.signup.default-roles:ROLE_BROWSER}") String defaultRoles) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.passwordEncoder = passwordEncoder;
        this.validator = validator;
        this.recaptchaService = recaptchaService;
        this.emailService = emailService;
        this.signupEnabled = signupEnabled;
        this.defaultRoles = defaultRoles;
    }

    @Transactional
    public User signup(String username, String password, String firstName, String lastName,
                       String email, String phoneNumber, String recaptchaToken) {
        if (!signupEnabled) throw new SignupDisabledException("signup is disabled");
        if (!recaptchaService.validate(recaptchaToken)) throw new RecaptchaException("recaptcha failed");
        validator.validate(password);
        if (userRepository.findByUsername(username).isPresent()) throw new DuplicateUsernameException("username taken");
        if (email != null && userRepository.findByEmail(email).isPresent()) throw new DuplicateEmailException("email taken");

        User u = new User();
        u.setUsername(username);
        u.setPassword(passwordEncoder.encode(password));
        u.setFirstName(firstName);
        u.setLastName(lastName);
        u.setEmail(email);
        u.setPhoneNumber(phoneNumber);
        u.setActive(false);   // matches Grails handleSignup behavior (admin must activate)

        Set<Role> roles = new HashSet<>();
        for (String roleTypeName : Arrays.stream(defaultRoles.split(",")).map(String::trim).toList()) {
            try {
                roleRepository.findByRoleType(RoleType.valueOf(roleTypeName)).ifPresent(roles::add);
            } catch (IllegalArgumentException ignored) { /* unknown RoleType in config — skip */ }
        }
        u.setRoles(roles);
        if (!roles.isEmpty()) u.setActive(true);   // matches Grails UserService.assignDefaultRoles:120

        User saved = userRepository.save(u);
        if (saved.getEmail() != null) {
            try { emailService.sendWelcomeEmail(saved.getEmail(), saved.getUsername()); }
            catch (Exception e) { /* log but don't fail signup */ }
        }
        return saved;
    }
}
```

- [ ] **Step 4: Create `SignupController.java`.**
```java
package org.openboxes.identity.controller;

import org.openboxes.identity.dto.*;
import org.openboxes.identity.service.SignupService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class SignupController {
    private final SignupService signupService;
    public SignupController(SignupService s) { this.signupService = s; }

    @PostMapping("/signup")
    public ResponseEntity<SignupResponse> signup(@RequestBody SignupRequest req) {
        var user = signupService.signup(req.username(), req.password(), req.firstName(), req.lastName(),
                                        req.email(), req.phoneNumber(), req.recaptchaToken());
        return ResponseEntity.ok(SignupResponse.from(user));
    }
}
```

- [ ] **Step 5: Rebuild + restart; smoke-test signup (with `OPENBOXES_SIGNUP_ENABLED=true` env override).**
```bash
# Override env for smoke
sudo docker exec -e OPENBOXES_SIGNUP_ENABLED=true openboxes-identity-service \
  wget -qO- --post-data='{"username":"smoke","password":"SmokeP@ss1","firstName":"S","lastName":"M","email":"s@s.com"}' \
  --header='Content-Type: application/json' http://localhost:8082/api/identity/signup
# Expect: 200 + SignupResponse JSON (note: env override on a running container won't take; for smoke purposes, temporarily change compose default or skip and rely on Task 16's JUnit test with @TestPropertySource)
```

- [ ] **Step 6: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/{controller/SignupController.java,service/{SignupService,EmailService,RecaptchaService}.java,dto/SignupRequest.java,dto/SignupResponse.java}
git commit -m "phase 2 task 8: signup endpoint + EmailService + welcome email + RecaptchaService"
```

### Task 9: Password endpoints (change, admin-change, reset-request, reset-confirm) (§8 Step 5 — password)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/controller/PasswordController.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/service/PasswordResetService.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/dto/{ChangePasswordRequest,AdminChangePasswordRequest,ResetRequest,ResetConfirmRequest}.java`

- [ ] **Step 1: Create `PasswordResetService.java`** — generates single-use tokens, validates, persists, sends email.
```java
package org.openboxes.identity.service;

import org.openboxes.identity.entity.*;
import org.openboxes.identity.password.OpenboxesPasswordEncoder;
import org.openboxes.identity.password.PasswordComplexityValidator;
import org.openboxes.identity.repository.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;

@Service
public class PasswordResetService {
    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final OpenboxesPasswordEncoder passwordEncoder;
    private final PasswordComplexityValidator validator;
    private final EmailService emailService;
    private final String grailsBaseUrl;
    private final SecureRandom rng = new SecureRandom();

    public PasswordResetService(UserRepository u, PasswordResetTokenRepository t,
                                OpenboxesPasswordEncoder e, PasswordComplexityValidator v,
                                EmailService email, @Value("${openboxes.grails-base-url:http://localhost/openboxes}") String url) {
        this.userRepository = u; this.tokenRepository = t; this.passwordEncoder = e;
        this.validator = v; this.emailService = email; this.grailsBaseUrl = url;
    }

    @Transactional
    public void requestReset(String email) {
        // Always silently succeed (don't leak whether email exists)
        userRepository.findByEmail(email).ifPresent(user -> {
            if (Boolean.TRUE.equals(user.getActive())) {
                byte[] bytes = new byte[32];
                rng.nextBytes(bytes);
                String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
                PasswordResetToken prt = new PasswordResetToken();
                prt.setToken(token);
                prt.setUser(user);
                prt.setCreatedAt(Instant.now());
                prt.setExpiresAt(Instant.now().plus(24, ChronoUnit.HOURS));
                tokenRepository.save(prt);
                String link = grailsBaseUrl + "/auth/resetPassword?token=" + token;
                try { emailService.sendPasswordResetEmail(user.getEmail(), link); }
                catch (Exception ignored) { /* silent fail per always-200 design */ }
            }
        });
    }

    @Transactional
    public void confirmReset(String token, String newPassword) {
        validator.validate(newPassword);
        PasswordResetToken prt = tokenRepository.findByTokenAndUsedAtIsNull(token)
            .orElseThrow(() -> new InvalidTokenException("invalid or already-used token"));
        if (prt.getExpiresAt().isBefore(Instant.now())) throw new InvalidTokenException("token expired");
        User user = prt.getUser();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        prt.setUsedAt(Instant.now());
        tokenRepository.save(prt);
    }
}
```

- [ ] **Step 2: Extend `AuthService.java` (from Task 7) with `changePassword(userId, currentPassword, newPassword)` and `adminChangePassword(callerUserId, targetUserId, newPassword)`.** Both verify, hash, save. Admin variant uses `RoleTypeCache.hasAnyType(callerRoleIds, ROLE_ADMIN, ROLE_SUPERUSER)` (inject `RoleTypeCache` via constructor).

- [ ] **Step 3: Create `PasswordController.java`.**
```java
package org.openboxes.identity.controller;

import org.openboxes.identity.dto.*;
import org.openboxes.identity.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class PasswordController {
    private final AuthService authService;
    private final PasswordResetService resetService;
    public PasswordController(AuthService a, PasswordResetService r) {
        this.authService = a; this.resetService = r;
    }

    @PostMapping("/password/change")
    public ResponseEntity<Object> changeSelf(@RequestBody ChangePasswordRequest req,
                                              @RequestAttribute("userId") String userId) {
        authService.changePassword(userId, req.currentPassword(), req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }

    @PutMapping("/users/{id}/password")
    public ResponseEntity<Object> changeAdmin(@PathVariable("id") String targetUserId,
                                                @RequestBody AdminChangePasswordRequest req,
                                                @RequestAttribute("userId") String callerUserId,
                                                @RequestAttribute("roleIds") java.util.List<String> callerRoleIds) {
        authService.adminChangePassword(callerUserId, callerRoleIds, targetUserId, req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }

    @PostMapping("/password/reset-request")
    public ResponseEntity<Object> requestReset(@RequestBody ResetRequest req) {
        resetService.requestReset(req.email());
        return ResponseEntity.ok(java.util.Map.of());   // always 200
    }

    @PostMapping("/password/reset/{token}")
    public ResponseEntity<Object> confirmReset(@PathVariable("token") String token,
                                                 @RequestBody ResetConfirmRequest req) {
        resetService.confirmReset(token, req.newPassword());
        return ResponseEntity.ok(java.util.Map.of());
    }
}
```

- [ ] **Step 4: Rebuild + restart; defer smoke to Task 16 JUnit.**

- [ ] **Step 5: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/{controller/PasswordController.java,service/PasswordResetService.java,service/AuthService.java,dto/}
git commit -m "phase 2 task 9: password endpoints (change, admin-change, reset-request, reset-confirm) + PasswordResetService"
```

### Task 10: User-lookup endpoint + Spring Security filter chain + `JwtCookieAuthFilter` (§8 Step 5 — lookup + Step 7 — JWT validation)

**Files:**
- Create: `services/identity-service/src/main/java/org/openboxes/identity/controller/UserLookupController.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/security/{JwtCookieAuthFilter,SecurityConfig}.java`
- Create: `services/identity-service/src/main/java/org/openboxes/identity/dto/UserLookupResponse.java`

- [ ] **Step 1: Create `JwtCookieAuthFilter.java`** mirroring document-service's (substitute identity-service's JwtService for direct jjwt parsing for DRY).
```java
package org.openboxes.identity.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.openboxes.identity.service.JwtService;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    public JwtCookieAuthFilter(JwtService jwt) { this.jwtService = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.COOKIE_NAME.equals(c.getName())) {
                    Map<String, Object> claims = jwtService.validate(c.getValue());
                    if (claims != null) {
                        String userId = (String) claims.get("sub");
                        String locationId = (String) claims.get("loc");
                        @SuppressWarnings("unchecked")
                        List<String> roleIds = (List<String>) claims.getOrDefault("roles", List.of());
                        req.setAttribute("userId", userId);
                        req.setAttribute("locationId", locationId);
                        req.setAttribute("roleIds", roleIds);
                        var authorities = roleIds.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, authorities));
                    }
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 2: Create `SecurityConfig.java`** mirroring document-service; permit unauthenticated access to `/api/identity/login`, `/api/identity/signup`, `/api/identity/password/reset-request`, `/api/identity/password/reset/**`, `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`.
```java
package org.openboxes.identity.security;

import org.springframework.context.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain filterChain(HttpSecurity http, JwtCookieAuthFilter jwtFilter) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(a -> a
                .requestMatchers(
                    "/api/identity/login",
                    "/api/identity/signup",
                    "/api/identity/password/reset-request",
                    "/api/identity/password/reset/**",
                    "/actuator/health",
                    "/v3/api-docs/**",
                    "/swagger-ui/**", "/swagger-ui.html").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)
            .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
        return http.build();
    }
}
```

- [ ] **Step 3: Create `UserLookupController.java`.**
```java
package org.openboxes.identity.controller;

import org.openboxes.identity.dto.UserLookupResponse;
import org.openboxes.identity.repository.UserRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/identity")
public class UserLookupController {
    private final UserRepository userRepository;
    public UserLookupController(UserRepository u) { this.userRepository = u; }

    @GetMapping("/users/{id}")
    public ResponseEntity<UserLookupResponse> get(@PathVariable String id) {
        return userRepository.findById(id)
            .map(UserLookupResponse::from)
            .map(ResponseEntity::ok)
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: Add a global exception handler** that maps the identity-service exceptions to HTTP statuses per spec §6.
```java
@RestControllerAdvice
public class GlobalExceptionHandler {
    @ExceptionHandler(BadCredentialsException.class)
    public ResponseEntity<?> badCreds() { return ResponseEntity.status(401).body(Map.of("error","invalid credentials")); }
    @ExceptionHandler({AccountDisabledException.class, AccessDeniedException.class, RecaptchaException.class, SignupDisabledException.class})
    public ResponseEntity<?> forbidden(Exception e) { return ResponseEntity.status(403).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({DuplicateUsernameException.class, DuplicateEmailException.class})
    public ResponseEntity<?> conflict(Exception e) { return ResponseEntity.status(409).body(Map.of("error", e.getMessage())); }
    @ExceptionHandler({PasswordTooWeakException.class, InvalidTokenException.class})
    public ResponseEntity<?> badRequest(Exception e) { return ResponseEntity.status(400).body(Map.of("error", e.getMessage())); }
}
```

- [ ] **Step 5: Rebuild + restart; smoke-test the secured + unsecured endpoints.**
```bash
cd services && ./gradlew :identity-service:bootJar \
  && cd ../docker && sudo docker-compose up -d --build identity-service && sleep 25
# Unauth → 401
sudo docker exec openboxes-identity-service wget -qO- --server-response http://localhost:8082/api/identity/me 2>&1 | head -5
# Login (permitAll)
sudo docker exec openboxes-identity-service wget -qO- --post-data='{"username":"admin","password":"password","location":"1"}' \
  --header='Content-Type: application/json' --save-headers http://localhost:8082/api/identity/login | head -20
```

- [ ] **Step 6: Commit.**
```bash
git add services/identity-service/src/main/java/org/openboxes/identity/{controller/UserLookupController.java,security/,dto/UserLookupResponse.java}
git commit -m "phase 2 task 10: user-lookup endpoint + Spring Security filter chain + JwtCookieAuthFilter"
```

### Task 11: nginx `/api/identity` routing (§8 Step 9)

**Files:**
- Modify: `docker/nginx/conf.d/app.conf`

- [ ] **Step 1: Add the `/api/identity` location block ABOVE `/api/documents`.**
```nginx
    location /api/identity {
        proxy_pass http://identity-service:8082;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Cookie $http_cookie;
    }

    location /api/documents {
        # ... existing block ...
    }
```

- [ ] **Step 2: Reload nginx.**
```bash
sudo docker exec openboxes-nginx nginx -t
sudo docker exec openboxes-nginx nginx -s reload
```

- [ ] **Step 3: Smoke-test via host.**
```bash
JAR=$(mktemp); curl -s -c "$JAR" -X POST http://localhost/api/identity/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password","location":"1"}' -w "\nHTTP %{http_code}\n"
# Expect: HTTP 200 + LoginResponse JSON + obx_token cookie in $JAR
curl -s -b "$JAR" http://localhost/api/identity/me -w "\nHTTP %{http_code}\n"
# Expect: HTTP 200 + MeResponse JSON
rm -f "$JAR"
```

- [ ] **Step 4: Commit.**
```bash
git add docker/nginx/conf.d/app.conf
git commit -m "phase 2 task 11: nginx /api/identity routing block above /api/documents"
```

### Task 12: Grails `IdentityClient` service + Spring DI wiring (§8 Step 8b — client)

**Files:**
- Create: `grails-app/services/org/pih/warehouse/auth/IdentityClient.groovy`
- Modify: `grails-app/conf/spring/resources.groovy:23-24` — add `identityClient(...)` line

- [ ] **Step 1: Create `IdentityClient.groovy`** mirroring `DocumentClient.groovy` pattern (long-lived RestTemplate, 5s/10s timeouts, drain-and-disconnect).
```groovy
package org.pih.warehouse.auth

import org.springframework.beans.factory.annotation.Value
import org.springframework.http.*
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestTemplate

class IdentityClient {
    private final RestTemplate restTemplate

    @Value('${openboxes.identity.base-url:http://identity-service:8082}')
    String identityBaseUrl

    IdentityClient() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory()
        factory.connectTimeout = 5000   // ms
        factory.readTimeout = 10000     // ms
        this.restTemplate = new RestTemplate(factory)
    }

    /** Returns [body: Map, setCookieHeader: String]. Throws BadCredentialsException/AccountDisabledException on 401/403. */
    Map login(String username, String password, String locationId) {
        try {
            HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
            HttpEntity<Map> req = new HttpEntity<>([username: username, password: password, location: locationId], h)
            ResponseEntity<Map> resp = restTemplate.postForEntity("${identityBaseUrl}/api/identity/login", req, Map)
            return [body: resp.body, setCookieHeader: resp.headers.getFirst('Set-Cookie')]
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BadCredentialsException(e.responseBodyAsString)
        } catch (HttpClientErrorException.Forbidden e) {
            throw new AccountDisabledException(e.responseBodyAsString)
        }
    }

    String logout(String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        ResponseEntity<Map> resp = restTemplate.exchange("${identityBaseUrl}/api/identity/logout",
            HttpMethod.POST, new HttpEntity<>(h), Map)
        return resp.headers.getFirst('Set-Cookie')   // the clear-cookie header
    }

    Map chooseLocation(String locationId, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        ResponseEntity<Map> resp = restTemplate.exchange("${identityBaseUrl}/api/identity/chooseLocation/${locationId}",
            HttpMethod.PUT, new HttpEntity<>(h), Map)
        return [body: resp.body, setCookieHeader: resp.headers.getFirst('Set-Cookie')]
    }

    Map me(String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.add('Cookie', "obx_token=${obxTokenCookie}")
        return restTemplate.exchange("${identityBaseUrl}/api/identity/me",
            HttpMethod.GET, new HttpEntity<>(h), Map).body
    }

    Map signup(Map signupData) {
        try {
            HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
            return restTemplate.postForEntity("${identityBaseUrl}/api/identity/signup",
                new HttpEntity<>(signupData, h), Map).body
        } catch (HttpClientErrorException.Forbidden e) {
            throw new SignupDisabledException(e.responseBodyAsString)
        } catch (HttpClientErrorException.Conflict e) {
            throw new DuplicateUsernameException(e.responseBodyAsString)
        } catch (HttpClientErrorException.BadRequest e) {
            throw new ValidationException(e.responseBodyAsString)
        }
    }

    void changePassword(String currentPassword, String newPassword, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON; h.add('Cookie', "obx_token=${obxTokenCookie}")
        try {
            restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/change",
                new HttpEntity<>([currentPassword: currentPassword, newPassword: newPassword], h), Map)
        } catch (HttpClientErrorException.Unauthorized e) {
            throw new BadCredentialsException(e.responseBodyAsString)
        } catch (HttpClientErrorException.BadRequest e) {
            throw new PasswordTooWeakException(e.responseBodyAsString)
        }
    }

    void changeUserPasswordAsAdmin(String userId, String newPassword, String obxTokenCookie) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON; h.add('Cookie', "obx_token=${obxTokenCookie}")
        restTemplate.exchange("${identityBaseUrl}/api/identity/users/${userId}/password",
            HttpMethod.PUT, new HttpEntity<>([newPassword: newPassword], h), Map)
    }

    void requestPasswordReset(String email) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
        restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/reset-request",
            new HttpEntity<>([email: email], h), Map)
        // always 200; never throws
    }

    void resetPassword(String token, String newPassword) {
        HttpHeaders h = new HttpHeaders(); h.contentType = MediaType.APPLICATION_JSON
        try {
            restTemplate.postForEntity("${identityBaseUrl}/api/identity/password/reset/${token}",
                new HttpEntity<>([newPassword: newPassword], h), Map)
        } catch (HttpClientErrorException.BadRequest e) {
            throw new InvalidTokenException(e.responseBodyAsString)
        }
    }
}
```
Plus the new exception classes (BadCredentialsException, AccountDisabledException, SignupDisabledException, DuplicateUsernameException, ValidationException, PasswordTooWeakException, InvalidTokenException) in the same package as Groovy classes extending RuntimeException.

- [ ] **Step 2: Register the bean in `resources.groovy:24`** (after `documentClient(...)`).
```groovy
    documentClient(org.pih.warehouse.core.DocumentClient)
    identityClient(org.pih.warehouse.auth.IdentityClient)
```

- [ ] **Step 3: Rebuild Grails + restart.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose up -d --build app && sleep 60
sudo docker logs openboxes-app 2>&1 | grep -iE "identityClient|IdentityClient" | head -5
# Expect: bean wired without errors
```

- [ ] **Step 4: Commit.**
```bash
git add grails-app/services/org/pih/warehouse/auth/IdentityClient.groovy \
        grails-app/conf/spring/resources.groovy
git commit -m "phase 2 task 12: IdentityClient service + Spring DI wiring"
```

### Task 13: Grails shim controllers (§8 Step 8b — shims)

**Files:**
- Modify: `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy:43-67,258-272` — `login`, `chooseLocation`, `logout` become shims
- Modify: `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy:69-153,178-222` — `handleLogin`, `logout`, `handleSignup` become shims; add `forgotPassword`, `resetPassword` actions
- Modify: `grails-app/controllers/org/pih/warehouse/user/UserController.groovy:278-299` — `changePassword` routes to admin or self-edit endpoint
- Modify: `grails-app/views/user/edit.gsp:132-160` — add `currentPassword` input field inside `#password-tab`

- [ ] **Step 1: Replace `ApiController.login`** (lines 43-61). Each shim follows the same pattern: forward request, set `session.user` + `session.warehouse` from response, forward `Set-Cookie`.
```groovy
def identityClient

def login() {
    def username = request.JSON.username
    def password = request.JSON.password
    def locationId = request.JSON.location
    try {
        def result = identityClient.login(username, password, locationId)
        // Populate session for backward compatibility with ~54 session.user readers
        session.user = User.findByUsernameOrEmail(result.body.user.username, result.body.user.username)
        if (result.body.location) session.warehouse = Location.get(result.body.location.id)
        response.setHeader('Set-Cookie', result.setCookieHeader)
        render([status: 200, contentType: 'application/json', text: (result.body as grails.converters.JSON).toString()])
    } catch (BadCredentialsException e) {
        render([status: 401, text: 'Authentication failed'])
    } catch (AccountDisabledException e) {
        render([status: 403, text: 'Account disabled'])
    }
}
```

- [ ] **Step 2: Replace `ApiController.chooseLocation`** (lines 63-72).
```groovy
def chooseLocation() {
    def tokenCookie = request.cookies?.find { it.name == 'obx_token' }?.value
    if (!tokenCookie) { render([status: 401, text: 'No session']); return }
    try {
        def result = identityClient.chooseLocation(params.id, tokenCookie)
        session.warehouse = Location.get(result.body.location.id)
        response.setHeader('Set-Cookie', result.setCookieHeader)
        render([status: 200, text: "User ${session.user} is now logged into ${result.body.location.name}"])
    } catch (org.springframework.web.client.HttpClientErrorException.NotFound e) {
        throw new ObjectNotFoundException(params.id, Location.class.toString())
    }
}
```

- [ ] **Step 3: Replace `ApiController.logout`** (lines 258-272).
```groovy
def logout() {
    def tokenCookie = request.cookies?.find { it.name == 'obx_token' }?.value
    String clearHeader = tokenCookie ? identityClient.logout(tokenCookie) : 'obx_token=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'
    response.setHeader('Set-Cookie', clearHeader)
    if (session.impersonateUserId) {
        session.user = User.get(session.activeUserId); session.impersonateUserId = null; session.activeUserId = null
        render([status: 200, text: "Logout was successful"])
    } else {
        session.invalidate()
        render([status: 200, text: "Logout was successful"])
    }
}
```

- [ ] **Step 4: Replace `AuthController.handleLogin`** (lines 69-135) — same shape, populates session.user/userName/warehouse from identity-service response, redirects to `session.targetUri` or dashboard on success, renders login view with errors on failure.
```groovy
def identityClient

def handleLogin() {
    if ("POST".equalsIgnoreCase(request.getMethod())) {
        try {
            def result = identityClient.login(params.username, params.password, null)
            def userInstance = User.findByUsernameOrEmail(params.username, params.username)
            session.user = userInstance
            session.userName = userInstance.username
            if (userInstance?.warehouse && userInstance?.rememberLastLocation) session.warehouse = userInstance.warehouse
            response.setHeader('Set-Cookie', result.setCookieHeader)
            if (session?.targetUri) { redirect(uri: session.targetUri); session.targetUri = null; return }
            redirect(controller: 'dashboard', action: 'index')
        } catch (BadCredentialsException e) {
            flash.message = "${warehouse.message(code: 'auth.incorrectPassword.label', args: [params.username])}"
            def userInstance = new User(username: params['username'])
            userInstance.errors.rejectValue("version", "default.authentication.failure",
                [warehouse.message(code: 'user.label')] as Object[], "${warehouse.message(code: 'auth.unableToAuthenticateUser.message')}")
            render(view: "login", model: [userInstance: userInstance])
        } catch (AccountDisabledException e) {
            flash.message = "${warehouse.message(code: 'auth.accountRequestUnderReview.message')}"
            redirect(controller: 'auth', action: 'login')
        }
    }
}
```

- [ ] **Step 5: Replace `AuthController.logout`** (lines 141-153) — same pattern as ApiController.logout but redirects to login.

- [ ] **Step 6: Replace `AuthController.handleSignup`** (lines 178-222) — shim to `identityClient.signup(...)`; handles SignupDisabledException, DuplicateUsernameException, ValidationException by rendering signup view with errors.

- [ ] **Step 7: Add `forgotPassword` + `resetPassword` actions to `AuthController.groovy`** — GET renders GSP form; POST shims to identity-service.
```groovy
def forgotPassword() {   // GET renders form; POST shims to identity-service
    if ("POST".equalsIgnoreCase(request.getMethod())) {
        identityClient.requestPasswordReset(params.email)
        flash.message = "${warehouse.message(code: 'auth.passwordResetRequestSent.message', default: 'If that email exists, a reset link has been sent.')}"
        redirect(action: 'login')
    }
    // GET: just render forgotPassword.gsp
}

def resetPassword() {   // GET renders form (with token in model); POST shims to identity-service
    if ("POST".equalsIgnoreCase(request.getMethod())) {
        try {
            identityClient.resetPassword(params.token, params.newPassword)
            flash.message = "${warehouse.message(code: 'auth.passwordResetSuccess.message', default: 'Password reset.')}"
            redirect(action: 'login')
        } catch (InvalidTokenException e) {
            flash.error = "Reset link invalid or expired."
            redirect(action: 'login')
        } catch (PasswordTooWeakException e) {
            flash.error = "Password does not meet complexity requirements."
            render(view: 'resetPassword', model: [token: params.token])
        }
        return
    }
    render(view: 'resetPassword', model: [token: params.token])
}
```

- [ ] **Step 8: Update `UserController.changePassword`** (lines 278-299) — routes to admin or self-edit endpoint.
```groovy
def changePassword() {
    def tokenCookie = request.cookies?.find { it.name == 'obx_token' }?.value
    User user = userGormService.get(params?.id)
    if (!user) {
        flash.message = "${warehouse.message(code: 'default.not.found.message', args: [warehouse.message(code: 'user.label'), params.id])}"
        redirect(action: "list"); return
    }
    try {
        if (user.id == session.user?.id) {
            // Self-edit path — requires currentPassword
            identityClient.changePassword(params?.currentPassword, params?.password, tokenCookie)
        } else {
            // Admin-edit path
            identityClient.changeUserPasswordAsAdmin(user.id, params?.password, tokenCookie)
        }
        flash.message = "${warehouse.message(code: 'default.updated.message', args: [warehouse.message(code: 'user.label'), user.id])}"
        redirect(action: "edit", id: user.id)
    } catch (BadCredentialsException e) {
        flash.error = "Current password is incorrect."
        redirect(action: "edit", id: user.id)
    } catch (PasswordTooWeakException e) {
        flash.error = e.message
        redirect(action: "edit", id: user.id)
    }
}
```

- [ ] **Step 9: Add `currentPassword` input field to `edit.gsp:132-160`** inside `#password-tab`, ABOVE the existing `password` field. Pattern:
```html
<tr class="prop">
    <td valign="top" class="name"><label for="currentPassword"><warehouse:message code="user.currentPassword.label" default="Current Password"/></label></td>
    <td valign="top" class="value">
        <input type="password" style="display:none"/>
        <g:passwordField name="currentPassword" value="" class="text" size="40"/>
    </td>
</tr>
```
**Note:** Only shown when target user == current user (self-edit). Use `<g:if test="${userInstance?.id == session?.user?.id}">...</g:if>` to wrap.

- [ ] **Step 10: Rebuild Grails + restart + smoke-test all 7 shimmed actions.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose up -d --build app && sleep 60
sudo docker exec openboxes-nginx nginx -s reload
# Re-run Task 1 Step 3 probes — all should still succeed, now via identity-service
```

- [ ] **Step 11: Commit.**
```bash
git add grails-app/controllers/org/pih/warehouse/api/ApiController.groovy \
        grails-app/controllers/org/pih/warehouse/user/{AuthController,UserController}.groovy \
        grails-app/views/user/edit.gsp
git commit -m "phase 2 task 13: Grails shim controllers (ApiController + AuthController + UserController.changePassword) + currentPassword input field"
```

### Task 14: Grails `JwtService` reduction + `DashboardController:225` delete + `UserSignupEventService` delete + (conditional) `UserSignupEvent` delete (§8 Step 4 — Grails-side)

**Files:**
- Modify: `grails-app/services/org/pih/warehouse/auth/JwtService.groovy` — delete `issue(User, Location)` (lines 31-43) + `buildSetCookieHeader(String, boolean)` (lines 57-62)
- Modify: `grails-app/controllers/org/pih/warehouse/user/DashboardController.groovy:223-228` — delete `user.lastLoginDate = new Date()` + `user.save(flush: true)` (preserve `session.user = user` reassignment)
- Modify: `grails-app/services/org/pih/warehouse/core/UserService.groovy:478-499` — delete `authenticate(...)` + `authenticateUsingDatabase(...)` (0 callers per P41)
- Delete: `grails-app/services/org/pih/warehouse/auth/UserSignupEventService.groovy` (P14 corrected path)
- Conditionally Delete: `src/main/groovy/org/pih/warehouse/auth/UserSignupEvent.groovy` (P15 corrected path; only if grep shows no other consumers)

- [ ] **Step 1: Verify zero callers of soon-to-be-deleted symbols.**
```bash
grep -rn "jwtService\.issue\|JwtService\.issue\|JwtService\.buildSetCookieHeader" grails-app/ | grep -v "JwtService\.groovy"
# Expect: zero hits (Task 13 shims no longer call them)
grep -rn "userService\.authenticate\b\|userService\.authenticateUsingDatabase\b" grails-app/
# Expect: zero hits
grep -rn "UserSignupEvent\b" grails-app/ src/main/ | grep -v "UserSignupEvent.groovy"
# Expect: 1 hit at most (in UserSignupEventService.groovy — which is also being deleted)
```
If any non-zero count, STOP — Task 13 missed a shim conversion.

- [ ] **Step 2: Edit `JwtService.groovy`** — delete `issue(User, Location)` method (lines 31-43) and `buildSetCookieHeader(String, boolean)` static method (lines 57-62). Keep `COOKIE_NAME`, `TOKEN_LIFETIME_SECONDS`, `getSigningKey()`, `validate(String)`. Remove now-unused imports (`User`, `Location`, `SignatureAlgorithm`, `Date`).

- [ ] **Step 3: Edit `DashboardController.groovy:223-228`** — delete the lastLoginDate write.
```groovy
// Before:
if (user) {
    //userInstance.rememberLastLocation = Boolean.valueOf(params.rememberLastLocation)
    user.lastLoginDate = new Date()    // DELETE this line
    user.save(flush: true)              // DELETE this line
    session.user = user
}
// After:
if (user) {
    //userInstance.rememberLastLocation = Boolean.valueOf(params.rememberLastLocation)
    session.user = user
}
```

- [ ] **Step 4: Edit `UserService.groovy:478-499`** — delete `authenticate(username, password)` + `authenticateUsingDatabase(username, password)` methods. Keep everything else (`assignDefaultRoles`, `hasHighestRole`, etc.).

- [ ] **Step 5: Delete `UserSignupEventService.groovy`** (P14 corrected path).
```bash
git rm grails-app/services/org/pih/warehouse/auth/UserSignupEventService.groovy
```

- [ ] **Step 6: Conditionally delete `UserSignupEvent.groovy`** (P15 corrected path) — only if Step 1 grep showed zero remaining consumers.
```bash
# Re-grep AFTER UserSignupEventService deletion
grep -rn "UserSignupEvent\b" grails-app/ src/main/
# Expect: 1 hit at most (in UserSignupEvent.groovy itself — the class definition)
# If only self-reference, delete:
git rm src/main/groovy/org/pih/warehouse/auth/UserSignupEvent.groovy
# Otherwise: keep the class (some other consumer still references it; flag to user for follow-up)
```

- [ ] **Step 7: Rebuild Grails + restart + verify zero compile errors + behaviors intact.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose up -d --build app && sleep 60
sudo docker logs openboxes-app 2>&1 | grep -iE "compilation failed|error|exception" | head -5
# Re-run smoke probes (Task 1 Step 3) — login + chooseLocation + dashboard load
```

- [ ] **Step 8: Commit.**
```bash
git add grails-app/services/org/pih/warehouse/{auth/JwtService.groovy,core/UserService.groovy} \
        grails-app/controllers/org/pih/warehouse/user/DashboardController.groovy
git commit -m "phase 2 task 14: JwtService reduction (delete issue + buildSetCookieHeader) + DashboardController:225 lastLoginDate delete + UserService.authenticate delete + UserSignupEventService delete"
```

### Task 15: React URL updates + forgot-password GSP flow (§8 Step 8a + new feature)

**Files:**
- Modify: `src/js/components/LoginModal.jsx:24,41` — URL updates + verb change
- Modify: `src/js/actions/index.js:220` — URL update + verb change
- Modify: `src/js/components/LoginModal.jsx` — add "Forgot password?" link
- Create: `grails-app/views/auth/forgotPassword.gsp`, `grails-app/views/auth/resetPassword.gsp`

- [ ] **Step 1: Update LoginModal URLs.**
```diff
- const url = '/api/login';
+ const url = '/api/identity/login';
```
```diff
- const url = `/api/chooseLocation/${this.props.currentLocationId}`;
- return apiClient.post(url);
+ const url = `/api/identity/chooseLocation/${this.props.currentLocationId}`;
+ return apiClient.put(url);
```

- [ ] **Step 2: Update actions/index.js URL + verb.**
```diff
- const url = `/api/chooseLocation/${location.id}`;
- ... apiClient.post(url)
+ const url = `/api/identity/chooseLocation/${location.id}`;
+ ... apiClient.put(url)
```
(Verify exact existing call shape via `grep -n -A2 "chooseLocation" src/js/actions/index.js`.)

- [ ] **Step 3: Add "Forgot password?" link** to LoginModal.jsx below the password field.
```jsx
<div className="px-3 pb-2">
  <a href="/openboxes/auth/forgotPassword" className="text-sm">
    {this.props.translate('react.default.forgotPassword.label', 'Forgot password?')}
  </a>
</div>
```

- [ ] **Step 4: Create `forgotPassword.gsp`** (basic email-input form posting to /openboxes/auth/forgotPassword).
```html
<%@ page contentType="text/html;charset=UTF-8" %>
<html><head><title><g:message code="auth.forgotPassword.label" default="Forgot Password"/></title></head>
<body>
<g:form action="forgotPassword" method="post">
  <h2><g:message code="auth.forgotPassword.label" default="Forgot Password"/></h2>
  <p><g:message code="auth.forgotPassword.description" default="Enter your email to receive a reset link."/></p>
  <label><g:message code="user.email.label" default="Email"/>: <g:textField name="email"/></label>
  <g:submitButton name="submit" value="${message(code: 'default.button.submit.label', default: 'Submit')}"/>
</g:form>
</body></html>
```

- [ ] **Step 5: Create `resetPassword.gsp`** (token + new-password form, token comes from URL `?token=...`).
```html
<%@ page contentType="text/html;charset=UTF-8" %>
<html><head><title><g:message code="auth.resetPassword.label" default="Reset Password"/></title></head>
<body>
<g:form action="resetPassword" method="post">
  <h2><g:message code="auth.resetPassword.label" default="Reset Password"/></h2>
  <g:hiddenField name="token" value="${token}"/>
  <label><g:message code="user.newPassword.label" default="New Password"/>: <g:passwordField name="newPassword"/></label>
  <g:submitButton name="submit" value="${message(code: 'default.button.submit.label', default: 'Submit')}"/>
</g:form>
</body></html>
```

- [ ] **Step 6: Rebuild + restart; smoke-test React login flow + GSP forgot-password.**
```bash
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose up -d --build app && sleep 60
# React: open http://localhost in browser, log in via LoginModal
# Verify Network tab shows POST /api/identity/login and PUT /api/identity/chooseLocation/...
# GSP: open http://localhost/openboxes/auth/forgotPassword, submit email, verify redirect with flash message
```

- [ ] **Step 7: Commit.**
```bash
git add src/js/components/LoginModal.jsx src/js/actions/index.js \
        grails-app/views/auth/forgotPassword.gsp grails-app/views/auth/resetPassword.gsp
git commit -m "phase 2 task 15: React URL updates (LoginModal + actions/index.js) + forgot-password GSP flow"
```

### Task 16: JUnit + TestContainers integration tests (§8 Step 11 — JUnit)

**Files:**
- Create: `services/identity-service/src/test/java/org/openboxes/identity/IdentityServiceIntegrationTest.java`
- Create: `services/identity-service/src/test/resources/test-data/seed.sql` (Person/User/Role rows for fixtures incl. SHA-1 password + BCrypt password)

- [ ] **Step 1: Create the test class** mirroring `services/document-service/.../DocumentServiceIntegrationTest.java` pattern. Includes `@SpringBootTest`, `@Testcontainers`, `@DynamicPropertySource` injecting MariaDB container's JDBC URL, `MockBean` for `JavaMailSender`. Test method names per spec §11.1:
  - `loginGoodCreds_returns200AndSetsCookie`
  - `loginBadCreds_returns401`
  - `loginDisabledAccount_returns403`
  - `logout_clearsCookie`
  - `chooseLocation_reissuesJwtAndUpdatesLastLoginDate`
  - `chooseLocation_404OnBadLocationId`
  - `me_returnsUserAndLocationAndEffectiveRoles`
  - `signup_createsUserAndPersonAndSendsEmail` (asserts JavaMailSender mock invoked)
  - `signup_409OnDuplicateUsername`
  - `signup_400OnWeakPassword`
  - `passwordChange_verifiesCurrentBeforeSettingNew`
  - `passwordChange_400OnWeakNew`
  - `passwordChange_401OnWrongCurrent`
  - `passwordResetRequest_alwaysReturns200`
  - `passwordResetConfirm_validatesTokenAndUpdatesHash`
  - `passwordResetConfirm_400OnUsedToken` / `_400OnExpiredToken`
  - `sha1AutoMigrate_acceptsSha1ThenStoresBcrypt` (asserts post-login the user row's password starts with `$2a$`)
  - `cleartextStored_rejected` (seed user with literal "password" → assert login returns 401)
  - `adminEndpoint_403WhenCallerNotAdmin` / `200WhenCallerIsAdmin` (uses RoleTypeCache)

- [ ] **Step 2: Create `seed.sql`** with fixture persons/users:
  - admin user with BCrypt password
  - legacy user with SHA-1+Base64 password (use `OpenboxesPasswordEncoder.sha1Base64("legacy")` to compute)
  - cleartext user with literal "cleartext" stored
  - disabled user (active=false on Person row)
  - role: ROLE_ADMIN, ROLE_BROWSER

- [ ] **Step 3: Run tests.**
```bash
cd services && sudo -E ./gradlew :identity-service:test --info
# Expect: 100% pass
```

- [ ] **Step 4: Commit.**
```bash
git add services/identity-service/src/test/
git commit -m "phase 2 task 16: JUnit + TestContainers integration tests (18+ methods per spec §11.1)"
```

### Task 17: Playwright E2E specs (§8 Step 11 — E2E)

**Files:**
- Create: `e2e/tests/identity-{login,logout,choose-location,grails-shim-regression,password-change,password-reset,caller-regression}.spec.ts`
- Create (optional): `e2e/tests/identity-signup.spec.ts` (skipped if `OPENBOXES_SIGNUP_ENABLED=false`)

- [ ] **Step 1: Pattern each spec on Phase 1's `e2e/tests/document-*.spec.ts`.** Test fixtures + login helper already exist in `e2e/tests/` (see `login.spec.ts` for the admin-login pattern).

- [ ] **Step 2: identity-login.spec.ts** — clicks Sign In on LoginModal, asserts cookie set + `/api/identity/me` returns user JSON.

- [ ] **Step 3: identity-logout.spec.ts** — login then logout, assert cookie cleared + `/api/identity/me` returns 401.

- [ ] **Step 4: identity-choose-location.spec.ts** — login without location, then PUT /api/identity/chooseLocation, assert new cookie has `loc` claim.

- [ ] **Step 5: identity-grails-shim-regression.spec.ts** — POST `/openboxes/auth/handleLogin` form, assert shim forwards cookie, subsequent Grails-served page (`/openboxes/dashboard/index`) loads with HTTP 200.

- [ ] **Step 6: identity-password-change.spec.ts** — login, hit edit page, fill currentPassword + newPassword + passwordConfirm, submit, logout, login with new password. Also seed a SHA-1 fixture user, login, verify post-login the DB row is BCrypt format (via `sudo docker exec openboxes-db ...` from within the test).

- [ ] **Step 7: identity-password-reset.spec.ts** — POST `/openboxes/auth/forgotPassword` with email, query DB for `password_reset_token` row, navigate to `/openboxes/auth/resetPassword?token=...`, submit newPassword, login with new password.

- [ ] **Step 8: identity-caller-regression.spec.ts** — broad smoke through 4-5 Grails-served pages reading `session.user`: Dashboard, ProductList, InvoiceList, ShipmentList. Each must return HTTP 200 with the logged-in user's name in the rendered HTML.

- [ ] **Step 9: (Optional) identity-signup.spec.ts** — gated on env var; submits GSP signup form, asserts User + Person rows created in DB + welcome email mocked via test SMTP (MailHog-style).

- [ ] **Step 10: Run all Playwright specs.**
```bash
cd e2e && E2E_LOCATION_ID=1 npm test
# Expect: 9 existing + 7 new identity (+ optional 1) = 16 (or 17) pass; 0 fail
```

- [ ] **Step 11: Commit.**
```bash
git add e2e/tests/identity-*.spec.ts
git commit -m "phase 2 task 17: Playwright E2E specs (login, logout, chooseLocation, GSP shim regression, password change, password reset, caller regression)"
```

### Task 18: Done-gate verification + 1-hour soak + tag `phase-2-identity` (§8 Steps 12 + 13)

**Files:**
- (No code; commits + tag)

- [ ] **Step 1: Full clean rebuild + 5-container boot.**
```bash
cd services && ./gradlew :identity-service:bootJar :document-service:bootJar
./gradlew prepareDocker -Dgrails.env=prod -x generateGitProperties --console=plain
cd docker && sudo docker-compose down
sudo docker-compose up -d --build && sleep 90
sudo docker ps --filter name=openboxes --format "table {{.Names}}\t{{.Status}}"
# Expect: 5 containers, all healthy (db, app, document-service, identity-service, nginx)
```

- [ ] **Step 2: Health probes.**
```bash
sudo docker exec openboxes-identity-service curl -fs http://localhost:8082/actuator/health | jq .status
# Expect: "UP"
sudo docker exec openboxes-identity-service curl -s http://localhost:8082/v3/api-docs | jq '.paths | keys'
# Expect: 10 paths listed (per P48 spec literal correction — not 11 as spec done-gate §12.1 line 466 claims)
```

- [ ] **Step 3: Done-gate grep verifications (spec §12.1).**
```bash
grep -rn "JwtService\.issue\|jwtService\.issue" grails-app/ ; echo "exit: $?"
# Expect: zero hits (`exit: 1` = grep no-match)
grep -rn "JwtService\.buildSetCookieHeader" grails-app/ ; echo "exit: $?"
# Expect: zero hits
grep -rn "user\.lastLoginDate = new Date" grails-app/ ; echo "exit: $?"
# Expect: zero hits
grep -rn "userService\.authenticate\b" grails-app/ ; echo "exit: $?"
# Expect: zero hits (per P41)
# §15 admin-write carve-out boundary verification — outside the named list, no Grails writes to identity tables
grep -rn "\.save(" grails-app/ | grep -iE "userInstance|personInstance|roleInstance|locationRoleInstance" | \
  grep -vE "UserController|RoleController|PersonController|ShipmentController|CreateShipmentWorkflowController|UserService\.groovy:397|UserImportDataService|PersonImportDataService|UserLocationImportDataService|LoadDataService"
# Expect: zero hits (anything else is a §15 carve-out violation)
```
If any unexpected hit, surface to user before proceeding.

- [ ] **Step 4: JUnit pass.**
```bash
cd services && sudo -E ./gradlew :identity-service:test
# Expect: 100% pass
```

- [ ] **Step 5: All Playwright specs pass.**
```bash
cd e2e && E2E_LOCATION_ID=1 npm test
# Expect: 9 existing (Phase 0+1) + 7 new identity = 16 pass; 0 fail
```

- [ ] **Step 6: 1-hour soak.** Mirrors Phase 1 done-gate (`docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md` Task 13).
  - E2E iteration #1 (~5-8 min); capture `sudo docker stats --no-stream` baseline for 5 containers
  - 10-min wait; capture stats after-idle
  - E2E iteration #2; capture stats after
  - 15-min manual exercise: log in as admin, sign up test user via GSP form (with `OPENBOXES_SIGNUP_ENABLED=true` env override), change password, request password reset, complete reset via token, log out, log back in
  - Log grep: `sudo docker logs openboxes-identity-service 2>&1 | grep -iE 'error|exception|warn'` — filter known startup INFO; assert 0 unhandled errors
  - Log grep: `sudo docker logs openboxes-app 2>&1 | grep -iE 'User|Person|Role' | grep -iE 'error|exception'` — assert 0 hits
  - Pass conditions: memory steady (no monotonic growth across baseline → idle → after), zero unhandled errors, all manual flows pass UI-level
  - Record observations in audit doc

- [ ] **Step 7: Tag `phase-2-identity` at the clean HEAD** (BEFORE retrospective commit per Phase 1 precedent).
```bash
git rev-parse HEAD   # confirm we're at the post-Task-17 clean HEAD
git tag phase-2-identity HEAD
# Push tag AFTER user confirms
```
**Confirm with user before `git push origin phase-2-identity`** (per session-level rule about explicit push confirmation).

- [ ] **Step 8: Commit done-gate notes** (if any audit edits were made during soak).
```bash
git add docs/audits/  # only if soak notes were appended
git commit -m "phase 2 task 18: done-gate verification + 1-hour soak completed"
```

### Task 19: Phase 2 retrospective

**Files:**
- Create: `docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md` (~100 lines; mirror Phase 1 retrospective at `docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`)

- [ ] **Step 1: Write retrospective** with sections:
  - TL;DR (1 paragraph)
  - What worked (bullets — esp. anything Phase 2-specific: JOINED inheritance, password state machine, RoleTypeCache, etc.)
  - Codebase / env gotchas (Phase 3+ should know — sub-grouped Build & deploy / Code-level / Container / runtime)
  - Process / meta-lessons
  - Forward to Phase 3 (Location slice — what Phase 2 surfaced that Phase 3 will need)
  - Phase X: Identity-service decoupling (deferred from Phase 2 — verbatim from spec §16 essentially)
  - Artifacts (links to spec, plan, tag, commits)

- [ ] **Step 2: Commit retrospective on top of tag.**
```bash
git add docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md
git commit -m "phase 2: identity-service retrospective"
```

- [ ] **Step 3: Push (with explicit user confirmation).**
```bash
git push origin main
git push origin phase-2-identity
```

---

## Tasks NOT in this plan

Inherited from spec §4.2 + §15 + §16. A new spec → new plan cycle is required to add any of these.

- Deletion of Grails `User.groovy`, `Person.groovy`, `Role.groovy`, `LocationRole.groovy` — Phase X (or Phase 12 cleanup by elimination)
- Migration of Grails admin UIs (UserController CRUD, RoleController, LocationRoleController GSP screens) to React — Phase X or eliminated in Phase 12
- Person-creation paths in shipping workflows (`ShipmentController:1083`, `CreateShipmentWorkflowController:171,1080`) — stay Grails through Phase 8 (Shipping slice owns Person-from-shipment then)
- User/Person/Role CRUD HTTP endpoints (beyond `GET /api/identity/users/{id}` for cross-service lookup) — Phase X
- OIDC / external IdP — never (per parent spec §11) or Phase 12+ if ever
- JWT refresh tokens / revocation list / blacklist — never (parent spec §7.6)
- 2FA / MFA — not in parent spec
- Audit log of identity events — not in parent spec
- Multi-tenant identity — not in parent spec
- Separation of `user.dashboard_config` (per-user React UI state) into a separate concern — stays as `user.dashboard_config` longblob; identity-service owns it as part of the user row

## Phase 2 hybrid state (intentional)

Inherited verbatim from spec §15. Phase 2 leaves the codebase in a strangler-fig hybrid:

- **identity-service is the authoritative writer** of user/person/role/user_role/location_role tables for all user-initiated paths (login, signup, password change, password reset, chooseLocation).
- **identity-service is the sole JWT issuer.**
- **Grails User.groovy, Person.groovy, Role.groovy, LocationRole.groovy STAY ALIVE** as read-bridges. ~97 `User.get|findBy` callsites + 30 Person callsites + 22 Role callsites + 54 `session.user`/`authService.currentUser` readers continue to work via GORM against the shared DB.
- **`AuthService.currentUser` ThreadLocal pattern** is preserved. Shims populate `session.user` from identity-service login response body, then existing SecurityInterceptor + AuthService logic runs unchanged.

### Bounded carve-out (Grails writes that remain to identity-owned tables)

| Caller | Write target | Why retained in Phase 2 | Resolution phase |
|---|---|---|---|
| `UserController.save/update/delete` (admin user CRUD via GSP) | user, person, user_role, location_role | Admin UI rebuild is days of GSP/React work; admin-rare | Phase X or Phase 12 elimination |
| `RoleController` (admin Role CRUD) | role | Admin-rare | Phase X |
| `PersonController.save/update` (admin person CRUD via GSP) | person | Admin-rare | Phase X |
| `ShipmentController.groovy:1083` (`new Person()` during shipment workflow) | person | Shipping-slice concern | Phase 8 (Shipping slice) |
| `CreateShipmentWorkflowController.groovy:171,1080` | person | Same as above | Phase 8 |
| `UserImportDataService`, `UserLocationImportDataService`, `PersonImportDataService` (bulk import services) | user, person, user_role, location_role | Admin bulk-import; rarely exercised | Phase X (or kept until Phase 12) |
| `LoadDataService.groovy` (data-loading bootstrap path) | user, person | One-off setup | Phase 12 elimination |
| `UserService.groovy:397-399` (Sql.execute bootstrap seed) | user | Seed/test bootstrap | Phase 12 elimination |

Outside this list, no Grails code may write to user/person/role/user_role/location_role tables after Phase 2. Done-gate Task 18 Step 3 verifies via grep.

## Phase X: Identity-service decoupling (deferred from Phase 2)

Inherited verbatim from spec §16. Placeholder for the eventual completion of Grails → identity-service for the auth slice. **Trigger to dispatch**: this phase runs once the following are answered (likely via a focused brainstorming + design-spec cycle):

1. **Admin UI fate.** Migrate UserController/RoleController/PersonController/LocationRoleController GSP screens to React + identity-service admin CRUD endpoints, OR retain GSP screens with thin shims that POST to identity-service, OR delete in Phase 12 only.
2. **Bulk import / data-loading paths.** Port UserImportDataService / PersonImportDataService / UserLocationImportDataService / LoadDataService to identity-service equivalents OR retain Grails-side with shim writes via identity-service.
3. **`User.findByUsername`/`User.get` Grails-side read bridges** — when do they go away? Each caller migrates to `IdentityClient.fetchUser(id)` in its own slice (likely incremental across many phases).
4. **`AuthService.currentUser` ThreadLocal pattern** survives until Grails dies (Phase 12). No Phase X action needed.
5. **`location_role.location_roles_idx` and other Grails-list-index columns** — once join tables move to identity-service ownership (if ever — see Phase X #6), decide whether `@OrderColumn` is preserved or dropped.
6. **Join-table ownership.** `user_role` and `location_role` join tables — stay identity-service-owned (current design); migrate further if a Phase X decomposition requires it.

**Owner**: TBD. Likely paired with the Phase 8 Shipping slice (which owns the Person-creation paths) for `ShipmentController` / `CreateShipmentWorkflowController` cleanup; the rest could be a dedicated mini-phase or absorbed in Phase 12.

## Known issues inherited from spec

Inherited verbatim from spec §14.

- **Java 8 EOL on Grails container.** Same as parent §11 — stays until Phase 12.
- **Cleartext-stored passwords (if any) reject after Phase 2.** Any user row whose `password` column is literal plaintext (a legacy data-quality issue; existing in current codebase per `UserService.groovy:494` fallback) cannot log in via identity-service. They must reset via the new forgot-password flow. **Acceptance**: blast-radius probably zero or small on a single-developer dev DB; clean cutover is preferable to indefinitely supporting cleartext. Document in operator-facing change notes.
- **GORM `dirty` checking** on the Grails-side `session.user` may diverge from the identity-service-canonical row if a Grails admin action saves `user.active = false` and identity-service is mid-transaction issuing a JWT for that user. Acceptable race; bounded by JWT TTL.
- **Password reset emails depend on identity-service's MailSender config.** If SMTP is misconfigured, reset-request fails silently (`always-200` design). Operator must verify SMTP at deploy time. Document in operator runbook.
- **Welcome email (signup)** moves from Grails `UserSignupEventService` to identity-service. If signup is disabled (`OPENBOXES_SIGNUP_ENABLED=false`, the default), this path is unreachable.
- **Bounded admin-write carve-out** (see Phase 2 hybrid state above) is an explicit hybrid-state violation. Bounded; documented; resolved in Phase X or Phase 12 cleanup.
- **`UserService.groovy:397-399` SQL bootstrap path** writes a user row directly via `Sql.execute('insert into user ...')` with a SHA-1+Base64-hashed `"password"` literal. This is a seed/test bootstrap. It still works (SHA-1+Base64 is on the verifier path) and migrates to BCrypt on first login. Document in Phase X note.
- **L2 cache** wasn't explicitly flipped because none of the 4 domain classes declared `cache true`. If Grails defaults change in a future upgrade, revisit (per Phase 1 T4-I1 precedent).
- **Spring Security setup** in identity-service uses a stateless cookie-based auth (no session, no CSRF for API). Same pattern document-service uses; consistent.
- **`location_role.location_roles_idx` column** (Grails list-index for ordered hasMany) is preserved in the JPA entity for round-trip compatibility but identity-service writes don't strictly maintain it (a JPA `@OrderColumn` could be added if needed; Phase X concern).
