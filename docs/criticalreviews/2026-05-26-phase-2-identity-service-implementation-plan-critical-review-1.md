# Critical Implementation Review: 2026-05-26-phase-2-identity-service-implementation-plan (Round 1)

**Plan:** `/home/yv01p/openboxes/docs/plans/2026-05-26-phase-2-identity-service-implementation-plan.md`
**Verified plan-level assumptions section:** present (P1–P48)

## 1. Verified-plan-assumptions cross-check

Fresh-read sanity check of cited evidence. All P1–P48 reconfirmed; selected spot-checks below.

- **P14 (`UserSignupEventService.groovy` path correction)** ✅ Reconfirmed. `find . -name "UserSignupEvent*"` returns `./grails-app/services/org/pih/warehouse/auth/UserSignupEventService.groovy` (not `core/`).
- **P15 (`UserSignupEvent.groovy` path correction)** ✅ Reconfirmed at `./src/main/groovy/org/pih/warehouse/auth/UserSignupEvent.groovy`.
- **P16 (`changePassword.gsp` does not exist; form is in `edit.gsp:132+`)** ✅ Reconfirmed; `ls grails-app/views/user/` shows no `changePassword.gsp`; password-tab confirmed at `edit.gsp:132-160`.
- **P21 (`userService.authenticate` has 2 callers; both become shims)** ✅ Reconfirmed: `grep -rn "userService\.authenticate"` returns `ApiController.groovy:46` + `AuthController.groovy:98` only.
- **P22 (`assignDefaultRoles` body)** ✅ Reconfirmed at `UserService.groovy:107-128` — reads `grailsApplication.config.openboxes.signup.defaultRoles`, splits comma-separated, looks up `Role.findByRoleType(roleType)`.
- **P30 (RoleTypeCache refresh-on-miss reload)** ✅ Reconfirmed — TWP user pick during plan-drafting was option (a) on-cache-miss reload; design is internally consistent in Task 6.
- **P4 (Dockerfile uses curl via apt-get, not wget — spec correction)** ✅ Reconfirmed at `services/document-service/Dockerfile`.

## 2. Literal-wrongness findings

### 2.1 Identity-service has no location data — `chooseLocation` silently drops the spec-required 404 / 403-disabled checks; `/me` + `chooseLocation` + `login` response bodies can't populate `location.name`

**Spec contract** (`docs/specs/2026-05-26-phase-2-identity-service-design.md`):

- §6.1 chooseLocation row: "Requires authenticated cookie. **404 if location not found, 403 if location disabled** or user lacks LocationRole for it."
- §6.1 login row: response body is `{user: {...}, location: {id, name}|null}`.
- §6.1 /me row: response body is `{user: {...}, location: {...}|null, effectiveRoles: [ids]}`.
- §6.1 chooseLocation row: response body is `{user: {...}, location: {...}, effectiveRoles: [ids]}`.
- §11.1 JUnit test list explicitly names `chooseLocation_404OnBadLocationId`.

**Plan's implementation** (`docs/plans/2026-05-26-phase-2-identity-service-implementation-plan.md`):

- Task 3 (JPA entities) creates Person/User/Role/LocationRole/PasswordResetToken — **no `Location` entity**.
- Task 7 Step 3 `AuthService.chooseLocation`:
  ```java
  boolean allowed = user.getLocationRoles().stream()
      .anyMatch(lr -> locationId.equals(lr.getLocationId()));
  if (!allowed) throw new AccessDeniedException("user lacks LocationRole for " + locationId);
  ```
  Only checks LocationRole — **silently drops** the 404-not-found and 403-disabled checks.
- Task 7 Step 3 `AuthService.me`: returns `MeResult` carrying only `locationId` (the JWT claim string), not a full Location record.
- Task 7 Step 4 `AuthController.chooseLocation`: builds `ChooseLocationResponse.from(result)` — the DTO can only populate `location.id` because `MeResult` / `ChooseLocationResult` have only the id.
- Task 13 Step 2 Grails shim `ApiController.chooseLocation` includes `catch (HttpClientErrorException.NotFound e)` — but identity-service can never throw 404 (no Location data), so the catch is dead code.

**Evidence the location data is needed and accessible:**

