# Phase 0 (Foundations) Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Source spec:** `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` §7 (commit SHA: `34283a7`)

**Goal:** Establish migration foundations — nginx routing layer (additive), JWT-alongside-JSESSIONID auth in Grails (HMAC-HS256, `obx_token` HttpOnly SameSite=Strict cookie), Playwright E2E harness with baseline tests, and the ProductApi.js apiClient fix.

**Architecture:** Pure additive changes to the Grails monolith. No Grails code deleted; no schema changes; no Spring Boot services yet. nginx gains explicit `/api/` and `/openboxes/` location blocks (current config only has a catch-all `/`); Grails issues JWTs (jjwt 0.11.5) at all three login plant points; SecurityInterceptor accepts the cookie alongside the existing session; both logout endpoints clear the cookie; Playwright covers login + React nav + API auth + GSP regression. Rollback = revert.

**Tech stack:**
- Grails 3.3.16 / Groovy 2.4 / Java 8 (Grails container; unchanged in Phase 0)
- jjwt 0.11.5 (Java-8 compatible; new dependency)
- nginx 1.13 (existing; config refined)
- MariaDB 10 (unchanged)
- Playwright (JS/TS; new — lives in `e2e/`)
- React 16+ / webpack (unchanged; ProductApi.js: 1-line edit + dead-import removal)

## File Structure

**Create:**
- `grails-app/services/org/pih/warehouse/auth/JwtService.groovy` — HMAC-HS256 sign/validate; claims `{sub, loc, roles, exp}`; reads `OPENBOXES_JWT_SECRET` env var.
- `e2e/package.json` — Playwright deps + scripts (isolated from root React `package.json`).
- `e2e/playwright.config.ts` — Playwright config (Chromium baseline, base URL `http://localhost`).
- `e2e/tests/login.spec.ts` — `POST /api/login` returns 200 + sets `obx_token` cookie.
- `e2e/tests/react-nav.spec.ts` — React-hosted route returns 200 post-login.
- `e2e/tests/api-auth.spec.ts` — authenticated API call returns 200.
- `e2e/tests/gsp-regression.spec.ts` — GSP `/openboxes/admin/index` loads via JSESSIONID-based login.
- `.github/workflows/e2e-tests.yml` — CI job: docker-compose up + Playwright run.

**Modify:**
- `docker/nginx/conf.d/app.conf` — add `/api/` and `/openboxes/` location blocks; keep service name `app`.
- `docker/docker-compose-base.yml` — add `OPENBOXES_JWT_SECRET` env var to `app` service.
- `build.gradle` — add jjwt 0.11.5 deps (`compile`/`runtime` style matching existing).
- `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy` — inject `jwtService`; issue cookie at line 117 success branch; clear cookie at line 136 logout.
- `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy` — inject `jwtService`; issue cookie at line 41 login success; re-issue at line 55 chooseLocation; clear cookie at line 248 logout.
- `grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy` — inject `jwtService`; validate `obx_token` cookie at top of `before()` (line 35); fall through to existing session logic when missing/invalid.
- `src/js/api/services/ProductApi.js` — line 1: remove dead `import axios from 'axios';`; line 15: change `axios.get(...)` to `apiClient.get(...)`.

## Inherited from spec (NOT re-verified by this plan)

The following spec assumptions are treated as ground truth (verified by thorough-brainstorming at spec-write time and re-confirmed across CDR Rounds 1–6):

- **A4**: Grails container runs Java 8 Temurin (`docker/Dockerfile:1`). Constrains JWT lib to jjwt 0.11.x.
- **A5**: `AuthController.handleLogin` line 117 is the success branch.
- **A6**: `SecurityInterceptor` is the single auth chokepoint (`matchAll()` at line 27); zero `@Secured`/`@PreAuthorize` anywhere.
- **A7**: `AuthService` exposes `setCurrentUser(User)` and `setCurrentLocation(Location)` via ThreadLocal (`AuthService.groovy:24,37`).
- **A8**: No existing JWT infrastructure to conflict.
- **A18**: All React API calls go through `apiClient` EXCEPT `ProductApi.js:15` (raw axios) and `SupportButton.jsx:21` (HelpScout, out of scope).
- **A20**: All 13 jobs declare `def sessionRequired = false`; `BootStrap` has zero session references — Phase 0 changes don't break jobs.

