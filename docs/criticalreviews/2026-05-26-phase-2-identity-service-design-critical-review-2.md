# Critical Design Review: 2026-05-26-phase-2-identity-service-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-26-phase-2-identity-service-design.md`
**Verified Assumptions section:** present (§13, A1–A31)

## 1. Verified-assumptions cross-check

All A1–A30 reconfirmed in Round 1; no cited evidence has changed.

- **A31 (admin role-type detection via startup cache)** ✅ Reconfirmed. `grails-app/services/org/pih/warehouse/auth/JwtService.groovy:39` continues to build the `roles` claim as `user.roles?.collect { it.id }`. `services/document-service/src/main/java/org/openboxes/document/security/JwtCookieAuthFilter.java:48-52` maps each ID to a `SimpleGrantedAuthority` literally. The chosen mechanism — startup `SELECT id, role_type FROM role` populating an in-memory `Map<String, RoleType>` — is internally consistent with the §6.2 admin-edit row's prose. Refresh-strategy deferral to TWP is explicit.

## 2. Literal-wrongness findings

### 2.1 §10.1 — `DelegatingPasswordEncoder` is the wrong Spring Security class for the described algorithm

§10.1 (line 366): "identity-service `PasswordEncoder` is a `DelegatingPasswordEncoder` with two recognized formats. On every `verify(rawPassword, storedHash)` call: [algorithm with manual `$2a$`/`$2b$`/`$2y$` prefix detection on raw hash content]."

Spring Security's `DelegatingPasswordEncoder` (`org.springframework.security.crypto.password.DelegatingPasswordEncoder`) selects an encoder by parsing a leading `{id}` prefix on the stored hash, e.g., `{bcrypt}$2a$10$…` or `{noop}plaintext`. Without that prefix, `DelegatingPasswordEncoder` falls back to a single `defaultPasswordEncoderForMatches` — only ONE default encoder is configurable.

The spec's stored hashes have no `{id}` prefix:
- Legacy SHA-1+Base64 hashes are 28-char base64 strings (e.g., `qUqP5cyxm6YcTAhz05Hph5gvu9M=`).
- New BCrypt hashes start with `$2a$10$…` (60 chars).
- Neither carries `{bcrypt}` / `{sha1base64}` / any `{id}` prefix.

So a literal `DelegatingPasswordEncoder` implementation with `BCryptPasswordEncoder` as the default would:
- Succeed for unprefixed BCrypt hashes (Spring's default-encoder fallback path).
- **Fail** for unprefixed SHA-1+Base64 hashes (BCrypt verification on a non-BCrypt hash returns false).
- Break the auto-migrate-on-login design — legacy users get 401 instead of successful login + rehash.

The algorithm described in steps 1–3 (manual prefix detection on raw content) is correct, but it does NOT match `DelegatingPasswordEncoder`'s semantics. It matches a custom `PasswordEncoder` implementation.

**Why this is literal-wrongness:** An implementer following the spec literally would either (a) wire up Spring's `DelegatingPasswordEncoder` and watch legacy logins fail, or (b) notice the contradiction and silently invent a custom class — neither path follows the spec as written. The asked-for behavior (verify both formats from unprefixed storage) is impossible under Spring's `DelegatingPasswordEncoder`.

**Proposed fix:** In §10.1 line 366, replace:

> identity-service `PasswordEncoder` is a `DelegatingPasswordEncoder` with two recognized formats.

with:

> identity-service ships a custom `PasswordEncoder` implementation (`org.springframework.security.crypto.password.PasswordEncoder` interface) that recognizes two unprefixed stored-hash formats. Spring Security's built-in `DelegatingPasswordEncoder` is not used because the legacy SHA-1+Base64 hashes carry no `{id}` prefix and DelegatingPasswordEncoder only supports one default fallback encoder.

No §13 update needed — A11 already correctly describes the algorithm.

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

All R1 findings resolved by commit `a0d8d40e`:

- §2.1 (R1) `lastLoginDate` write-timing contradiction → §6.1 login row no longer claims to write `lastLoginDate`; only chooseLocation writes it (consistent with A20 + §11.1 test list).
- §2.2 (R1) §10.1 auto-migrate transaction semantics contradiction → algorithm step 2 + prose paragraph both now describe a single coherent mechanism (synchronous rehash in nested `REQUIRES_NEW` transaction, try/catch isolates failure).
- §2.3 (R1) `RoleType.groovy` path → §7.1 now cites `src/main/groovy/org/pih/warehouse/core/RoleType.groovy`.
- §3.1 (R1) admin role-type detection → §6.2 admin-edit row + new A31 capture the chosen mechanism (startup cache, refresh strategy deferred to TWP).

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes** — one §2 finding, no §3 forced decisions. The fix is a small editorial replacement (one paragraph in §10.1); no new design decisions required. Proceed to `update-design-doc` for the fix, then TWP.
