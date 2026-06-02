# Critical Design Review: 2026-05-26-phase-2-identity-service-design (Round 1)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-26-phase-2-identity-service-design.md`
**Verified Assumptions section:** present (§13, A1–A30)

## 1. Verified-assumptions cross-check

Fresh-read sanity check against cited evidence for the load-bearing assumptions. All A1–A30 reconfirmed; selected re-verifications below.

- **A1 (nginx pattern)** ✅ Confirmed. `docker/nginx/conf.d/app.conf:11-23` shows `/api/documents` above `/api/`; a new `/api/identity` block above `/api/documents` follows the same prefix-specificity rule.
- **A2 (port 8082 unused)** ✅ Confirmed. `docker/docker-compose-base.yml` exposes only 8080 (app) and 8081 (document-service); port 8082 is free.
- **A4 (shared `OPENBOXES_JWT_SECRET`)** ✅ Confirmed at `docker-compose-base.yml:18` (app) and `:37` (document-service).
- **A6 (3 `jwtService.issue` plant points)** ✅ Confirmed by grep: `ApiController.groovy:55, :69` + `AuthController.groovy:113`.
- **A11 (PasswordCodec algorithm)** ✅ Confirmed. `grails-app/utils/org/pih/warehouse/PasswordCodec.groovy:18` uses `MessageDigest.getInstance('SHA')` (resolves to SHA-1) with `Apache Commons Codec Base64.encodeBase64` (padded, no chunking). Java's `Base64.getEncoder()` produces byte-identical output for 20-byte SHA-1 input.
- **A20 (`lastLoginDate` writer)** ✅ Confirmed. `grep -rn lastLoginDate grails-app/ src/main/` shows the only writer is `DashboardController.groovy:225` inside `chooseLocation`; `JsonController.groovy:1609` only reads. (Note: this re-confirms the assumption, but exposes an internal contradiction in §6.1 — see §2 finding 1 below.)
- **A28 (JWT roles-claim shape)** ✅ Confirmed. `grails-app/services/org/pih/warehouse/auth/JwtService.groovy:39` builds the claim as `user.roles?.collect { it.id }` — raw role IDs (CHAR(38) UUIDs), no `ROLE_` prefix. `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:48-52` consumes these IDs as raw `SimpleGrantedAuthority` strings. (Reconfirmation exposes a forced decision in §6.2 — see §3 finding 1 below.)

## 2. Literal-wrongness findings

### 2.1 `lastLoginDate` write timing — internal contradiction

The spec disagrees with itself on whether the login endpoint writes `user.last_login_date`.

- §6.1 login row (line 92) says: "Updates `user.last_login_date` on success."
- §6.1 chooseLocation row (line 94) says: "**Also updates `user.last_login_date`** (preserves the semantic from Grails `DashboardController.chooseLocation:225` which is removed)." The phrase "preserves the semantic" implies the original Grails behavior is being moved here.
- §13 A20 says: "Design moves this semantic to identity-service's `chooseLocation` endpoint (forced-decision resolved); Grails block is DELETED." Singular semantic, moved to one place.
- §11.1 (lines 407–425) lists `chooseLocation_reissuesJwtAndUpdatesLastLoginDate` but no analogous `login_updatesLastLoginDate`.
- Verified by grep: in current Grails, `DashboardController.groovy:225` is the only writer; `ApiController.login` (lines 43–61) does **not** touch `lastLoginDate`. So writing it at login is a new behavior, not a preserved one.

**Why this is literal-wrongness:** An implementer cannot tell whether to write `lastLoginDate` in the login service method. The spec asserts both yes (§6.1 login row) and no (§13 A20 + §11.1 test list). The done-gate grep at §12.1 line 465 (`grep -r "user.lastLoginDate = new Date" grails-app/` returns ZERO) doesn't disambiguate — it only verifies the Grails write is removed.

**Proposed fix:** Pick one. Recommendation: drop the lastLoginDate write from the login endpoint (delete the trailing sentence from §6.1 login row). Rationale: §13 A20 was the verified-and-resolved decision; "preserve existing semantic" is what was actually decided; the login row's addition appears to be drafting drift. If the intent was actually to extend the behavior (write at both login and chooseLocation), then keep it but add a test to §11.1 (`login_updatesLastLoginDate`) and update A20 to say "writes at login AND chooseLocation" rather than "moves this semantic."

### 2.2 §10.1 auto-migrate transaction semantics — three mutually exclusive claims

The auto-migrate description simultaneously asserts three properties that cannot all hold:

- §10.1 algorithm step 2 (line 372): "return true; **ASYNCHRONOUSLY** re-hash to BCrypt and UPDATE user row"
- §10.1 prose (line 383): "Auto-migrate happens in the **same JPA transaction** as the login (`@Transactional` boundary on the login service method)."
- §10.1 prose (line 383): "**If the DB write fails, login still succeeds** (verification was true); a WARN log records the migration miss for follow-up."

If sync + same transaction → DB write failure rolls back the entire transaction → login fails (contradicts claim 3).
If sync + nested REQUIRES_NEW transaction → DB write failure is isolated (matches claim 3) but it's no longer the "same transaction" (contradicts claim 2).
If async on a separate thread → can't be "same transaction" because JPA transactions are thread-bound (contradicts claim 2).

**Why this is literal-wrongness:** An implementer cannot write code that satisfies all three constraints simultaneously. Each combination violates at least one. The JUnit test `sha1AutoMigrate_acceptsSha1ThenStoresBcrypt` (§11.1) and the Playwright fixture-user test (§11.2 line 442) both implicitly assume *some* coherent behavior, but the spec doesn't specify which.