## Verified plan-level assumptions

Newly introduced by this plan (paths, signatures, commands, ordering, code-in-plan validity, consumer impact) and verified at plan-write time against the codebase:

| # | Cat | Assumption | Evidence |
|---|---|---|---|
| P1 | path | `docker/docker-compose.yml` exists at root | `ls`: 831 bytes |
| P2 | path | `build.gradle` exists at root | `ls`: 36568 bytes |
| P3 | path | `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy` exists | `ls`: 14889 bytes |
| P4 | path | `grails-app/services/org/pih/warehouse/auth/` exists (3 existing services: AuthService, RecaptchaService, UserSignupEventService); `JwtService.groovy` will be NEW | `ls`: parent dir present |
| P5 | path | `src/js/api/services/ProductApi.js` exists | `ls`: 1249 bytes |
| P6 | path | `e2e/` does NOT yet exist | `ls`: no such directory |
| P7 | path | Root `package.json` exists (React build scripts); plan creates SEPARATE `e2e/package.json` to isolate Playwright deps from React bundle | `cat package.json`: React/webpack scripts |
| P8 | path | nginx config lives at `docker/nginx/conf.d/app.conf` (NOT inline in docker-compose.yml); volume-mounted into nginx container per `docker-compose-base.yml` | `cat docker-compose-base.yml`: `./nginx/conf.d:/etc/nginx/conf.d` |
| P9 | path | `.github/workflows/` exists with 10 existing workflows (backend-tests, frontend-tests, etc.); `e2e-tests.yml` is NEW | `ls`: existing CI workflows present |
| P10 | sig | `AuthController.groovy:136 def logout()` | `grep -n`: exact match |
| P11 | sig | `ApiController.groovy:41 def login()` (success render `render([status: 200, text: "Authentication was successful"])` inside the action body around line 50) | `grep -n` + `sed`: confirmed |
| P12 | sig | `ApiController.groovy:55 def chooseLocation()` (success render around line 62) | `grep -n` + `sed`: confirmed |
| P13 | sig | `SecurityInterceptor.groovy:35 boolean before()` | `grep -n`: exact match |
| P14 | sig | `ProductApi.js:15` contains raw `axios.get(INVENTORY_ITEM(productId, lotNumber))`; `apiClient` imported at line 11; `axios` imported at line 1 (becomes dead after fix; no other axios usage in file) | `cat`: exact match |
| P15 | sig | `ApiController.groovy:248 def logout()` (React-side logout — symmetric to GSP-side `AuthController.logout`; spec only mentioned GSP-side, user approved adding both) | `grep -n`: exact match |
| P16 | sig | User.id is `String` (UUID-generated per `static mapping { id generator: 'uuid' }`) — JwtService claims and lookups use `String` IDs | `User.groovy`: `String id` + `id generator: 'uuid'` |
| P17 | sig | `AuthService.setCurrentUser(User)` takes a User object (does internal `User.get(user.id)` for entity-manager safety); `setCurrentLocation(Location)` symmetric | `cat AuthService.groovy`: signatures confirmed |
| P18 | sig | AuthController service DI style: `def serviceName` (Groovy duck-typed); existing examples `def userService`, `def authService` | `head AuthController.groovy`: existing pattern |
| P19 | cmd | docker-compose v1 syntax (`docker-compose up`); existing docker-compose.yml uses legacy `services:` schema | `cat docker-compose.yml` |
| P20 | cmd | Commit message convention: lowercase imperative; no Conventional Commits prefix; UDD-style commits exist alongside manual commits | `git log --oneline -10`: "apply CDR Round 4 fixes", "ignore docs..." |
| P21 | cmd | Frontend lint command: `npm run eslint` (per `package.json:scripts.eslint`) — used for the ProductApi.js sanity check | `cat package.json` |
| P22 | order | Task 1 (nginx) and Task 2 (JWT) mutually independent | Different files, no shared symbols |
| P23 | order | Task 3 (ProductApi.js) independent of Tasks 1, 2 | Different file/layer |
| P24 | order | Task 4 (Playwright) depends on Task 2 (login test verifies obx_token cookie); soft-depends on Task 1 (tests reach Grails via nginx, but current `location /` catch-all also works); independent of Task 3 (api-auth test uses `/openboxes/api/dashboard/menu`, not ProductApi) | Test bodies in Steps 3-6 of Task 4 |
| P25 | order | Task 5 (done-gate + tag) depends on all above | By definition |
| P26 | code | jjwt 0.11.5 is the last Java-8-compatible release line; jjwt 0.12+ requires Java 11+ | Spec §7.2 verified during brainstorming |
| P27 | code | Grails 3.3.16 bundles Servlet 3.1 — native Cookie API lacks SameSite support. Implementation builds the `Set-Cookie` header manually via `response.setHeader('Set-Cookie', "obx_token=...; HttpOnly; SameSite=Strict; Path=/; Max-Age=28800")`. Standard Grails 3.x workaround. | Servlet 4.0+ would be required for native SameSite; out of scope (Java 8 / Grails 3.3) |
| P28 | code | Existing Grails service convention: `@CompileStatic` + `@Transactional(readOnly = true)` on class | `cat AuthService.groovy`: pattern confirmed |
| P29 | code | `SecurityInterceptor.before()` returns `true` to continue request, `false` to halt; existing code uses `return true` for the `status`/`menu` allow-through paths | `cat SecurityInterceptor.groovy`: pattern confirmed |
| P30 | code | docker-compose service name is `app` (NOT `grails` as spec literal §7.1 says); existing nginx config already uses `http://app:8080`. Plan corrects the spec's hostname literal. | `cat docker-compose-base.yml`: service `app`; `cat docker/nginx/conf.d/app.conf`: existing `proxy_pass "http://app:8080"` |
| P31 | code | `build.gradle` dependency style: `compile 'group:name:version'` (Gradle 4.x legacy syntax) is dominant for newer additions; e.g., `compile 'com.google.guava:guava:32.0.1-jre'`. New jjwt entries match. jjwt convention uses `compile` for `jjwt-api` and `runtime` for `jjwt-impl`/`jjwt-jackson` per the library's docs. | `grep "compile " build.gradle` |
| P32 | code | apiClient is `axios.create({})` with no base URL; URL interceptor handles prefixing as needed. Relative URLs in tests (`/api/login`, `/openboxes/api/...`) work through nginx | `cat src/js/utils/apiClient.js` |
| P33 | consumer | AuthController.handleLogin success branch: adding `Set-Cookie` header before `redirect(...)` — Set-Cookie on redirect responses is standard HTTP and supported by browsers | RFC 7234; universal browser behavior |
| P34 | consumer | ApiController.login success branch: adding `Set-Cookie` header before `render([status: 200, ...])` — Set-Cookie on JSON responses works | Standard HTTP |
| P35 | consumer | ApiController.chooseLocation: re-issuing cookie doesn't break `LoginModal.jsx` (consumer awaits response status, ignores headers) | Inferred from apiClient.put usage pattern |
| P36 | consumer | SecurityInterceptor.before(): inserting the JWT branch at the top of the method preserves existing session-based auth on the fall-through path. The branch sets `authService.currentUser` / `setCurrentLocation` exactly like the existing `session.user`-based logic does. | Existing flow at file lines ~37-39 |
| P37 | consumer | ProductApi.js: 10+ call sites in `src/js/` use `ProductApi.getInventoryItem(productId, lotNumber)` and destructure `const { data } = await ...`. `apiClient.get` returns the same axios Promise as raw `axios.get`, so callers are unaffected | `grep ProductApi`: callers in option-utils, hooks, components — all `await … then {data}` |

