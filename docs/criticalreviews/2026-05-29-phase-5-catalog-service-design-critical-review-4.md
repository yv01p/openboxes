# Critical Design Review: 2026-05-29-phase-5-catalog-service-design (Round 4)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-29-phase-5-catalog-service-design.md`
**Verified Assumptions section:** present (§8, A1–A26; A15 updated at R1 follow-up; no changes since R3)

## 1. Verified-assumptions cross-check

No §8 changes since R3. All assumptions previously spot-checked (A11, A15, A24) reconfirmed in R1+R2; no new evidence to reopen.

## 2. Literal-wrongness findings

No literal-wrongness findings.

The R3 fix is internally consistent: FD#9 (line 126), §6 bullet (line 298), and §7 risk bullet (line 321) all reference the same empirical finding + T1-deferred resolution protocol with consistent language. T1 task description (line 256) already includes "per-entity write-scope finalization (default GET-only for Category/Tag/Synonym/ProductGroup)" + "User approval gate before T2" — sufficient surface for the FD#9-deferred forced decision to fire at the T1 approval gate without requiring further T1 description edits.

## 3. Forced decisions

No forced decisions found.

The R3 fix transformed FD#9's broken safety claim into an explicit conditional deferral. The deferred decision is genuinely T1-conditional (only manifests if T1 finds Tag write callers); the spec has picked the deferral, so no §3 surfacing required.

## 4. Previously addressed

- **R1 §2.1 / §3.1 / §3.2** — resolved at R1 follow-up commit (`157849f24`) per R2 §4.
- **R3 Finding 3.1** — FD#9 false "unique-pair constraint" safety claim. Resolved at R3 follow-up commit (`c9bfaa829`) per user pick of option (b): drop claim + T1 forced decision if Tag writes. Spec edits applied at FD#9 (line 126), §6 known issues (line 298), §7 risks (line 321).

## 5. Recommendation

✅ **Approve as-is**

§2 and §3 are both empty. R3's finding is resolved by the spec's current state. Spec is ready for `thorough-writing-plans` to resume.
