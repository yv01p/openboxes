# Critical Design Review: 2026-05-28-phase-4-organization-service-design (Round 3)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-28-phase-4-organization-service-design.md`
**Verified Assumptions section:** present (28 rows: A1-A27 + A28 pending T1 verification)

## 1. Verified-assumptions cross-check

R1 spot-checked A4, A7, A11, A12, A20, A22 and all reconfirmed. No spec changes since R2 touch the cited evidence for any of those. A28 remains correctly marked `⏳ PENDING T1 EMPIRICAL VERIFICATION` (now broadened to cover both Party and Organization discriminator values per R2's UDD). All verified assumptions still hold.

## 2. Literal-wrongness findings

No literal-wrongness findings.

R2's UDD applied the symmetric Party `@DiscriminatorValue` placeholder, broadened §17 A28 to cover both Party and Organization, and propagated the A28-pending note to §11.1 line 470. Spot-checks of `party_role` (lines 1624-1644), `party_type` (lines 1648-1678), and the party table (line 1607 `class VARCHAR(255) NOT NULL`) confirm the §5.3 / §5.4 JPA column declarations all exist in the physical schema; ddl-auto: validate has nothing to complain about at the JPA-mapping level. The R2 fix introduced no new partial-fix gaps.

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

- **R1 §2.1 (RoleTypeCode subset)** — resolved by R1's UDD (commit `0ff57277a`). §5.3 PartyRole.roleType is raw `String`; §7 PartyRoleService signature matches; §13 rewritten.
- **R1 §2.2 (Organization `@DiscriminatorValue` unverified)** — fully resolved across R1 + R2 UDDs. §5.2 placeholder + A28-pending note (R1); §17 A28 row added (R1) and broadened (R2); §11.1 line 469 caveat (R1). Empirical verification gates T2.
- **R1 §2.3 (Organization.active default)** — resolved by R1's UDD. §5.2 lines 185-186: `@Column(nullable = false, ...) private Boolean active = true;`.
- **R2 §2.1 (Party `@DiscriminatorValue` symmetric gap)** — resolved by R2's UDD (commit `436c555a1`). §5.1 now has `@DiscriminatorValue("org.pih.warehouse.core.Party")` placeholder + A28-pending note; §11.1 line 470 has the same caveat as line 469; §17 A28 explicitly enumerates both Party and Organization discriminator values to pin.

## 5. Recommendation

✅ **Approve as-is.** Spec is ready for `thorough-writing-plans`. The single remaining open item is A28's empirical verification, which is properly scoped as a T1 audit step that blocks T2 — that's an implementation-plan gate, not a design issue.