Cat 6 requirement satisfied (P33–P37 cover consumer impact for all `Modify:` entries).

## Tasks

### Task 1: nginx routing layer (§7.1)

**Files:**
- Modify: `docker/nginx/conf.d/app.conf`

- [ ] **Step 1: Add `/api/` and `/openboxes/` location blocks before the catch-all.** Replace the contents of `docker/nginx/conf.d/app.conf` with:

```nginx
server {
    listen 80;

    access_log /var/log/nginx/reverse-access.log;
    error_log /var/log/nginx/reverse-error.log;

    location /api/ {
        proxy_pass http://app:8080/openboxes/api/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Cookie $http_cookie;
    }

    location /openboxes/ {
        proxy_pass http://app:8080/openboxes/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Cookie $http_cookie;
    }

    # Catch-all (preserves existing behavior for unmapped paths)
    location / {
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_pass http://app:8080;
    }
}
```

Notes:
- Service name is `app` (NOT `grails` as spec §7.1 literal says — corrected per P30).
- `proxy_pass http://app:8080/openboxes/api/;` rewrites `/api/foo` → `/openboxes/api/foo` on `app`, preparing for future slice migrations (per spec §7.1: "Each subsequent slice adds one location block at the top of the list").
- Today both new blocks still forward to `app` (no Spring Boot services yet); the routing layer is the additive piece.

