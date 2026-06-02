# Critical Implementation Review: 2026-05-28-phase-3-location-service-implementation-plan (Round 1)

**Plan:** `/home/yv01p/openboxes/docs/plans/2026-05-28-phase-3-location-service-implementation-plan.md`
**Verified plan-level assumptions section:** present (25 assumptions)
**Reviewer commit baseline:** `d2dc30113696b01c987297078fe9d1b6adf3f238` (plan-write HEAD)

(No drift note: source spec at `26ae7efef` + only 1 commit since = the plan commit itself; no codebase changes between plan-write and review.)

## 1. Verified-plan-assumptions cross-check

All 25 plan-level assumptions reconfirmed in bulk under fresh read. No spec/codebase changes between plan-write time and this review. Spot-checks:

- **A5** (CI workflow has identity probe + log dump): re-read `.github/workflows/e2e-tests.yml:44,78` — identity-service probe present at line 44; identity-service log dump at line 78. Holds.
- **A9** (JwtCookieAuthFilter depends on JwtService): re-read `services/identity-service/src/main/java/org/openboxes/identity/security/JwtCookieAuthFilter.java:23` (constructor takes JwtService) and `services/identity-service/src/main/java/org/openboxes/identity/service/JwtService.java:18,22,39` (COOKIE_NAME constant, constructor with `@Value("${openboxes.jwt.secret}")`, validate method). Holds.
- **A12** (Spring Boot 3.3.5 BOM): re-read `services/build.gradle:2` — `id 'org.springframework.boot' version '3.3.5' apply false` at the project root. Subprojects apply via line 17. Confirmed Spring Boot 3.3.5 is the inherited version.
- **A21** (nginx `/api/location/` trailing-slash location avoids collision with `/api/locations/`): nginx prefix-match semantics for location directive — trailing slash on `location` block requires the next character of the URI to be `/`, which `/api/locations/x` doesn't have. Holds. (NOTE: §2 Finding 1 below is a separate, NEW issue about the proxy_pass URL trailing slash, NOT the location trailing slash.)
- **A22** (nginx depends_on lives in `docker-compose.yml` not `base.yml`): re-read `docker/docker-compose.yml:31-41` — nginx depends_on block present with app + document-service + identity-service. `docker-compose-base.yml:73-80` nginx block has no depends_on. Holds.

All other assumptions (A1, A2, A3, A4, A6, A7, A8, A10, A11, A13–A20, A23–A25) reconfirmed via plan-time evidence; no fresh-read necessary given no drift in cited files.

## 2. Literal-wrongness findings

### Finding 1: nginx `proxy_pass` trailing slash strips the `/api/location/` prefix; T7 controllers won't match → every endpoint returns 404 through nginx

**Plan text** (T8 Step 1, mirroring spec §8 line 169-171):
```nginx
location /api/location/ {
    proxy_pass http://location-service:8083/;
    ...
}
```

**Evidence:** nginx `proxy_pass` semantics — when the proxy_pass URL ends with `/`, nginx replaces the portion of the request URI that matched the `location` directive with the proxy_pass URL's path. With `location /api/location/` matching prefix `/api/location/` and `proxy_pass http://location-service:8083/` having path `/`, a request for `/api/location/loc-001` arrives at location-service as `/loc-001`.