**Proposed fix:** Pick one. Recommended interpretation (cheapest + safest):

> On successful SHA-1 verify, synchronously re-hash to BCrypt and `UPDATE user SET password = ?` in a nested `@Transactional(propagation = REQUIRES_NEW)` boundary, wrapped in try/catch. On exception, log WARN and return true (login succeeds). On success, the migrated row is durable even if the outer login transaction rolls back for an unrelated reason.

This matches what the prose intends (login-succeeds-even-if-write-fails) without the "ASYNCHRONOUSLY" thread-boundary complication and without the impossible "same transaction" framing. Replace the algorithm-step phrasing and the prose paragraph with one consistent description.

### 2.3 `RoleType.groovy` path is wrong

§7.1 line 210: "`RoleType.java` — Java enum mirroring Grails RoleType. Values enumerated by reading `grails-app/domain/org/pih/warehouse/core/RoleType.groovy` during port."

Verified: `grails-app/domain/org/pih/warehouse/core/RoleType.groovy` does not exist. The actual file is at `src/main/groovy/org/pih/warehouse/core/RoleType.groovy`. (`find . -name RoleType.groovy` returns the latter only.)

**Why this is literal-wrongness:** An implementer following the spec literally would not find the source file at the cited path. RoleType has 40+ enum constants that must be enumerated correctly in the Java port; reading the wrong file (or no file) introduces porting errors.

**Proposed fix:** Update §7.1 to cite `src/main/groovy/org/pih/warehouse/core/RoleType.groovy`.

## 3. Forced decisions

### 3.1 Admin role-type detection mechanism

§6.2 line 103: "**Admin-edit endpoint.** Requires `ROLE_ADMIN` (or `ROLE_SUPERUSER`) in JWT claims. Does NOT require currentPassword. 403 if caller lacks admin role…"

But:
- §6 intro (line 86): "Roles claim keeps Phase 0/1 format — raw entity IDs like `R001` (NOT Spring `ROLE_*` prefixed; per T7-M3 follow-up)."
- `grails-app/services/org/pih/warehouse/auth/JwtService.groovy:39`: claims contain `user.roles?.collect { it.id }` — raw `Role.id` values (CHAR(38) UUIDs).
- `src/main/groovy/org/pih/warehouse/core/RoleType.groovy` shows `ROLE_ADMIN` and `ROLE_SUPERUSER` are values of the `RoleType` enum, which is the `Role.roleType` field — **not** `Role.id`. The DB has one Role record per RoleType; the role's `id` is a CHAR(38) UUID with no relationship to the RoleType name.
- `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:48-52` shows the established pattern: each ID from `claims.get("roles")` becomes a `SimpleGrantedAuthority` with the ID string itself as the authority name.

So a Spring `@PreAuthorize("hasAuthority('ROLE_ADMIN')")` would never match — the authorities are CHAR(38) UUIDs, not the literal string `ROLE_ADMIN`.

**Why this is forced:** The codebase has committed to a JWT-claim convention (raw role IDs, per T7-M3) and a Spring authorities convention (IDs become SimpleGrantedAuthority strings, per document-service precedent). Identity-service must check for admin/superuser role *types*, but cannot deduce role type from a role ID without additional information. The spec hasn't picked which of the three viable mechanisms identity-service uses.

**Options:**

| Option | Mechanism | Cost | Drawback |
|---|---|---|---|
| (a) Per-request DB lookup | On every admin endpoint hit, `SELECT roleType FROM role WHERE id IN (?, ...)`, then check membership of `{ROLE_ADMIN, ROLE_SUPERUSER}`. | ~1 extra SELECT per admin call (small; admin calls are rare). | Adds DB roundtrip; identity-service is the role's home so the query is cheap. |
| (b) Startup-time id→roleType cache | At identity-service startup, `SELECT id, roleType FROM role`; keep in memory; reload on role mutation (which identity-service mediates). | One small query at boot; constant-time check thereafter. | Requires cache invalidation when admin CRUD (still in Grails per §15) mutates the role table — but the carve-out includes `RoleController`, so Grails writes could drift the cache without identity-service knowing. |
| (c) Extend the JWT claim shape | Include role types alongside IDs in the claim, e.g., `roles: [{id: "R001", type: "ROLE_ADMIN"}, ...]` or a parallel `roleTypes: ["ROLE_ADMIN", ...]` claim. | None at request time. | Diverges from Phase 0/1 convention (per A28); requires document-service awareness if it ever needs to check role types; T7-M3 documented the raw-IDs convention deliberately. |

User input needed. The choice has knock-on effects: option (b) interacts with the §15 admin-write carve-out; option (c) requires re-documenting the claim shape in parent spec §A28 and revisiting T7-M3.

(Note: this is not a literal-wrongness finding because the spec *could* be implemented under any of the three options — but the spec hasn't picked one, and the §6.2 prose "Requires `ROLE_ADMIN`… in JWT claims" is ambiguous about which mechanism realizes that.)

## 5. Recommendation

🛑 **Surface forced decisions to user** — §3 has 1 item that needs user input before TWP can produce an unambiguous plan. §2 has 3 literal-wrongness items that must also be addressed, but they can be fixed editorially (no forced choice — recommended fixes are stated inline).

Suggested order:
1. Apply the 3 §2 fixes via `update-design-doc` (each has a recommended resolution; quick edits).
2. Bring the §3 admin-detection decision to the user (one of three options); record the choice in §6.2 + add an assumption to §13 capturing the chosen mechanism.
3. (Optional) Round 2 CDR if §3's resolution introduces enough new content to warrant another pass; otherwise proceed to TWP.