- [ ] **Step 2: Verify locally.**
```bash
docker-compose down
docker-compose up -d
# wait for app health (~30s)
for i in {1..30}; do curl -sf http://localhost/openboxes/health && break; sleep 5; done
curl -I http://localhost/openboxes/health
```
Expect HTTP 200 from the Grails health endpoint via nginx.

- [ ] **Step 3: Commit.**
```bash
git add docker/nginx/conf.d/app.conf
git commit -m "phase 0: add nginx /api/ and /openboxes/ routing blocks"
```

### Task 2: JWT auth plumbing (§7.2)

**Files:**
- Modify: `build.gradle`
- Create: `grails-app/services/org/pih/warehouse/auth/JwtService.groovy`
- Modify: `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy` (lines 117, 136)
- Modify: `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy` (lines 41, 55, 248)
- Modify: `grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy` (line 35)
- Modify: `docker/docker-compose-base.yml` (env var)

- [ ] **Step 1: Add jjwt dependencies to `build.gradle`.** Locate the `dependencies { ... }` block where existing entries like `compile 'com.google.guava:guava:32.0.1-jre'` live. Add adjacent:
```gradle
// JWT (Java-8 compatible line; replaces session-only auth incrementally)
compile 'io.jsonwebtoken:jjwt-api:0.11.5'
runtime 'io.jsonwebtoken:jjwt-impl:0.11.5'
runtime 'io.jsonwebtoken:jjwt-jackson:0.11.5'
```
Rationale: `jjwt-api` is the compile-time API; `jjwt-impl` and `jjwt-jackson` are runtime-only per jjwt's standard packaging.