- `grails-app/domain/org/pih/warehouse/core/Location.groovy:63` — `Boolean active = Boolean.TRUE` field exists. "Disabled" = `!active`. This is the field the spec's "403 if location disabled" refers to.
- The location table lives in the same shared DB identity-service already connects to (per Task 2 Step 4 application.yml — same `DATASOURCE_URL`). So identity-service *could* read it; it just doesn't have an entity mapping.
- Spec §A12 (parent design spec) classifies location-service as Phase 3; identity-service treats `warehouse_id` as a flat FK CHAR(38) string (per spec §7.1 + plan Task 3 Step 4 User entity). The cross-context read for location-existence/disabled is not currently in scope.

**Why this is literal-wrongness:** The spec explicitly asks for three observable outcomes that the plan does not deliver:
1. `chooseLocation` returns 404 when location doesn't exist — plan's implementation returns 403 (if LocationRole check fails) or 200 (if LocationRole exists for the nonexistent location, which is technically possible if data is inconsistent).
2. `chooseLocation` returns 403 when location is disabled — plan's implementation has no path to this.
3. `login`, `chooseLocation`, `/me` response bodies include `location.name` — plan can only provide `location.id`.

A consumer of these endpoints (React `LoginModal.jsx` doesn't currently USE the response body, so it survives — but any future caller, including the spec-mandated JUnit test `chooseLocation_404OnBadLocationId` at §11.1, hits the gap). The JUnit test would fail at Task 16 execution time; the Playwright caller-regression test at Task 17 Step 8 would likely surface the missing `location.name` when rendering Grails-served pages that rely on session-derived location display.

**Proposed fix (mechanism is a §3 forced decision — see §3.1 below).** Whichever mechanism is chosen, the fixes to plan content are:

- Add a `Location` entity (or equivalent read-only access) to identity-service mapping at minimum `{id: CHAR(38), name: String, active: Boolean}` from the existing `location` table.
- Task 7 `AuthService.chooseLocation` adds, before the LocationRole check:
  ```java
  Location location = locationRepository.findById(locationId)
      .orElseThrow(() -> new LocationNotFoundException("location not found: " + locationId));
  if (Boolean.FALSE.equals(location.getActive())) throw new LocationDisabledException("location disabled");
  ```
  Plus `LocationNotFoundException → 404`, `LocationDisabledException → 403` in `GlobalExceptionHandler` (Task 10 Step 4).
- Task 7 `MeResult` / `ChooseLocationResult` / `LoginResult` carry a `Location` (or `LocationDto`) instead of `String locationId`, so the response DTOs can populate `{id, name}`.
- Task 13 Step 2 Grails shim's `catch (...NotFound e)` becomes live code.
- Done-gate (Task 18 Step 3) adds a grep / JUnit-test-pass assertion that `chooseLocation_404OnBadLocationId` and the equivalent disabled-location test exist and pass.

### 2.2 `Boolean.FALSE.equals(user.getActive())` changes the `active=null` behavior from Grails

**Plan** (Task 7 Step 3 `AuthService.login`):
```java
if (Boolean.FALSE.equals(user.getActive())) throw new AccountDisabledException("account disabled");
```

**Grails original** (`AuthController.groovy:90` — verified at plan-write time):
```groovy
if (!userInstance?.active) { /* redirect to login with "account under review" message */ }
```

**Difference:**
- Groovy `!userInstance?.active` evaluates true when `active` is `null`, `false`, or absent → blocks login.
- Java `Boolean.FALSE.equals(user.getActive())` evaluates true ONLY when `active == false` → null active **does not** block login.

**Evidence:** `grails-app/domain/org/pih/warehouse/core/Person.groovy` (per spec §A12, `active` is on Person; nullable per the schema). Some legacy users may have `active = NULL` in the DB.

**Why this is literal-wrongness:** Spec §6.1 login row says "403 on `!person.active`". The expression `!person.active` is Groovy semantics where null is falsy. The plan implements Java semantics where null is not falsy. Two real users (Grails-blocked vs identity-service-allowed) get different behavior. An admin who deactivated a user by setting `active = NULL` (rather than `active = false`) would find that user can log in via identity-service when they couldn't via Grails.

**Proposed fix:** Change the check to `!Boolean.TRUE.equals(user.getActive())` (treats null as not-active). Alternatively, if the codebase consistently uses `active = false` (never null), document that assumption and add a Task 16 JUnit test `loginNullActiveAccount_returns403` to lock it in. The Task 1 Step 4 live-probe should add a `SELECT COUNT(*) FROM person WHERE active IS NULL` to surface the actual data distribution.

## 3. Forced decisions

### 3.1 How does identity-service access location data?

**The choice:** Spec §6.1 requires identity-service to validate location-existence + location-active + populate `location.name` in response bodies (see §2.1 above). The plan silently picked "ignore the requirement" (no Location entity, no validation, no name). The user must pick the actual mechanism.

**Why it's forced:** The codebase has Location.active and Location.name fields the spec requires identity-service to read, but identity-service per the strangler-fig design owns only `{user, person, role, user_role, location_role, password_reset_token}`. The plan's design treats `warehouse_id` as a flat-FK CHAR(38) string — consistent with the spec's "location-service is Phase 3" framing — but that framing collides with the spec's §6.1 contract that asks identity-service to surface location data in responses.

**Options:**

| Option | Mechanism | Cost | Drawback |
|---|---|---|---|
| (a) Add a read-only `Location` JPA entity | Identity-service maps `location` table read-only (`id`, `name`, `active`). `LocationRepository.findById(id)` for chooseLocation/me/login response population. No writes from identity-service. | Small entity + repository (~30 LOC); no new DB connection. | Cross-context read into a table Phase 3 will eventually own — but read-only access is consistent with the broader strangler-fig pattern (Grails reads user/person via GORM today; identity-service reads location via JPA in this proposal). |
| (b) Defer to Phase 3; accept degraded Phase 2 behavior | Update spec §6.1 to drop the 404/disabled checks and the `location.name` field for Phase 2; identity-service returns `{location: {id: "<warehouseId>"}}` only. Phase 3 location-service adds a proper API; Phase 3+ revisits these endpoints. | Zero code; just spec edit. | Observable behavior regression vs. current Grails (which returns 404 for bad location and includes name in the chooseLocation response text). JUnit test `chooseLocation_404OnBadLocationId` must be deleted or rewritten. |
| (c) Have the Grails shim validate location before calling identity-service | `ApiController.chooseLocation` (Task 13 Step 2) does `Location.get(params.id)` first, throws ObjectNotFoundException early; identity-service trusts the input. | No identity-service code change. | Pushes the existence check back to Grails — but the spec's per-slice template puts identity-service as the validator. Also breaks for non-Grails callers (e.g., React calling `/api/identity/chooseLocation/{id}` directly via nginx). |
| (d) Identity-service calls back into Grails for location lookup | New `GET /openboxes/api/location/{id}` endpoint Grails-side; identity-service `LocationLookupClient` calls it. | New endpoint + new client. | Cross-service HTTP for read; circular dependency (Grails → identity-service for auth; identity-service → Grails for location lookup). Worst of all worlds. |

**Reviewer surfaces, never picks.** Option (a) appears cheapest and most consistent with the strangler-fig pattern, but option (b) is defensible if you'd rather lock Phase 2's scope tight and accept the spec edit + degraded behavior + test list shrink. Option (c) is awkward because it doesn't work for direct React access. Option (d) is a step back.

The choice has knock-on effects:
- Option (a) requires `additive-only schema` constraint relaxation? No — Location entity is read-only; no schema change. So additive-only is preserved.
- Option (b) requires CDR Round 3 to apply the spec edit (or a sub-pass through `update-design-doc`).
- Options (a), (c), (d) require updates to plan Tasks 3 (entity), 7 (service), 10 (security — `/api/identity/chooseLocation/**` permitAll?), and 16 (test).

## 5. Recommendation

🛑 **Surface forced decisions to user** — §3 has one item that needs user input before the plan can be revised to fix §2.1. §2 has two items; §2.1 is the major one (blocked on §3.1's resolution); §2.2 is a minor active-null behavior change with a straightforward editorial fix.

Suggested order:
1. Resolve §3.1 with user. The picked option determines the §2.1 fix shape.
2. Apply §2.1 fix per chosen option (touches Tasks 3, 7, 10, 13, 16).
3. Apply §2.2 fix (one-line change in Task 7 Step 3 + optional Task 1 Step 4 probe addition + optional Task 16 test addition).
4. (Optional) CIR Round 2 if §3.1 picks option (a)/(c)/(d) and introduces enough new entity/code to warrant another pass. Skip if option (b) (spec edit only).