But plan T7 Step 1 maps controllers to:
- `LocationController` → `@RequestMapping("/api/location")` (line 14 of T7's first code block)
- `LocationGroupController` → `@RequestMapping("/api/location/group")`
- `LocationTypeController` → `@RequestMapping("/api/location/type")`

So the upstream path `/loc-001` does NOT match `/api/location/{id}` — DispatcherServlet returns 404 for every nginx-routed request.

**Existing precedent** (works correctly): `docker/nginx/conf.d/app.conf:11-12,18-19` — `proxy_pass http://identity-service:8082;` and `proxy_pass http://document-service:8081;` both OMIT the trailing slash, preserving the full URI. Identity-service's controllers map to `/api/identity/...` and receive the full path unchanged.

**Why this is literal-wrongness:** plan T9 + T10 tests assume nginx routing works; spec's "drop-in for Phase 4+ callers via `/api/location/{id}`" requires nginx to forward the path. As written, every endpoint returns 404 through nginx (works directly via `docker exec ... curl localhost:8083` because that bypasses nginx). The integration test at T9 uses MockMvc (bypasses nginx) so it would pass; the Playwright tests at T10 (which go through nginx) would all fail.

**Proposed fix:** T8 Step 1 — change `proxy_pass http://location-service:8083/;` → `proxy_pass http://location-service:8083;` (remove the trailing slash). Keep the trailing slash on `location /api/location/` (mandatory for `/api/locations/` collision avoidance per assumption #21). This matches the working identity-service + document-service pattern.

Also recommend a parallel fix to spec §8 line 170 (same trailing-slash bug in spec's nginx config diff) — surface to user for next CDR cycle or accept as plan-only correction.

### Finding 2: T7 `LocationController.list()` is missing `type` and `parentId` query param filtering that spec §3 + T9 test #5 require

**Plan text** (T7 Step 1, the `list()` method):
```java
@GetMapping
public List<LocationDto> list(@RequestParam(required = false) Boolean active,
                              @RequestParam(defaultValue = "false") boolean includeInternal) {
    ...
}
```

**Spec text** (§3 endpoint table row 2):
> `GET /api/location?type={typeCode}&active=true&parentId={id}` | Filtered list | Internal locations (BIN_LOCATION/INTERNAL) excluded by default; `&includeInternal=true` to include. ZONE locations not filtered.

**Plan T9 test #5** (the JUnit test that verifies type filtering):
> `5. GET /api/location?type=DEPOT returns matching locations only`

**Evidence:** the controller `list()` signature accepts only `active` and `includeInternal` params — no `type` (LocationTypeCode), no `parentId` (parent location FK). With the controller as written, `GET /api/location?type=DEPOT` ignores the `type` param and returns ALL non-internal locations (depot, zone, supplier, etc.) — test #5 assertion "matching locations only" fails because both depot AND zone rows are returned (zones aren't filtered per FD#2(a)).

**Why this is literal-wrongness:** the spec endpoint contract is broken (3 promised filters, only 2 implemented). The plan's own test #5 fails. Phase 4+ callers depending on `?type=` filtering can't use the endpoint as documented.

**Proposed fix:** T7 Step 1 — extend `list()` signature:
```java
@GetMapping
public List<LocationDto> list(@RequestParam(required = false) String type,
                              @RequestParam(required = false) Boolean active,
                              @RequestParam(required = false) String parentId,
                              @RequestParam(defaultValue = "false") boolean includeInternal) { ... }
```
And in T4 Step 6 — extend `LocationRepository.findAllExcludingInternal` (or add a new criteria-based query) to accept the additional filter params. A JPQL like:
```java
@Query("SELECT l FROM Location l WHERE " +
       "(:type IS NULL OR l.locationType.locationTypeCode = :type) AND " +
       "(:parentId IS NULL OR l.parentLocationId = :parentId) AND " +
       "(:active IS NULL OR l.active = :active) AND " +
       "(:includeInternal = TRUE OR l.locationType.locationTypeCode NOT IN :internalTypes)")
List<Location> findFiltered(@Param("type") LocationTypeCode type, @Param("parentId") String parentId,
                            @Param("active") Boolean active, @Param("includeInternal") boolean includeInternal,
                            @Param("internalTypes") Set<LocationTypeCode> internalTypes);
```
Or use a Specification/Criteria-based approach if multiple optional filters get unwieldy.

### Finding 3: T9 `@Sql({"/seed.sql"})` runs before EACH test method → second test fails on PRIMARY KEY duplicate

**Plan text** (T9 Step 2, class annotations):
```java
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql({"/seed.sql"})
class LocationServiceIntegrationTest { ... }
```

**Evidence:** Spring's `@Sql` annotation default `executionPhase` is `BEFORE_TEST_METHOD`. With 15 test methods listed in T9, seed.sql's `INSERT INTO location_type (id, ...) VALUES ('lt-depot-001', ...)` runs once before test 1 (succeeds), then again before test 2 (fails with duplicate-key error on PK `lt-depot-001`), and 13 more times (all fail). The test class can't pass beyond test 1.

**Existing precedent that works correctly:** `services/identity-service/src/test/java/org/openboxes/identity/IdentityServiceIntegrationTest.java:84` uses `r.add("spring.sql.init.data-locations", () -> "classpath:test-data/seed.sql");` inside `@DynamicPropertySource` — this triggers Spring Boot's SQL init mechanism, which runs ONCE at application-context startup (the @SpringBootTest context is shared across all test methods in the class), populating the schema once.

**Why this is literal-wrongness:** the test class would compile and the FIRST test method would pass, but tests 2-15 fail at execution time with `SQLIntegrityConstraintViolationException`. T9's done-state ("15 tests pass; BUILD SUCCESSFUL") cannot be reached as written. By extension, T12 done-gate Step 7 ("Run JUnit suite ... Expected: all 3 test suites pass") also fails.

**Proposed fix:** T9 Step 2 — remove `@Sql({"/seed.sql"})` class annotation; add to `@DynamicPropertySource` block:
```java
@DynamicPropertySource
static void props(DynamicPropertyRegistry r) {
    r.add("spring.datasource.url", mariadb::getJdbcUrl);
    r.add("spring.datasource.username", mariadb::getUsername);
    r.add("spring.datasource.password", mariadb::getPassword);
    r.add("openboxes.jwt.secret", () -> "test-secret-32-chars-minimum-for-hs256-key");
    r.add("spring.jpa.hibernate.ddl-auto", () -> "create");
    r.add("spring.sql.init.data-locations", () -> "classpath:seed.sql");
    r.add("spring.sql.init.mode", () -> "always");  // override "embedded" default; runs against TestContainers MariaDB
}
```
Seed.sql then runs once during context init, before any test method. Tests are read-only (per FD#3 — no mutations), so single seed load is sufficient.

### Finding 4: T5 Step 4 verification expects `HTTP/1.1 404` but Spring Security returns `401` before the dispatcher

**Plan text** (T5 Step 4, the verification block):
```
- Anonymous protected endpoint fails (will 401 once T7 controllers exist; for now 404):
  sudo docker exec openboxes-location-service curl -sI localhost:8083/api/location/anything
  Expected: HTTP/1.1 404 (no controllers yet)
```

**Evidence:** Spring Security 6's `FilterChainProxy` runs as a servlet filter, BEFORE `DispatcherServlet` matches URLs to controllers. With T5 Step 3's `SecurityConfig` declaring `anyRequest().authenticated()`, any request without authentication is rejected by `AuthorizationFilter` → `ExceptionTranslationFilter` → `AuthenticationEntryPoint` which returns `401 Unauthorized`. The request never reaches `DispatcherServlet`, so the "no controller mapping" 404 path doesn't execute.

This holds regardless of whether the requested path has a controller mapping or not — Spring Security's authorization check is path-independent under `anyRequest().authenticated()`.

**Why this is literal-wrongness:** the verification step instructs the operator/implementer to confirm `HTTP/1.1 404` as success. They'd see `HTTP/1.1 401`, conclude "the verification failed; something's wrong with my SecurityConfig," and waste time debugging a non-issue. The actual response is the CORRECT post-T5 behavior.

**Proposed fix:** T5 Step 4 — change the verification block to:
```
- Anonymous protected endpoint returns 401 (Spring Security rejects before dispatcher):
  sudo docker exec openboxes-location-service curl -sI localhost:8083/api/location/anything
  Expected: HTTP/1.1 401 (security filter chain runs before DispatcherServlet; this holds whether or not a controller mapping exists for /api/location/anything)
```

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

(Section omitted — no prior CIR reviews for this plan.)

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes**

§1 has no failed assumptions; §2 has 4 literal-wrongness findings (all execution-time, all surfacing static errors that would block T9/T10/T12 done-gate); §3 is empty. Apply fixes via `update-implementation-plan` (or manual edits), then proceed to `subagent-driven-development`.

Findings #1 and #2 break the spec's stated outcome at execution time (nginx 404s every endpoint; controller missing required filter params). Findings #3 and #4 break the plan's own verification/test contracts (seed conflict on test #2; verification step text incorrect).
