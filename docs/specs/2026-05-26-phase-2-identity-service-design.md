---
date: 2026-05-26
phase: 2
slice: identity
parent_spec: docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md
---

# Phase 2 Identity-service Design Spec

## 1. Problem

Extract the auth slice — `user` / `person` / `role` / `user_role` / `location_role` tables and JWT issuance — from the Grails monolith into a standalone Spring Boot `identity-service`, following the per-slice template (parent spec §8) and the strangler-fig pattern Phase 1 established with `document-service`. Phase 2 is the second slice extraction and the first one to face GORM-to-JPA inheritance mapping (User extends Person, JOINED strategy).

At Phase 2's done-gate, identity-service is the sole JWT issuer and the authoritative writer of every user-initiated write path (login, signup, password change, password reset, chooseLocation). Grails becomes a JWT consumer that still reads User/Person/Role via GORM bridges (~54 controllers + interceptor surface) and still owns admin-rare CRUD writes to those tables (bounded carve-out documented in §15).

## 2. Constraints

Inherited from parent spec §2 and §5. The Phase-2-specific ones:

- **Coexistence required.** Grails domain classes `User.groovy`, `Person.groovy`, `Role.groovy`, `LocationRole.groovy` STAY ALIVE — too many readers (97 `User.get|findBy` callsites, 30 Person, 22 Role, 54 `session.user`/`authService.currentUser`-reading files) to migrate in one phase. Grails reads via shared DB; identity-service is canonical writer.
- **Java 8 on Grails side** (parent §5). identity-service runs Java 21 + Spring Boot 3.x + jjwt 0.12+. Grails keeps jjwt 0.11.x (Java-8 compatible) for cookie validation only.
- **Additive-only schema constraint** while Grails User/Person/Role domain classes remain (per-slice template §6). identity-service may add new columns/tables; cannot rename/drop/narrow existing.
- **Single-developer, no-live-users posture.** No multi-tenant identity. No OIDC. No JWT revocation/refresh tokens. HMAC-HS256 JWT with shared `OPENBOXES_JWT_SECRET` env var (Phase 0 pattern) is fit-for-purpose.

## 3. Approach

**Strangler-fig hybrid** (Phase 1 precedent). identity-service owns tables (Liquibase shadow pattern) and user-initiated writes; Grails reads via shared DB through unchanged GORM domain classes. Grails-side login plant points are replaced with thin shims that POST to identity-service and forward the `Set-Cookie` header. Grails JWT validation stays local (jjwt + shared HMAC secret) — no per-request HTTP introspection.

The hybrid leaves a bounded set of Grails-side writes to the identity-owned tables — explicitly named in §15 and accepted. Phase X (placeholder, no timeline) eventually deletes the Grails User/Person/Role domain classes and migrates admin CRUD to React + identity-service.

## 4. Scope

### 4.1 In scope (Phase 2 deliverables)

- New Spring Boot module `services/identity-service/` (Java 21, Spring Boot 3.x, jjwt 0.12+, Spring Security BCrypt, Spring Boot starter-mail, springdoc-openapi 2.5+, Liquibase, Spring Data JPA + Hibernate 6, mariadb-java-client).
- JPA entities for `Person`, `User`, `Role`, `LocationRole` (User extends Person via `@Inheritance(JOINED)` + `@PrimaryKeyJoinColumn`).
- Liquibase shadow-changelog at `services/identity-service/src/main/resources/db/changelog/identity-changelog-master.xml` (MARK_RAN against existing tables; same FILENAME-namespacing pattern as Phase 1 `document-service`).
- New `password_reset_token` table (NEW; identity-service-only ownership; not shadowed).
- All HTTP endpoints under `/api/identity/*` (see §6).
- Password handling: BCrypt-encoded for new/changed passwords; SHA-1+Base64 backward-compat verifier with auto-migrate-on-successful-login (see §10). Cleartext-storage fallback (present in current Grails code) is NOT preserved.
- Grails `IdentityClient` (service, ~150 lines) — analog of Phase 1's `DocumentClient`.
- Grails AuthController.handleLogin / ApiController.login / ApiController.chooseLocation / ApiController.logout / UserController.changePassword / AuthController.handleSignup all refactored to thin shims that POST to identity-service and forward Set-Cookie.
- Grails `JwtService.issue()` and `JwtService.buildSetCookieHeader()` static methods DELETED. `JwtService.validate()` stays for `SecurityInterceptor`.
- Grails `SecurityInterceptor` mechanical reduction (~10 lines smaller). Same shape; still does the 5 business-policy checks.
- React `LoginModal.jsx:24,41` + `actions/index.js:220` inline URL updates (3 inline references — no central constants file).
- Forgot-password flow as NEW feature (no Grails equivalent exists today). React adds "Forgot password?" link on LoginModal.
- nginx routing: new `location /api/identity` block above `/api/documents` (which is above `/api/`).
- Email-sending in identity-service (Spring Boot starter-mail; reuses Grails `grails.mail.*` config values via env vars in `spring.mail.*` keys).
- Welcome email (UserSignupEvent listener ports to a Java `EmailService` in identity-service).
- ReCAPTCHA validation in identity-service (mirrors `RecaptchaService.groovy`).
- TestContainers JUnit integration test (5 endpoints minimum) + Playwright E2E specs (7 new specs).
- Done-gate verification + 1-hour soak + retrospective + tag `phase-2-identity`.

### 4.2 Tasks NOT in this plan (out of scope — see §15 + §16)

- Deletion of Grails `User.groovy`, `Person.groovy`, `Role.groovy`, `LocationRole.groovy` — Phase X (or Phase 12 cleanup by elimination).
- Migration of Grails admin UIs (UserController CRUD, RoleController, LocationRoleController GSP screens) to React — Phase X or eliminated in Phase 12.
- Person-creation paths in shipping workflows (`ShipmentController:1083`, `CreateShipmentWorkflowController:171,1080`) — stay Grails through Phase 8 (Shipping slice owns Person-from-shipment then).
- User/Person/Role CRUD HTTP endpoints (beyond `GET /api/identity/users/{id}` for cross-service lookup) — Phase X.
- OIDC / external IdP — never (per parent spec §11) or Phase 12+ if ever.
- JWT refresh tokens / revocation list / blacklist — never (parent spec §7.6).
- 2FA / MFA — not in parent spec.
- Audit log of identity events — not in parent spec.
- Multi-tenant identity — not in parent spec.
- Separation of `user.dashboard_config` (per-user React UI state) into a separate concern — stays as `user.dashboard_config` longblob; identity-service owns it as part of the user row.

## 5. Tech choices

