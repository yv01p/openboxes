# Critical Design Review: 2026-05-28-phase-4-organization-service-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-28-phase-4-organization-service-design.md`
**Verified Assumptions section:** present (now 28 rows after R1's UDD added A28)

## 1. Verified-assumptions cross-check

R1 spot-checked A4, A7, A11, A12, A20, A22 — all reconfirmed in R1. No spec changes touch their cited evidence (UDD edits were confined to §5.2, §5.3, §7, §11.1, §13, §17). All R1-reconfirmed assumptions still hold.

New row added by UDD:
- **A28** (`party.class` discriminator value Grails writes) — explicitly marked `⏳ PENDING T1 EMPIRICAL VERIFICATION`; **blocks T2**. Listed correctly as unverified; no fresh-read cross-check applies (nothing to verify against yet).

## 2. Literal-wrongness findings

### 2.1 Party (§5.1) lacks `@DiscriminatorValue`; bare-Party polymorphic reads fail if Grails writes a value other than the JPA default `"Party"` — R1 §2.2 fix addressed Organization but missed the symmetric problem on Party

**Description.** R1 §2.2 surfaced the unverified `@DiscriminatorValue` on Organization (§5.2). The UDD applied an A28-pending note above §5.2 and an A28 row to §17, but did not touch §5.1 (Party). Party is declared as a **concrete** entity (`public class Party`, not abstract) with `@DiscriminatorColumn` and **no `@DiscriminatorValue`** annotation (lines 119-124). When no `@DiscriminatorValue` is declared on a concrete entity under `SINGLE_TABLE` inheritance, JPA/Hibernate's default is the entity name — for `@Entity public class Party {}` (no explicit `@Entity(name="…")`), that's the simple class name `"Party"`.

The bare Party reads are part of the asked-for behavior:
- §6 line 295 exposes `GET /api/organization/party/{id}` ("Polymorphic party read (returns base shape regardless of discriminator)").
- §11.1 line 454 has a dedicated integration test `readPartyById_returnsBaseShapeForBareParty` against §11.1 line 470's seed row `1 bare Party row (class='org.pih.warehouse.core.Party', party_type_id='PERSON', for polymorphic Party-by-id test)`.

If A28's `SELECT DISTINCT class FROM party` returns `"org.pih.warehouse.core.Party"` for bare Party rows (consistent with the spec's FQCN guess for Organization), JPA's default `"Party"` mismatches and the polymorphic read either returns null (404) or throws on unknown discriminator — `readPartyById_returnsBaseShapeForBareParty` fails and the asked-for `GET /api/organization/party/{id}` is broken for any bare Party row.

The cited evidence has **changed** since R1 (UDD added an A28-pending note to §5.2 but not §5.1), so this is a partial-fix gap, not a re-raise of R1 §2.2. The fix R1 prescribed asks the implementer to "pin `@DiscriminatorValue` in §5.2" — but there is no `@DiscriminatorValue` in §5.1 to pin, and §17 A28 doesn't direct anyone to add one. Following the spec literally (and the A28 instructions) leaves Party still defaulting to `"Party"`.

A second symptom of the same gap: §11.1 line 470's bare-Party `class='org.pih.warehouse.core.Party'` is the same unverified-FQCN assumption as the Organization row above it (line 469), but UDD only annotated the Organization line with the A28-pending caveat. Line 470 still asserts the FQCN as if confirmed.

**Evidence.**
- Spec §5.1 lines 119-124 (no `@DiscriminatorValue` on concrete `Party`)
- Spec §6 line 295 (polymorphic party endpoint declared)
- Spec §11.1 line 454 (test for bare-Party read)
- Spec §11.1 line 470 (seed fixture uses unverified FQCN, no A28-pending note)
- Spec §17 A28 (verification step requires pinning §5.2's value only)
- `grails-app/domain/org/pih/warehouse/core/Party.groovy:23-25` (no discriminator override — same situation as Organization)

**Proposed fix.** Three coordinated edits:

1. **§5.1** — add an A28-pending note (analogous to §5.2's note) above the Party `@Entity` block, and add an explicit `@DiscriminatorValue("…")` to Party (the value is a placeholder, same as Organization's, to be pinned post-A28):
   ```java
   @Entity
   @Table(name = "party")
   @Inheritance(strategy = InheritanceType.SINGLE_TABLE)
   @DiscriminatorColumn(name = "class", discriminatorType = DiscriminatorType.STRING, length = 255)
   @DiscriminatorValue("org.pih.warehouse.core.Party")  // A28-pending — same as §5.2
   public class Party {
   ```

2. **§11.1 line 470** — append the same A28-pending caveat already present on line 469:
   > 1 bare Party row (class='org.pih.warehouse.core.Party', party_type_id='PERSON', for polymorphic Party-by-id test) *(class value pending A28 verification — placeholder above is the spec's provisional FQCN guess; replace with the observed `SELECT DISTINCT class FROM party` value before T9 implements)*

3. **§17 A28** — broaden the action so it covers **both** discriminator values:
   > Run `SELECT DISTINCT class FROM party` against a Grails-bootstrapped DB; pin `@DiscriminatorValue` in **both §5.1 (Party) and §5.2 (Organization)**, and both `class=` values in **§11.1 seed.sql (lines 469 and 470)**, to the observed values. **Blocks T2.**

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

R1 findings now resolved or partially resolved by UDD (commit `0ff57277a`):

- **R1 §2.1 (RoleTypeCode subset)** — fully resolved. `PartyRole.roleType` is now raw `String` in §5.3; PartyRoleService signature updated in §7; §13 known-issue bullet rewritten. The 60+-value enum-mirror failure mode is gone.
- **R1 §2.2 (Organization `@DiscriminatorValue` unverified)** — partially resolved. §17 A28 + §5.2 note + §11.1 line 469 caveat in place; verification step exists and blocks T2. **Symmetric gap on Party (§5.1) remains** — surfaced as §2.1 above.
- **R1 §2.3 (Organization.active default)** — fully resolved. §5.2 lines 182-183 now show `@Column(nullable = false, ...) private Boolean active = true;`.

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes.** Address §2.1 (the symmetric Party-discriminator gap) via `update-design-doc`. After the three-part edit lands, A28 will fully cover the SINGLE_TABLE discriminator surface and the design is ready for `thorough-writing-plans`.