- [ ] **Step 2: Create `grails-app/services/org/pih/warehouse/auth/JwtService.groovy`.**
```groovy
package org.pih.warehouse.auth

import grails.gorm.transactions.Transactional
import groovy.transform.CompileStatic
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.SignatureAlgorithm
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.security.Keys
import org.pih.warehouse.core.Location
import org.pih.warehouse.core.User

import javax.crypto.SecretKey
import java.nio.charset.StandardCharsets

@CompileStatic
@Transactional(readOnly = true)
class JwtService {

    static final String COOKIE_NAME = 'obx_token'
    static final int TOKEN_LIFETIME_SECONDS = 8 * 3600

    private SecretKey getSigningKey() {
        String secret = System.getenv('OPENBOXES_JWT_SECRET')
        if (!secret) {
            throw new IllegalStateException(
                'OPENBOXES_JWT_SECRET env var is required for JWT issuance/validation')
        }
        return Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))
    }

    String issue(User user, Location location) {
        Date now = new Date()
        Date exp = new Date(now.time + TOKEN_LIFETIME_SECONDS * 1000L)
        return Jwts.builder()
                .setSubject(user.id)
                .claim('loc', location?.id)
                .claim('roles', user.roles?.collect { it.id } ?: [])
                .setIssuedAt(now)
                .setExpiration(exp)
                .signWith(getSigningKey(), SignatureAlgorithm.HS256)
                .compact()
    }

    /** Returns parsed claims map, or null if token invalid/expired. */
    Map<String, Object> validate(String token) {
        try {
            return (Map<String, Object>) Jwts.parserBuilder()
                    .setSigningKey(getSigningKey())
                    .build()
                    .parseClaimsJws(token)
                    .getBody()
        } catch (JwtException ignored) {
            return null
        }
    }

    /** Build the Set-Cookie header value manually — Servlet 3.1 lacks native SameSite support. */
    static String buildSetCookieHeader(String token, boolean clear = false) {
        long maxAge = clear ? 0 : TOKEN_LIFETIME_SECONDS
        String value = clear ? '' : token
        return "${COOKIE_NAME}=${value}; HttpOnly; SameSite=Strict; Path=/; Max-Age=${maxAge}"
    }
}
```

- [ ] **Step 3: Wire JWT issuance into `AuthController.handleLogin` success branch.** In `grails-app/controllers/org/pih/warehouse/user/AuthController.groovy`:
  - Add `def jwtService` to the service-injection block (alongside `def userService`, `def authService`, etc., around line 22).
  - After the `if (userInstance?.warehouse && userInstance?.rememberLastLocation) { session.warehouse = ... }` block (around line 109) and BEFORE the `if (session?.targetUri)` check at line 112, add:
    ```groovy
    String token = jwtService.issue(session.user, session.warehouse)
    response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader(token))
    ```
    (Placement before the `targetUri` check ensures both redirect branches carry the cookie — inserting only before line 117 would skip the post-session-timeout re-login flow.)

- [ ] **Step 4: Clear cookie in `AuthController.logout`.** Same file, at the start of the `def logout()` body at line 136:
```groovy
response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader('', true))
```

- [ ] **Step 5: Wire JWT issuance into `ApiController.login`.** In `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy`:
  - Add `def jwtService` to the service-injection block (alongside `def localizationService`, etc., around line 35).
  - In `def login()` at line 41, after `session.user = User.findByUsernameOrEmail(...)` and the optional `session.warehouse = Location.get(...)` assignment, before the `render([status: 200, text: "Authentication was successful"])` line:
    ```groovy
    String token = jwtService.issue(session.user, session.warehouse)
    response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader(token))
    ```

- [ ] **Step 6: Re-issue JWT in `ApiController.chooseLocation`.** Same file, in `def chooseLocation()` at line 55, after `session.warehouse = location` and before `render(...)`:
```groovy
String token = jwtService.issue(session.user, location)
response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader(token))
```

- [ ] **Step 7: Clear cookie in `ApiController.logout`.** Same file, at the start of `def logout()` body at line 248 (symmetric with AuthController.logout — covers the React-side logout path):
```groovy
response.setHeader('Set-Cookie', JwtService.buildSetCookieHeader('', true))
```