| Concern | Choice |
|---|---|
| Module | `services/identity-service/` Gradle sub-module (add `include 'identity-service'` to `services/settings.gradle`) |
| Runtime | Java 21, Spring Boot 3.x (matches document-service) |
| Web | spring-boot-starter-web |
| Persistence | spring-boot-starter-data-jpa + Hibernate 6, mariadb-java-client 3.4.x |
| Migrations | Liquibase via spring-boot-starter (shadow pattern) |
| Security | spring-boot-starter-security + jjwt-api/impl/jackson 0.12+ + BCryptPasswordEncoder (cost 10) |
| Email | spring-boot-starter-mail (SMTP) |
| API docs | springdoc-openapi-starter-webmvc-ui 2.5.0 |
| Tests | spring-boot-starter-test + spring-security-test + testcontainers junit-jupiter + testcontainers mariadb (BOM override to 1.21.3 per Phase 1 T11-I1 lesson) |
| Container | Same Dockerfile pattern as document-service — multi-stage `temurin:21-jre` base, non-root user, `wget`-based healthcheck (per Phase 1 retrospective gotcha #2: curl unavailable in jre base image) |
| Port | 8082 (8080 = grails, 8081 = document-service) — `expose:` only, NOT `ports:` (matches document-service security posture; all external traffic routes through nginx) |
| Module wiring | New `services/identity-service/` declared in `services/settings.gradle`; Grails `IdentityClient` bean wired via `grails-app/conf/spring/resources.groovy` |

## 6. HTTP API contracts

All endpoints on identity-service, served via nginx at `/api/identity/*`. JSON request/response unless noted. Authenticated endpoints require valid `obx_token` HttpOnly cookie. JWT claims: `{sub: userId, loc: locationId|null, roles: [roleIds], iat, exp}`. Token lifetime: 8 hours (same as Phase 0 pattern). Roles claim keeps Phase 0/1 format — raw entity IDs like `"R001"` (NOT Spring `ROLE_*` prefixed; per T7-M3 follow-up).

### 6.1 Auth lifecycle

| Method | Path | Request body | Response 200 | Cookie effect | Notes |
|---|---|---|---|---|---|
| `POST` | `/api/identity/login` | `{username, password, location?}` | `{user: {id, username, firstName, lastName, email, roles: [ids]}, location: {id, name}\|null}` | Sets `obx_token` HttpOnly SameSite=Strict cookie (8h TTL, `Path=/`) | Accepts username OR email (mirrors current `User.findByUsernameOrEmail`). 401 on bad creds (incl. account-not-found). 403 on `!person.active`. Triggers BCrypt-or-SHA1 verification with auto-migrate (§10). |
| `POST` | `/api/identity/logout` | `{}` | `{}` | Clears `obx_token` (`Max-Age=0`) | Idempotent (200 even if no cookie). |
| `PUT` | `/api/identity/chooseLocation/{id}` | `{}` | `{user: {...}, location: {...}, effectiveRoles: [ids]}` | Re-issues `obx_token` with updated `loc` claim and refreshed `roles` (effective roles = global + location-specific). | Requires authenticated cookie. 404 if location not found, 403 if location disabled or user lacks LocationRole for it. **Also updates `user.last_login_date`** (preserves the semantic from Grails `DashboardController.chooseLocation:225` which is removed). |
| `GET` | `/api/identity/me` | (cookie) | `{user: {...}, location: {...}\|null, effectiveRoles: [ids]}` | (none) | Replaces React's currentUser lookup. effectiveRoles = global user.roles + LocationRoles-for-current-location. 401 on missing/invalid cookie. |

### 6.2 Signup + password

| Method | Path | Request body | Response 200 | Notes |
|---|---|---|---|---|
| `POST` | `/api/identity/signup` | `{username, password, firstName, lastName, email, phoneNumber?, additionalQuestions?, recaptchaToken?}` | `{user: {id, username, email}}` | Gated by `OPENBOXES_SIGNUP_ENABLED` env (default `false`). Optional ReCAPTCHA validation if `OPENBOXES_SIGNUP_RECAPTCHA_ENABLED=true`. Triggers welcome email. 403 if signup disabled, 400 on password complexity or validation failure, 409 on duplicate username/email. Creates Person + User rows in single JPA transaction; assigns default roles (mirrors `UserService.assignDefaultRoles`). |
| `POST` | `/api/identity/password/change` | `{currentPassword, newPassword}` | `{}` | **Authenticated self-edit.** Verifies current password (using §10 BCrypt-or-SHA1 verifier). 401 if currentPassword wrong, 400 if newPassword fails complexity rules (§10.2). Stores new password as BCrypt. |
| `PUT` | `/api/identity/users/{id}/password` | `{newPassword}` | `{}` | **Admin-edit endpoint.** Caller must hold a role whose `RoleType` is `ROLE_ADMIN` or `ROLE_SUPERUSER`. The JWT `roles` claim carries raw role IDs (Phase 0/1 convention, per A28); identity-service resolves IDs → `RoleType` via an in-memory cache loaded at startup (`SELECT id, role_type FROM role`). Cache refresh trigger (TTL vs. Grails-notified vs. on-cache-miss reload) is a TWP-level decision; staleness is tolerable since role-type changes are admin-rare. Does NOT require currentPassword. 403 if caller lacks admin role, 404 if user not found, 400 on weak password. Stores new password as BCrypt. |
| `POST` | `/api/identity/password/reset-request` | `{email}` | `{}` | **NEW feature.** Always returns 200 (don't leak whether email exists). If email matches an active user, generates single-use token (random 32-byte URL-safe), inserts row in `password_reset_token` (24h TTL), sends reset-link email. |
| `POST` | `/api/identity/password/reset/{token}` | `{newPassword}` | `{}` | **NEW feature.** Validates token (exists, not used, not expired). 400 on weak password. Stores new password as BCrypt; marks token used. |

### 6.3 User lookup (read-only, for cross-service callers)

| Method | Path | Request | Response 200 | Notes |
|---|---|---|---|---|
| `GET` | `/api/identity/users/{id}` | (cookie) | `{id, username, firstName, lastName, email, active, roles: [ids]}` | Thin user lookup. Used by other Spring Boot services that need to display user info (e.g., document-service for future uploader-name use). NOT used by Grails (Grails calls `User.get(id)` against shared DB). |

### 6.4 nginx routing

Add the following block to `docker/nginx/conf.d/app.conf` ABOVE the existing `/api/documents` block:

```nginx
location /api/identity {
    proxy_pass http://identity-service:8082;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Cookie $http_cookie;
}
```

Position rule: more specific matches first. `/api/identity` → identity-service; `/api/documents` → document-service; `/api/` → Grails. nginx `client_max_body_size 10m` (set globally per Phase 1 T9-M1) applies; signup payload + photo upload (if added later) fits.

## 7. Data model

### 7.1 JPA entities

`services/identity-service/src/main/java/org/openboxes/identity/entity/`

**Person.java** (base entity for JOINED inheritance; columns from `person` table at install/changelog-create-tables.groovy:1750):

```java
@Entity
@Table(name = "person")
@Inheritance(strategy = InheritanceType.JOINED)
public class Person {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;       // uuid; set by service.create() (Phase 1 pattern)
    @Version @Column(nullable = false)         private Long version;    // BIGINT; Person owns the version for JOINED
    @Column(name = "first_name", nullable = false, length = 255) private String firstName;
    @Column(name = "last_name",  nullable = false, length = 255) private String lastName;
    @Column(length = 255)                       private String email;
    @Column(name = "phone_number", length = 255) private String phoneNumber;
    @Column(name = "date_created", nullable = false) private Instant dateCreated;
    @Column(name = "last_updated", nullable = false) private Instant lastUpdated;
    @Column                                     private Boolean active;
    // toJson — anonymize-config aware (mirror Grails Person.toJson)
}
```

**User.java** (extends Person; columns from `user` table at install/changelog-create-tables.groovy:3644; note no `version`, no `date_created`, no `last_updated`, no `active` — those live on Person):

```java
@Entity
@Table(name = "`user`")  // backticks required (MariaDB reserved word; matches Grails User.groovy:40)
@PrimaryKeyJoinColumn(name = "id")
public class User extends Person {
    @Column(nullable = false, unique = true, length = 255) private String username;
    @Column(nullable = false, length = 255)                private String password;       // BCrypt format after migration; SHA-1+Base64 legacy until next login
    @Column(length = 255)                                  private String locale;
    @Column(length = 255)                                  private String timezone;
    @Column(name = "last_login_date")                      private Instant lastLoginDate;
    @Column(name = "warehouse_id", columnDefinition = "CHAR(38)") private String warehouseId;  // flat FK to location.id (location-service is Phase 3)
    @Column(name = "manager_id",   columnDefinition = "CHAR(38)") private String managerId;    // self-FK to user.id
    @Column(name = "remember_last_location")               private Boolean rememberLastLocation;
    @Lob @Column(columnDefinition = "MEDIUMBLOB")          private byte[] photo;
    @Lob @Column(name = "dashboard_config", columnDefinition = "LONGBLOB") private byte[] dashboardConfig;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_role",
               joinColumns        = @JoinColumn(name = "user_id"),
               inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles;

    @OneToMany(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private Set<LocationRole> locationRoles;
}
```

**Role.java** (columns from `role` table at install/changelog-create-tables.groovy:2938):

```java
@Entity @Table(name = "role")
public class Role {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column(nullable = false)         private Long version;       // BIGINT
    @Column(length = 255)                       private String description;
    @Column(name = "role_type", length = 255, nullable = false) @Enumerated(EnumType.STRING) private RoleType roleType;
    @Column(length = 255, nullable = false)     private String name;
}
```

**LocationRole.java** (columns from `location_role` table at install/changelog-create-tables.groovy:1184):

```java
@Entity @Table(name = "location_role")
public class LocationRole {
    @Id @Column(columnDefinition = "CHAR(38)") private String id;
    @Version @Column                            private Integer version;   // INT, NOT BIGINT
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id") private User user;
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "role_id") private Role role;
    @Column(name = "location_id", columnDefinition = "CHAR(38)") private String locationId;  // flat FK (Phase 3)
    @Column(name = "location_roles_idx") private Integer locationRolesIdx;  // Grails list-index column; preserved for compatibility
}
```

**RoleType.java** — Java enum mirroring Grails `RoleType`. Values enumerated by reading `src/main/groovy/org/pih/warehouse/core/RoleType.groovy` during port. Stored as VARCHAR(255) via `@Enumerated(EnumType.STRING)`.

**PasswordResetToken.java** (NEW entity; identity-service-only ownership):

```java
@Entity @Table(name = "password_reset_token")
public class PasswordResetToken {
    @Id @Column(length = 64)                    private String token;       // URL-safe random 32 bytes → 43-char base64
    @ManyToOne(fetch = FetchType.LAZY) @JoinColumn(name = "user_id", columnDefinition = "CHAR(38)") private User user;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "used_at")                   private Instant usedAt;     // nullable; non-null means token consumed
    @Column(name = "created_at", nullable = false) private Instant createdAt;
}
```

### 7.2 Liquibase shadow strategy

Same pattern as Phase 1 Document. identity-service ships a master changelog at `services/identity-service/src/main/resources/db/changelog/identity-changelog-master.xml` that shadows the existing Grails-owned changesets for `person`, `user`, `role`, `user_role`, `location_role`.

- Existing Grails Liquibase changesets in `grails-app/migrations/install/changelog-create-tables.groovy` for these tables STAY IN GRAILS (removing them would re-create the tables on next Grails boot per Phase 1 T6-M2 lesson).
- identity-service's master changelog declares the same table structure with FILENAME-namespaced changeset IDs (e.g., `services/identity-service/db/changelog/.../changelog-create-table-person.xml`). `preConditions: tableExists` causes MARK_RAN (don't re-create).
- Additive-only constraint applies while Grails domain classes remain (lifts when Phase X deletes them).
- New table `password_reset_token` is identity-service-only (not shadowed).

Production deploy runbook concern: same as Phase 1 T6-M2. Document the changeset-relocation behavior (two rows in DATABASECHANGELOG per relocated table — old + new MARK_RAN; schema unchanged).

### 7.3 Hibernate L2 cache

None of `User.groovy`, `Person.groovy`, `Role.groovy`, `LocationRole.groovy` declares `cache true` in its `static mapping` block — verified pre-write. Default applies (no L2 cache). No Phase 1-style cache flip needed (T4-I1 precedent doesn't reapply).

## 8. Grails-side integration

### 8.1 IdentityClient (~150 lines)

`grails-app/services/org/pih/warehouse/auth/IdentityClient.groovy`. Analog of Phase 1 `DocumentClient.groovy`. Single Spring bean wired in `grails-app/conf/spring/resources.groovy:24` (alongside `documentClient` at line 23). Holds long-lived `RestTemplate` field (Phase 1 T8b-I3 lesson — don't construct per-call). Constructor wires `SimpleClientHttpRequestFactory` with `connectTimeout=5000ms`, `readTimeout=10000ms` (Phase 1 T8b-I4 lesson — `RestTemplateBuilder` unavailable in Grails 3 Spring Boot).

Methods:

```groovy
class IdentityClient {
    Map login(String username, String password, String locationId)
        // POST identity-service/login {username,password,location:locationId}
        // returns {body: Map, setCookieHeader: String}
        // throws BadCredentialsException(401), AccountDisabledException(403)

    String logout(String obxTokenCookie)
        // POST .../logout with cookie; returns the clear-cookie Set-Cookie header

    Map chooseLocation(String locationId, String obxTokenCookie)
        // PUT .../chooseLocation/{id} with cookie; returns {body, setCookieHeader}

    Map me(String obxTokenCookie)
        // GET .../me with cookie

    Map signup(Map signupData)
        // POST .../signup; throws SignupDisabledException(403), DuplicateUsernameException(409), ValidationException(400)

    void changePassword(String currentPassword, String newPassword, String obxTokenCookie)
        // POST .../password/change with cookie; throws BadCredentialsException(401), PasswordTooWeakException(400)

    void changeUserPasswordAsAdmin(String userId, String newPassword, String obxTokenCookie)
        // PUT .../users/{id}/password with admin cookie; 403 if caller not admin

    void requestPasswordReset(String email)
        // POST .../password/reset-request; never throws (return-200-always design)

    void resetPassword(String token, String newPassword)
        // POST .../password/reset/{token}; throws InvalidTokenException(400), PasswordTooWeakException(400)

    Map fetchUser(String id, String obxTokenCookie)
        // GET .../users/{id} with cookie; throws ObjectNotFoundException(404)
}
```

Cleanup helpers: `drainAndDisconnect(ResponseEntity)` (Phase 1 T8b-I3 lesson — release HTTP connection back to pool).

### 8.2 Shim controller actions

Pattern: each shim reads request body/params, calls IdentityClient, forwards `Set-Cookie` from identity-service response to Grails outgoing response, redirects per existing logic. The five touch points:

| Grails action | Location | Shim behavior |
|---|---|---|
| `ApiController.login` | `grails-app/controllers/.../api/ApiController.groovy:43-61` | POSTs to `/api/identity/login`; forwards `Set-Cookie`; populates `session.user` + `session.warehouse` from response body for backward compat with Grails-side ThreadLocal pattern; renders 200/401 per existing pattern. |
| `ApiController.chooseLocation` | `ApiController.groovy:63-72` | PUTs to `/api/identity/chooseLocation/{id}` with incoming cookie; forwards new `Set-Cookie`; updates `session.warehouse`; renders existing 200 text. |
| `ApiController.logout` | `ApiController.groovy:258-...` | POSTs to `/api/identity/logout` with incoming cookie; forwards clear-`Set-Cookie`; calls `session.invalidate()`. |
| `AuthController.handleLogin` | `grails-app/controllers/.../user/AuthController.groovy:69-135` | POSTs to `/api/identity/login` with form params; forwards `Set-Cookie`; populates `session.user` + `session.warehouse`; redirects per existing `session.targetUri` logic on success, error flash on 401. |
| `AuthController.handleSignup` | `AuthController.groovy:178-222` | POSTs to `/api/identity/signup` with form params + ReCAPTCHA token; redirects per existing logic; flash messages mapped from identity-service exception types. |
| `AuthController.logout` (GSP) | `AuthController.groovy:141-153` | POSTs to `/api/identity/logout` with cookie; forwards clear-`Set-Cookie`; calls `session.invalidate()`; preserves impersonation-cleanup branch. |
| `UserController.changePassword` (self-edit path) | `grails-app/controllers/.../user/UserController.groovy:278-299` | Routes to admin or self-edit endpoint based on whether the target user is the current user. Self-edit POSTs to `/api/identity/password/change` requiring currentPassword (small GSP edit needed to add the currentPassword input field). Admin-edit PUTs to `/api/identity/users/{id}/password`. |
| `UserController.update` | `grails-app/controllers/.../user/UserController.groovy:236` | **Stays direct-Grails-write** for non-password fields (active, roles, locationRoles, manager). Documented bounded carve-out (§15). |

### 8.3 SecurityInterceptor (post-Phase-2 shape)

`grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy` — reduced by ~10 lines.

Unchanged blocks:
- Lines 30-31: matchAll() exclusions
- Lines 33-37: afterView() ThreadLocal clear
- Lines 38-59: JWT cookie validation + session.user population (Phase 0 block — still works; `JwtService.validate()` is the only auth helper called)
- Lines 60-65: authService ThreadLocal population
- Lines 66-85: healthcheck/megamenu/mobile/params-null branches
- Lines 86-120: unauth-redirect-to-login
- Lines 122-133: deactivated-user check
- Lines 135-141: disabled-location check
- Lines 143-151: missing-location-redirect-to-chooseLocation

Deleted: nothing in the interceptor itself. The "trivial" interpretation of the parent spec's done-gate ("SecurityInterceptor gone or trivial") means trivial-in-the-sense-of "no longer participates in token issuance — just consumes them." Going further (e.g., a Spring Boot reverse-proxy in front of Grails to take over the business-policy checks) is explicitly out of scope per §4.2.

### 8.4 Grails JwtService (post-Phase-2)

`grails-app/services/org/pih/warehouse/auth/JwtService.groovy` — reduced from 63 lines to ~30 lines.

| Symbol | Disposition |
|---|---|
| `COOKIE_NAME = 'obx_token'` | STAY |
| `TOKEN_LIFETIME_SECONDS = 8 * 3600` | STAY |
| `private SecretKey getSigningKey()` | STAY (used by validate) |
| `String issue(User user, Location location)` | **DELETE** (identity-service is sole issuer) |
| `Map<String,Object> validate(String token)` | STAY (used by SecurityInterceptor) |
| `static String buildSetCookieHeader(String token, boolean clear)` | **DELETE** (no longer used; identity-service builds the header) |

jjwt 0.11.x dependency stays in `build.gradle` (validate still needs it).

### 8.5 Grails domain classes (User, Person, Role, LocationRole) — UNTOUCHED

These four domain classes stay alive verbatim. Their getters/setters/methods continue to serve the ~54 Grails files that read `session.user.firstName`, `user.getEffectiveRoles(...)`, etc. No `// TODO(migrate-to-identity-service)` markers are added in Phase 2 to the read sites (it's all 100+ of them); the bridge pattern is named globally in the design.

`UserService.groovy` — most of it stays. Method-by-method disposition:
- `authenticate(username, password)` — replaced by call to `IdentityClient.login()`; could be deleted or kept as a thin wrapper
- `assignDefaultRoles(user)` — STAYS (used by admin user-create path which remains in Grails)
- `changePassword(user, password, passwordConfirm)` — replaced by `IdentityClient.changePassword*()`
- `hasHighestRole(user, locationId, roleType)` — STAYS (used in 4+ Grails-side authorization checks)
- The `Sql.execute` hardcoded-password seed at lines 397-399 — STAYS (it's a bootstrap path; passwords are encodeAsPassword-hashed via SHA-1+Base64, and identity-service will auto-migrate to BCrypt on first login)

`UserSignupEventService.groovy` — DELETED. Its responsibilities (welcome email) move to identity-service's Java `EmailService`.

`DashboardController.groovy:218-228` — the `user.lastLoginDate = new Date(); user.save(flush: true)` block at line 225 is **DELETED**. The semantic moves to identity-service's chooseLocation endpoint (§6.1).

## 9. React-side changes

Inline string replacements at 3 sites; no central constants file refactor.

| File | Line | Before | After |
|---|---|---|---|
| `src/js/components/LoginModal.jsx` | 24 | `const url = '/api/login';` | `const url = '/api/identity/login';` |
| `src/js/components/LoginModal.jsx` | 41 | `const url = \`/api/chooseLocation/${this.props.currentLocationId}\`;` | `const url = \`/api/identity/chooseLocation/${this.props.currentLocationId}\`;` (method also changes from `apiClient.post` to `apiClient.put`) |
| `src/js/actions/index.js` | 220 | `const url = \`/api/chooseLocation/${location.id}\`;` | `const url = \`/api/identity/chooseLocation/${location.id}\`;` + verb change |

Additional new React surface:
- "Forgot password?" link on `LoginModal.jsx` → routes to a new modal/page that POSTs `{email}` to `/api/identity/password/reset-request` and shows a "check your email" message.
- A password-reset confirmation page reachable via the email link (`/openboxes/password-reset?token=...`) that prompts for newPassword + posts to `/api/identity/password/reset/{token}`. Can be a simple GSP-hosted React component (since React is GSP-hosted per parent spec §A19); or a server-side GSP form posting to a Grails shim that delegates to identity-service. **Plan decision**: GSP form posting to a Grails shim, to minimize React surface in Phase 2.

## 10. Password handling

### 10.1 Verification (with auto-migrate)

identity-service `PasswordEncoder` is a `DelegatingPasswordEncoder` with two recognized formats. On every `verify(rawPassword, storedHash)` call:

```
1. If storedHash starts with "$2a$" / "$2b$" / "$2y$" (BCrypt format):
   return BCrypt.verify(rawPassword, storedHash)
2. Else if SHA1Base64(rawPassword) == storedHash:
   return true; in a nested `@Transactional(propagation = REQUIRES_NEW)` boundary wrapped in try/catch, re-hash to BCrypt and UPDATE user row
3. Else:
   return false  (no cleartext fallback — Grails legacy cleartext storage rejected by design)
```

SHA1Base64 implementation matches `grails-app/utils/org/pih/warehouse/PasswordCodec.groovy`:
```java
byte[] digest = MessageDigest.getInstance("SHA").digest(rawPassword.getBytes(UTF_8));
String result = Base64.getEncoder().encodeToString(digest);   // no padding stripping; matches Apache Commons Codec default
```

Auto-migrate runs synchronously on the same request thread but in a nested `REQUIRES_NEW` transaction, isolated from the outer login transaction. On exception, the catch swallows the error, emits a WARN log recording the migration miss, and returns true — login still succeeds because verification was true. On success, the migrated BCrypt row is durably committed even if the outer login transaction rolls back for an unrelated reason.

Logging: every successful SHA-1 verify emits `INFO: legacy SHA-1 password migrated to BCrypt for userId={id}`. This gives operators a metric for migration progress.

### 10.2 Complexity rules

Enforced server-side in identity-service on signup, password/change, password/reset:

- minSize 8 (up from current Grails minSize 6)
- maxSize 255 (matches `user.password` VARCHAR(255) constraint)
- Must contain: ≥1 uppercase, ≥1 lowercase, ≥1 digit, ≥1 special character (special = ASCII punctuation `!@#$%^&*()_+\-=\[\]{};':"\\|,.<>/?`)

Mirrored client-side in React signup/password forms for UX hint (not a security gate). Existing users grandfather — rules apply only on new signup, password change, or reset.

### 10.3 BCrypt cost

`BCryptPasswordEncoder` with cost 10 (Spring Security default). Re-hashing on the auto-migrate path uses cost 10.

## 11. Testing strategy

### 11.1 JUnit + TestContainers

`services/identity-service/src/test/java/org/openboxes/identity/IdentityServiceIntegrationTest.java`. Mirrors Phase 1's `DocumentServiceIntegrationTest`. TestContainers MariaDB 10.6 with BOM 1.21.3 override + `api.version=1.44` + `ryuk.disabled=true` (Phase 1 T11-I1 + retrospective lessons; copy verbatim).

Test methods (minimum):
- `loginGoodCreds_returns200AndSetsCookie`
- `loginBadCreds_returns401`
- `loginDisabledAccount_returns403`
- `logout_clearsCookie`
- `chooseLocation_reissuesJwtAndUpdatesLastLoginDate`
- `chooseLocation_404OnBadLocationId`
- `me_returnsUserAndLocationAndEffectiveRoles`
- `signup_createsUserAndPersonAndSendsEmail` (mocks JavaMailSender)
- `signup_409OnDuplicateUsername`
- `signup_400OnWeakPassword`
- `passwordChange_verifiesCurrentBeforeSettingNew`
- `passwordChange_400OnWeakNew`
- `passwordChange_401OnWrongCurrent`
- `passwordResetRequest_alwaysReturns200`
- `passwordResetConfirm_validatesTokenAndUpdatesHash`
- `passwordResetConfirm_400OnUsedToken` / `expiredToken`
- `sha1AutoMigrate_acceptsSha1ThenStoresBcrypt` ← key migration test
- `cleartextStored_rejected` ← explicit negative test for the dropped fallback

JUnit run command (Phase 1 sudo lesson — TestContainers needs docker socket):
```bash
cd services && sudo -E ./gradlew :identity-service:test
```

### 11.2 Playwright E2E

New specs at `e2e/tests/identity-*.spec.ts`:

| Spec | Coverage |
|---|---|
| `identity-login.spec.ts` | Full login flow via React LoginModal; assert `obx_token` cookie set; subsequent `/api/identity/me` returns user |
| `identity-logout.spec.ts` | Login → logout → cookie cleared → `/api/identity/me` returns 401 |
| `identity-choose-location.spec.ts` | Login w/o location → chooseLocation → new cookie has updated `loc` claim |
| `identity-grails-shim-regression.spec.ts` | Exercise Grails GSP form login at `POST /openboxes/auth/handleLogin`; confirm shim forwards cookie; subsequent Grails-served page (`/openboxes/dashboard/index`) loads OK with identity-minted JWT |
| `identity-password-change.spec.ts` | Login → change password (with current-password check) → log out → log in with new password. Also re-uses a SHA-1-seeded fixture user to verify auto-migrate. |
| `identity-password-reset.spec.ts` | Trigger reset request → assert reset token row exists; complete reset via token; log in with new password |
| `identity-caller-regression.spec.ts` | Broad smoke through 4-5 Grails-served pages that read `session.user` / `authService.currentUser` (Dashboard, ProductList, InvoiceList, ShipmentList) — verifies the `User.get(claims.sub)` bridge still works after user table is identity-owned |

Optional, conditionally skipped if `OPENBOXES_SIGNUP_ENABLED=false`:
- `identity-signup.spec.ts` — signup via the GSP form; assert User + Person rows created; welcome email triggered (mock SMTP)

### 11.3 Spock integration tests

Existing Grails Spock tests that exercise auth flows (`grails-app/services/org/pih/warehouse/core/UserServiceSpec.groovy` etc., if they exist) — keep running as service-level regression. Don't migrate to identity-service Java in Phase 2 (port them in Phase 12 if still relevant).

## 12. Done-gate + soak

Following Phase 1 §13 pattern.

### 12.1 Done-gate

- [ ] Full clean rebuild + boot: `prepareDocker` + `:identity-service:bootJar` (Phase 1 retrospective lesson — bootJar MUST run before `docker-compose up --build` because identity-service Dockerfile COPIES the JAR) + `docker-compose down && up --build -d`. All 5 containers healthy: `openboxes-{db, app, document-service, identity-service, nginx}`.
- [ ] `wget -qO- http://identity-service:8082/actuator/health` returns `{"status":"UP"}` (via `docker exec` — port 8082 is `expose:` only, not host-reachable; Phase 1 lesson).
- [ ] JUnit pass: `cd services && sudo -E ./gradlew :identity-service:test` → 100%.
- [ ] All Playwright specs pass: 12 existing (Phase 0 + Phase 1) + 7 new identity = 19 total.
- [ ] `grep -r "JwtService.issue\|jwtService.issue" grails-app/` returns ZERO hits (verifies removal).
- [ ] `grep -r "JwtService.buildSetCookieHeader" grails-app/` returns ZERO hits (verifies removal).
- [ ] `grep -r "user.lastLoginDate = new Date" grails-app/` returns ZERO hits in DashboardController (verifies removal of the chooseLocation hidden writer).
- [ ] `wget -qO- http://identity-service:8082/v3/api-docs | jq` returns valid OpenAPI 3.0.1 with the 11 endpoints listed.
- [ ] Grails IdentityClient bean wired (covered by `identity-grails-shim-regression.spec.ts`).
- [ ] Cross-check: a user logging in via Grails GSP form (which now shims through identity-service) gets the same `obx_token` cookie shape (HttpOnly, SameSite=Strict, Path=/, Max-Age=28800) as a React-LoginModal-mediated login.
- [ ] SHA-1 auto-migrate verified at integration-test level AND with an end-to-end Playwright fixture user.

### 12.2 Soak (1-hour)

Same shape as Phase 1's done-gate:

- E2E suite iteration #1 (~5-8 min); capture `docker stats --no-stream` baseline for 5 containers
- 10-min wait; capture stats after-idle
- E2E suite iteration #2; capture stats after
- Manual exercise of ~15 min (login as admin, signup a test user via the GSP form, change password, request password reset, complete reset via token, log out, log back in)
- Log greps: `sudo docker logs openboxes-identity-service 2>&1 | grep -iE 'error|exception|warn'` (filter known startup INFO); `sudo docker logs openboxes-app 2>&1 | grep -iE 'User|Person|Role' | grep -iE 'error|exception'` (expect 0 hits)

Pass conditions: memory steady (no monotonic growth), zero unhandled errors, all manual flows pass UI-level.

### 12.3 Tag + retrospective

- Tag `phase-2-identity` at the clean Phase 2 HEAD (BEFORE the retrospective commit — Phase 1 precedent).
- Retrospective at `docs/retrospectives/<date>-phase-2-identity-retrospective.md` as a separate commit on top of the tag.

## 13. Verified assumptions

The following load-bearing assumptions were verified empirically against the codebase before this spec was committed.

| # | Assumption | Result | Key evidence |
|---|---|---|---|
| A1 | nginx routing pattern accepts `/api/identity` block above `/api/documents` | ✅ | `docker/nginx/conf.d/app.conf:11-23` — current `/api/documents` block is more specific than `/api/`; new `/api/identity` follows same pattern |
| A2 | Port 8082 unused | ✅ | `docker-compose-base.yml` only exposes 8080 (app), 8081 (document-service); no other binding |
| A3 | `services/settings.gradle` extends with `include 'identity-service'` | ✅ | `services/settings.gradle:2` shows the pattern (`include 'document-service'`) |
| A4 | `OPENBOXES_JWT_SECRET` env shared across services | ✅ | `docker-compose-base.yml:18` (app) and `:37` (document-service) — same env value; identity-service adds line ~50 |
| A5 | SecurityInterceptor JWT-validation block stays unchanged | ✅ | `SecurityInterceptor.groovy:38-59` only calls `jwtService.validate()`; nothing it depends on is being removed |
| A6 | `jwtService.issue` callers limited to 3 plant points | ✅ | grep returned 3 exact: `ApiController.groovy:55,69` + `AuthController.groovy:113`. (Note: parent-spec §7.2 cited slightly different line numbers — drift; plan uses actual lines.) |
| A7 | `ApiController.login` request/response shape | ⚠️ (design adapted) | `ApiController.groovy:43-61` — accepts `request.JSON.{username, password, location}` but returns plain text `[status:200, text:"Authentication was successful"]`, NOT JSON `{user, location}`. Calls `userService.authenticate(username, password)`. Uses `User.findByUsernameOrEmail`. Design's `POST /api/identity/login` returns the richer `{user, location}` JSON shape — Grails shim populates `session.user`/`session.warehouse` from response body to preserve Grails ThreadLocal behavior; React LoginModal gets the richer response too (improvement). |
| A8 | `chooseLocation` accepts id in path | ✅ | `ApiController.groovy:64` — `Location.get(params.id)` |
| A9 | `UserController.changePassword` lacks current-password check | ⚠️ (design adapted) | `UserController.groovy:278-299` — takes `params.id, params.password, params.passwordConfirm`; calls `userService.changePassword(user, password, passwordConfirm)`; no current-password verification. Design adds it for self-edit (forced-decision resolved in §10/§6); admin-edit endpoint preserves no-current-password semantic |
| A10 | Forgot-password / reset-password actions do NOT exist in Grails today | ❌ → NEW FEATURE | grep across `grails-app/controllers/` + `services/` returned only one comment match (no actions). Phase 2 builds these as new feature (forced-decision resolved); 2 new endpoints + email infrastructure + new `password_reset_token` table + React GSP-hosted reset page |
| A11 | `encodeAsPassword()` algorithm | ⚠️ → ADAPT | `grails-app/utils/org/pih/warehouse/PasswordCodec.groovy:18` — `MessageDigest.getInstance('SHA')` resolves to **SHA-1** (Java's default for "SHA" string), Base64-encoded via Apache Commons Codec, NO salt. Identity-service's verifier matches this exact algorithm (§10.1); BCrypt auto-migrate triggered on successful SHA-1 verify. Cleartext-storage fallback present in `UserService.groovy:494` is DROPPED by design (forced-decision resolved) |
| A12 | Person/User schema | ⚠️ (entities adapted) | `install/changelog-create-tables.groovy:1750` (person) + `:3644` (user). User table has NO `version`, NO `date_created`, NO `last_updated`. The `active` column was MOVED from user to person (per `0.8.x/changelog-2022-07-11-1300-move-active-column-from-user-to-person.xml`). JPA: `@Version` on Person only; User extends Person; `active` is a Person field |
| A13 | JOINED inheritance | ✅ | `Person.groovy:28` declares `tablePerHierarchy false`; install changelog has separate `person` + `user` tables with same id type CHAR(38) — `user.id` is PK and FK to `person.id`. JPA `@Inheritance(JOINED)` + `@PrimaryKeyJoinColumn` is correct |
| A14 | Role schema mappable | ✅ | `install/changelog-create-tables.groovy:2938` — id CHAR(38), version BIGINT, description, role_type VARCHAR(255) NOT NULL, name VARCHAR(255) NOT NULL |
| A15 | LocationRole schema mappable | ✅ | `install/changelog-create-tables.groovy:1184` — user_id, location_id, role_id, version INT, id, location_roles_idx. Note: `version` is INT (not BIGINT); design uses `@Version Integer` |
| A16 | user_role columns | ✅ | `install/changelog-create-tables.groovy:3673` — pure join table (user_id, role_id only); no PK, no version. JPA `@ManyToMany @JoinTable` is correct |
| A17 | id column type | ⚠️ (adapted) | CHAR(38) not VARCHAR(36). JPA uses `@Column(columnDefinition = "CHAR(38)")` |
| A18 | photo/dashboard_config types | ✅ | photo MEDIUMBLOB; dashboard_config LONGBLOB. JPA uses `@Lob @Column(columnDefinition = "...")` |
| A19 | version columns | ⚠️ (per-entity) | Person.version BIGINT, Role.version BIGINT, LocationRole.version INT, user_role no version, User no version. `@Version` on Person (Long), Role (Long), LocationRole (Integer) only |
| A20 | Hidden `lastLoginDate` writer | ❌ → resolved | `DashboardController.groovy:225` writes `user.lastLoginDate = new Date()` inside `chooseLocation` action (semantic overload — "last login" is touched on location change too). Design moves this semantic to identity-service's chooseLocation endpoint (forced-decision resolved); Grails block is DELETED. `JsonController.groovy:1609` only READS the field (`if (it.lastUpdated == it.lastLoginDate)`); no other writers |
| A21 | Admin write surface — bounded carve-out enumeration | ⚠️ (named in §15) | Writers: `UserController.{save:127, update:236, delete:350}` (admin CRUD), `RoleController` at `grails-app/controllers/.../core/RoleController.groovy`, `PersonController.{save:50,update:56}` (admin create), `ShipmentController:1083` + `CreateShipmentWorkflowController:171,1080` (Person-creation during shipment workflows), Importer services (`UserImportDataService`, `PersonImportDataService`, `UserLocationImportDataService`), `UserService` Sql.execute seed at line 397-399. Phase 2 carve-out documented in §15 |
| A22 | UserSignupEvent has single producer + single consumer | ✅ | Producer: `AuthController.groovy:209` (`publishEvent(new UserSignupEvent(...))`); consumer: `UserSignupEventService.groovy`. No others |
| A23 | RecaptchaService single integration | ✅ | Only `AuthController` calls `recaptchaService.validate(...)` |
| A24 | Grails MailService config | ✅ | Class at `org.pih.warehouse.core.MailService`; config under `grails.mail.*` (host, port, from, enabled — verified at AdminController.groovy:208-211 reads). Identity-service uses Spring Boot `spring.mail.*` env keys with same values |
| A25 | React URL constants location | ✅ (3 inline sites) | `LoginModal.jsx:24` (`/api/login`), `LoginModal.jsx:41` (`/api/chooseLocation/${id}`), `actions/index.js:220` (`/api/chooseLocation/${id}`). No central constants file. Plan touches 3 inline references |
| A26 | L2 cache state on auth domain classes | ✅ | None of User/Person/Role/LocationRole declares `cache` in mapping block. Default no-cache applies. No Phase 1-style cache flip needed |
| A27 | TestContainers BOM 1.21.3 override | ✅ | `services/document-service/build.gradle:4,27-28` shows the pattern + `api.version=1.44` system property + `testcontainers.ryuk.disabled=true`. Identity-service copies verbatim |
| A28 | Grails Spring DI for IdentityClient | ✅ | `grails-app/conf/spring/resources.groovy:23` — `documentClient(org.pih.warehouse.core.DocumentClient)`. New line: `identityClient(org.pih.warehouse.auth.IdentityClient)` |
| A29 | `response.setHeader('Set-Cookie', ...)` forwarding works | ✅ | Already used at `ApiController.groovy:56,70` and `AuthController.groovy:114,142` to set the JWT cookie locally; shim adapts to forward identity-service's header value instead of building one |
| A30 | 5-container memory headroom | ✅ (assumed from Phase 1 baseline) | Phase 1 4-container baseline ran stable at ~3GB total; identity-service estimated 500-700MiB (similar to document-service); 5-container target ~3.7GB well within dev-host capacity |
| A31 | Admin role-type detection from raw-ID JWT claims | ✅ (mechanism chosen) | `JwtService.groovy:39` claim is `user.roles?.collect { it.id }` (raw IDs, no RoleType info). `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:48-52` maps each ID to a `SimpleGrantedAuthority` literally. Identity-service maintains an in-memory `Map<roleId, RoleType>` populated by `SELECT id, role_type FROM role` at startup; admin-endpoint authorization checks `cache.get(claimRoleId) ∈ {ROLE_ADMIN, ROLE_SUPERUSER}`. Refresh strategy deferred to TWP |

## 14. Known issues / accepted as out of scope

- **Java 8 EOL on Grails container.** Same as parent §11 — stays until Phase 12.
- **Cleartext-stored passwords (if any) reject after Phase 2.** Any user row whose `password` column is literal plaintext (a legacy data-quality issue; existing in current codebase per `UserService.groovy:494` fallback) cannot log in via identity-service. They must reset via the new forgot-password flow. **Acceptance**: blast-radius probably zero or small on a single-developer dev DB; clean cutover is preferable to indefinitely supporting cleartext. Document in operator-facing change notes.
- **GORM `dirty` checking** on the Grails-side `session.user` may diverge from the identity-service-canonical row if a Grails admin action saves `user.active = false` and identity-service is mid-transaction issuing a JWT for that user. Acceptable race; bounded by JWT TTL.
- **Password reset emails depend on identity-service's MailSender config.** If SMTP is misconfigured, reset-request fails silently (`always-200` design). Operator must verify SMTP at deploy time. Document in operator runbook.
- **Welcome email (signup)** moves from Grails `UserSignupEventService` to identity-service. If signup is disabled (`OPENBOXES_SIGNUP_ENABLED=false`, the default), this path is unreachable.
- **Bounded admin-write carve-out** (see §15) is an explicit hybrid-state violation. Bounded; documented; resolved in Phase X or Phase 12 cleanup.
- **`UserService.groovy:397-399` SQL bootstrap path** writes a user row directly via `Sql.execute('insert into user ...')` with a SHA-1+Base64-hashed `"password"` literal. This is a seed/test bootstrap. It still works (SHA-1+Base64 is on the verifier path) and migrates to BCrypt on first login. Document in Phase X note.
- **L2 cache** wasn't explicitly flipped because none of the 4 domain classes declared `cache true`. If Grails defaults change in a future upgrade, revisit (per Phase 1 T4-I1 precedent).
- **Spring Security setup** in identity-service uses a stateless cookie-based auth (no session, no CSRF for API). Same pattern document-service uses; consistent.
- **`location_role.location_roles_idx` column** (Grails list-index for ordered hasMany) is preserved in the JPA entity for round-trip compatibility but identity-service writes don't strictly maintain it (a JPA `@OrderColumn` could be added if needed; Phase X concern).

## 15. Hybrid state (intentional, bounded)

By design, Phase 2 leaves the codebase in a strangler-fig hybrid:

- **identity-service is the authoritative writer** of user/person/role/user_role/location_role tables for all user-initiated paths (login, signup, password change, password reset, chooseLocation).
- **identity-service is the sole JWT issuer.**
- **Grails User.groovy, Person.groovy, Role.groovy, LocationRole.groovy STAY ALIVE** as read-bridges. ~97 `User.get|findBy` callsites + 30 Person callsites + 22 Role callsites + 54 `session.user`/`authService.currentUser` readers continue to work via GORM against the shared DB (no per-caller migration in Phase 2).
- **`AuthService.currentUser` ThreadLocal pattern** is preserved. Shims populate `session.user` from identity-service login response body, then existing SecurityInterceptor + AuthService logic runs unchanged.

### 15.1 Bounded carve-out (Grails writes that remain to identity-owned tables)

These specific Grails write paths continue to mutate identity-owned tables in Phase 2. Each is named, scoped, and bounded — and either resolved in a later phase or eliminated in Phase 12 cleanup:

| Caller | Write target | Why retained in Phase 2 | Resolution phase |
|---|---|---|---|
| `UserController.save/update/delete` (admin user CRUD via GSP) | user, person, user_role, location_role | Admin UI rebuild is days of GSP/React work; admin-rare; not on user-initiated auth lifecycle | Phase X (identity admin UI migration) or Phase 12 elimination |
| `RoleController` (admin Role CRUD) | role | Admin-rare | Phase X |
| `PersonController.save/update` (admin person CRUD via GSP) | person | Admin-rare | Phase X |
| `ShipmentController.groovy:1083` (`new Person()` during shipment workflow) | person | Shipping-slice concern; Person creation tied to shipment recipient/sender resolution | Phase 8 (Shipping slice) |
| `CreateShipmentWorkflowController.groovy:171,1080` (`new Person()` / `flash.personInstance = new Person()`) | person | Same as above | Phase 8 |
| `UserImportDataService`, `UserLocationImportDataService`, `PersonImportDataService` (bulk import services) | user, person, user_role, location_role | Admin bulk-import paths; rarely exercised; migration is its own concern | Phase X (or kept until Phase 12) |
| `LoadDataService.groovy` (data-loading bootstrap path) | user, person | One-off setup path | Phase 12 elimination |
| `UserService.groovy:397-399` (Sql.execute bootstrap seed) | user | Seed/test bootstrap | Phase 12 elimination |

Outside this list, no Grails code may write to user/person/role/user_role/location_role tables after Phase 2. The plan verifies this via grep at done-gate (§12.1).

## 16. Phase X: identity-service decoupling (deferred from Phase 2)

Placeholder for the eventual completion of Grails → identity-service for the auth slice. **Trigger to dispatch**: this phase runs once the following are answered (likely via a focused brainstorming + design-spec cycle):

1. **Admin UI fate.** Migrate UserController/RoleController/PersonController/LocationRoleController GSP screens to React + identity-service admin CRUD endpoints, OR retain GSP screens with thin shims that POST to identity-service, OR delete in Phase 12 only.
2. **Bulk import / data-loading paths.** Port UserImportDataService / PersonImportDataService / UserLocationImportDataService / LoadDataService to identity-service equivalents OR retain Grails-side with shim writes via identity-service.
3. **`User.findByUsername`/`User.get` Grails-side read bridges** — when do they go away? Each caller migrates to `IdentityClient.fetchUser(id)` in its own slice (likely incremental across many phases).
4. **`AuthService.currentUser` ThreadLocal pattern** survives until Grails dies (Phase 12). No Phase X action needed.
5. **`location_role.location_roles_idx` and other Grails-list-index columns** — once join tables move to identity-service ownership (if ever — see Phase X #6), decide whether `@OrderColumn` is preserved or dropped.
6. **Join-table ownership.** `user_role` and `location_role` join tables — stay identity-service-owned (current design); migrate further if a Phase X decomposition requires it.

**Owner**: TBD. Likely paired with the Phase 8 Shipping slice (which owns the Person-creation paths) for `ShipmentController` / `CreateShipmentWorkflowController` cleanup; the rest could be a dedicated mini-phase or absorbed in Phase 12.
