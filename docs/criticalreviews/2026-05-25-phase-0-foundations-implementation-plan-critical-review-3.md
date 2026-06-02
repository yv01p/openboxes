# Critical Implementation Review: 2026-05-25-phase-0-foundations-implementation-plan (Round 3)

**Plan:** `/home/yv01p/openboxes/docs/plans/2026-05-25-phase-0-foundations-implementation-plan.md`
**Verified plan-level assumptions section:** present (37 assumptions P1–P37)

⚠️ 3 commits since plan-write spec SHA (34283a7): `4c288cf` (plan creation), `3c938f2` (Round 1 fixes applied), `8fe544e` (Round 2 fixes applied). All are plan/UIP commits — no code drift in cited surfaces. Cited file:line references re-checked under §1.

## 1. Verified-plan-assumptions cross-check

All 37 plan-level assumptions (P1–P37) reconfirmed. Spot-checks:

- **P11/P12/P13/P15**: line-precise method headers all still resolve (`AuthController.handleLogin:67`, `ApiController.login:41`, `ApiController.chooseLocation:55`, `SecurityInterceptor.before():35`, `ApiController.logout:248`).
- **P10**: `AuthController.logout:136` still resolves.
- **P36** (consumer-impact for SecurityInterceptor): re-verified post-Round 2 — the inserted JWT branch populates `session.user`/`session.warehouse` from claims with `!session.user` guard; existing flow at lines 37–135 reads `session.user` for all safety checks; the JWT branch is a peer of the session branch.
- **Round 2 fix sanity-check** (Step 3 placement): `grep -n` against `AuthController.groovy` confirms `session.warehouse = userInstance.warehouse` at line 108, `if (session?.targetUri) {` at line 111, `redirect(controller: 'dashboard', action: 'index')` at line 117. Plan's "around line 109" / "at line 112" wording is approximate-but-unambiguous given the search anchors (`if (userInstance?.warehouse && ...)` block, `if (session?.targetUri)` check) are unique in the file.

## 2. Literal-wrongness findings

No literal-wrongness findings.

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

Round 1's three §2 findings and Round 2's two §2 findings are resolved (the latter with one acknowledged scope-cap):

- **Round 1 §2.1** (SecurityInterceptor JWT branch returned `true` too eagerly, bypassing safety checks): resolved in `3c938f2`. Task 2 Step 8 populates `session.user`/`session.warehouse` with `!session.user` guard; existing safety checks run unchanged.
- **Round 1 §2.2** (`chooseLocation` NPE on `session.user.id` when only JWT auth is valid): resolved via Round 1's §2.1 fix.
- **Round 1 §2.3** (`api-auth.spec.ts` targeted non-existent `/openboxes/api/dashboard/menu`): resolved in `3c938f2`. Endpoint substituted to `/openboxes/api/users` (maps to `selectOptionsApi.usersOptions`; `endsWith("Api")` bypasses the location-not-set check).
- **Round 2 §2.1** (`handleLogin`'s `targetUri` early-return bypasses JWT issuance): resolved in `8fe544e`. Task 2 Step 3 now instructs insertion before the `if (session?.targetUri)` check at line 112 with an explanatory parenthetical — both redirect branches now carry the cookie.
- **Round 2 §2.2** (`react-nav.spec.ts` and `gsp-regression.spec.ts` false-positives on location-not-set redirect): resolved with explicit scope-cap. `react-nav.spec.ts` got option (1) [`location: process.env.E2E_LOCATION_ID` in login JSON] + option (3) [`expect(navRes.url()).toContain('/invoice/list')`]; CI workflow + Local Run docs updated with `E2E_LOCATION_ID` plumbing. `gsp-regression.spec.ts` got option (3) only (the `url()` assertion) — option (1) is a no-op against `handleLogin` (which ignores form `location` fields and only auto-sets `session.warehouse` from `userInstance.warehouse` when `rememberLastLocation=true`). The diagnostic-only fix means the test now fails loudly if the seed admin fixture lacks `rememberLastLocation` defaults — rather than silently false-passing. User explicitly approved this scope at UIP time.

## 5. Recommendation

✅ **Approve as-is.** §1 has no failed assumptions; §2 and §3 are both empty. The plan is internally consistent, plan-level assumptions ground-truth verified, and the Round 1+2 fixes have all landed correctly. Plan is ready for `superpowers:subagent-driven-development`.