- [ ] **Step 8: Wire JWT validation into `SecurityInterceptor.before()`.** In `grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy`:
  - Add `def jwtService` to the service-injection block (alongside existing `def authService`).
  - At the START of `before()` at line 35, BEFORE the existing `authService.currentUser = session.user ?: null` line, insert:
    ```groovy
    // Phase 0: accept obx_token JWT cookie alongside JSESSIONID.
    // Populate session.user/warehouse from JWT claims so existing safety checks
    // (deactivated-user, missing-user redirect, disabled-location, location-required)
    // run uniformly for both auth paths. The `!session.user` guard means a fresh
    // session takes precedence; the JWT path only fills in when the session is
    // missing/expired (typical case: server-side session TTL < 8h JWT TTL).
    def tokenCookie = request.cookies?.find { it.name == JwtService.COOKIE_NAME }
    if (tokenCookie?.value && !session.user) {
        Map<String, Object> claims = jwtService.validate(tokenCookie.value)
        if (claims) {
            User user = User.get((String) claims.get('sub'))
            if (user) {
                session.user = user
                session.userName = user.username
                if (claims.get('loc')) {
                    session.warehouse = Location.get((String) claims.get('loc'))
                }
            }
        }
    }
    // Fall through to existing session-based logic — all safety checks run unchanged
    ```
  - Also add the necessary imports at the top of the file:
    ```groovy
    import org.pih.warehouse.auth.JwtService
    import org.pih.warehouse.core.User
    import org.pih.warehouse.core.Location
    ```

- [ ] **Step 9: Set `OPENBOXES_JWT_SECRET` env var.** Edit `docker/docker-compose-base.yml` to add to the `app:` service's `environment:` block:
```yaml
        OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
```
Rationale: required by `JwtService.getSigningKey()`. Default value is acceptable for the no-live-users dev environment per spec §2.

- [ ] **Step 10: Local smoke test.**
```bash
docker-compose down
docker-compose up -d
# wait ~30s for app health
for i in {1..30}; do curl -sf http://localhost/openboxes/health && break; sleep 5; done

# verify cookie is issued by the React login path
curl -i -c /tmp/jar.txt -X POST http://localhost/api/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"password"}'
grep obx_token /tmp/jar.txt
```
Expect `obx_token` cookie present in the response.

- [ ] **Step 11: Commit.**
```bash
git add build.gradle \
  grails-app/services/org/pih/warehouse/auth/JwtService.groovy \
  grails-app/controllers/org/pih/warehouse/user/AuthController.groovy \
  grails-app/controllers/org/pih/warehouse/api/ApiController.groovy \
  grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy \
  docker/docker-compose-base.yml
git commit -m "phase 0: issue and validate obx_token JWT cookie alongside JSESSIONID"
```

### Task 3: ProductApi.js apiClient fix (§7.4)

**Files:**
- Modify: `src/js/api/services/ProductApi.js`

- [ ] **Step 1: Replace raw axios call and remove dead import.** In `src/js/api/services/ProductApi.js`:
  - Line 1: delete the line `import axios from 'axios';`
  - Line 15: change `axios.get(INVENTORY_ITEM(productId, lotNumber))` to `apiClient.get(INVENTORY_ITEM(productId, lotNumber))`

- [ ] **Step 2: ESLint sanity check.**
```bash
npm run eslint -- src/js/api/services/ProductApi.js
```
Expect 0 errors/warnings for this file.

- [ ] **Step 3: Commit.**
```bash
git add src/js/api/services/ProductApi.js
git commit -m "phase 0: route ProductApi.getInventoryItem through apiClient"
```

### Task 4: Playwright E2E harness + tests + CI hook (§7.3)

**Files:**
- Create: `e2e/package.json`
- Create: `e2e/playwright.config.ts`
- Create: `e2e/tests/login.spec.ts`
- Create: `e2e/tests/react-nav.spec.ts`
- Create: `e2e/tests/api-auth.spec.ts`
- Create: `e2e/tests/gsp-regression.spec.ts`
- Create: `.github/workflows/e2e-tests.yml`

- [ ] **Step 1: Create `e2e/package.json`.**
```json
{
  "name": "openboxes-e2e",
  "version": "0.0.1",
  "private": true,
  "scripts": {
    "test": "playwright test",
    "test:headed": "playwright test --headed",
    "install:browsers": "playwright install --with-deps chromium"
  },
  "devDependencies": {
    "@playwright/test": "^1.40.0",
    "typescript": "^5.3.0"
  }
}
```
Then bootstrap:
```bash
cd e2e
npm install
npx playwright install --with-deps chromium
```

- [ ] **Step 2: Create `e2e/playwright.config.ts`.**
```typescript
import { defineConfig, devices } from '@playwright/test';

export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // serial — Grails shares session state across tests
  retries: 0,
  use: {
    baseURL: process.env.BASE_URL || 'http://localhost',
    trace: 'retain-on-failure',
  },
  projects: [
    { name: 'chromium', use: { ...devices['Desktop Chrome'] } },
  ],
});
```

- [ ] **Step 3: Create `e2e/tests/login.spec.ts` — POST /api/login sets obx_token cookie (the spec's explicit done-gate test per §7.5).**
```typescript
import { test, expect } from '@playwright/test';

test('POST /api/login returns 200 and sets obx_token cookie', async ({ request }) => {
  const response = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(response.status()).toBe(200);
  const setCookies = response.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const hasObxToken = setCookies.some(h => h.value.includes('obx_token='));
  expect(hasObxToken).toBe(true);
});
```

- [ ] **Step 4: Create `e2e/tests/react-nav.spec.ts` — React route loads post-login.**
```typescript
import { test, expect } from '@playwright/test';

test('React-hosted route /openboxes/invoice/list is reachable after login', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
      location: process.env.E2E_LOCATION_ID,
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const navRes = await request.get('/openboxes/invoice/list');
  expect(navRes.status()).toBe(200);
  expect(navRes.url()).toContain('/invoice/list');  // catches silent redirect to chooseLocation when warehouse unset
});
```

- [ ] **Step 5: Create `e2e/tests/api-auth.spec.ts` — authenticated API call returns 200.**
```typescript
import { test, expect } from '@playwright/test';

test('Authenticated API call returns 200 (cookie-based auth)', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const response = await request.get('/openboxes/api/users');
  expect(response.status()).toBe(200);
});
```

- [ ] **Step 6: Create `e2e/tests/gsp-regression.spec.ts` — GSP admin page loads via JSESSIONID-based login.**
```typescript
import { test, expect } from '@playwright/test';

test('GSP /openboxes/admin/index loads after GSP-style login', async ({ request }) => {
  await request.post('/openboxes/auth/handleLogin', {
    form: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
  });
  const response = await request.get('/openboxes/admin/index');
  expect(response.status()).toBe(200);
  expect(response.url()).toContain('/admin/index');  // fails loudly if seed admin lacks rememberLastLocation and request was redirected to chooseLocation
});
```

- [ ] **Step 7: Add CI workflow `.github/workflows/e2e-tests.yml`.**
```yaml
name: E2E Tests (Playwright)

on:
  pull_request:
    paths:
      - 'grails-app/**'
      - 'src/js/**'
      - 'docker/**'
      - 'e2e/**'
      - '.github/workflows/e2e-tests.yml'
  push:
    branches: [main]

jobs:
  e2e:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Boot docker-compose stack
        run: |
          docker-compose up -d
          for i in {1..60}; do
            curl -sf http://localhost/openboxes/health && break
            sleep 5
          done
      - uses: actions/setup-node@v4
        with:
          node-version: '20'
      - name: Install Playwright
        working-directory: e2e
        run: |
          npm ci
          npx playwright install --with-deps chromium
      - name: Run E2E tests
        working-directory: e2e
        env:
          BASE_URL: http://localhost
          E2E_USER: admin
          E2E_PASSWORD: password
          E2E_LOCATION_ID: '1'  # CI maintainer: override with a real seed warehouse UUID if '1' is not valid in the test fixture
        run: npm test
      - name: Tear down
        if: always()
        run: docker-compose down
```

- [ ] **Step 8: Local run.**
```bash
cd e2e
E2E_LOCATION_ID=<your-seed-warehouse-id> npm test
```
Expect 4 tests, all green. If the seed user differs from `admin`/`password`, also set `E2E_USER` and `E2E_PASSWORD`. Find a valid warehouse ID via `docker exec openboxes-db mysql -u openboxes -popenboxes openboxes -e "SELECT id, name FROM location WHERE active=1 LIMIT 5"`.

- [ ] **Step 9: Commit.**
```bash
git add e2e/ .github/workflows/e2e-tests.yml
git commit -m "phase 0: add Playwright E2E harness with baseline tests + CI job"
```

### Task 5: Phase 0 done-gate verification + tag (§7.5)

**Files:**
- (none — verification + tag only)

- [ ] **Step 1: Full stack smoke.**
```bash
docker-compose down
docker-compose up -d
for i in {1..30}; do curl -sf http://localhost/openboxes/health && break; sleep 5; done
```

- [ ] **Step 2: Verify done-gate items per spec §7.5.**
  - [ ] `docker-compose up` brings up MariaDB + Grails + nginx (verified by Step 1)
  - [ ] Logging in sets `obx_token` cookie (verified by Task 2 Step 10 + Task 4 `login.spec.ts`)
  - [ ] API calls from React succeed (verified by Task 4 `api-auth.spec.ts`)
  - [ ] GSP `/openboxes/admin/index` still loads (verified by Task 4 `gsp-regression.spec.ts`)
  - [ ] All 4 Playwright tests green (re-run `cd e2e && npm test` if needed)

- [ ] **Step 3: Tag.**
```bash
git tag phase-0-foundations
git push origin main
git push origin phase-0-foundations
```

## Tasks NOT in this plan

(Inherited verbatim from spec §7.6 "Explicitly NOT in Phase 0")

- **No frontend build decoupling** (webpack continues to generate `common/react.gsp` and `partialReceiving/create.gsp`; React continues to be hosted by Grails). The 8+ Grails controllers that `render(view: "/common/react")` continue to work unchanged. Decoupling happens late, when most of those controllers have been deleted as part of their slice migrations — see Phase 12.
- **No OpenAPI spec for existing Grails API**. Grails endpoints are terminal — being deleted. springdoc-openapi gets added per slice on the Spring Boot side.
- **No shared JWT validation library**. Phase 1 establishes its shape based on one real consumer.
- **No external OIDC / Keycloak**. HMAC JWT is enough for one developer / no live users.
- **No refresh tokens, no token revocation, no JWKS**. Long-lived token, env-secret, single signing key.
- **No schema decomposition**. Each slice decomposes its tables when it extracts; Phase 0 doesn't touch the DB.
- **No deletion of any Grails code**. Phase 0 is purely additive.
- **No Java upgrade for the Grails container**. Stays Java 8.
- **No saga infrastructure**. Built in Phase 7 when first needed.

## Known issues inherited from spec

(Inherited verbatim from spec §11 "Known issues / accepted as out of scope" — Phase 0 relevant items only)

- **Java 8 EOL on Grails container.** Grails stays on Java 8 until Phase 12. Java 8 has been EOL upstream since 2022 (Oracle commercial support). Eclipse Temurin still provides Java 8 builds. Accepted because (a) no live users — security exposure is local-only; (b) Grails 3.3.16 + Groovy 2.4 fights Java 11+ in subtle ways; (c) the problem deletes itself in Phase 12.
- **Gradle 4.10.3 on Grails container.** Same logic — stays until Phase 12.
- **`SupportButton.jsx` calls HelpScout directly with raw axios.** Out of scope; third-party API; no auth required.
- **Webpack continues to write GSPs through Phase 11.** Cosmetic only — the GSPs are generated automatically; no manual maintenance.
