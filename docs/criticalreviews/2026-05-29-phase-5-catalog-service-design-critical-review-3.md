# Critical Design Review: 2026-05-29-phase-5-catalog-service-design (Round 3)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-29-phase-5-catalog-service-design.md`
**Verified Assumptions section:** present (§8, A1–A26; A15 updated at R1 follow-up)

> **Provenance note:** R3 was triggered by an empirical finding surfaced during `thorough-writing-plans` execution (plan-write-time verification of FD#9's positive DB-schema claim). The finding fits CDR's §2 literal-wrongness category by the empirical-evidence + load-bearing-safety-claim test. CDR R3 captures it formally so `update-design-doc` can resolve via the standard pipeline.

## 1. Verified-assumptions cross-check

No §8 assumption changes since R2. A15's R2-reconfirmed inline-string evidence (`option-utils.jsx:281, :291`) still holds.

## 2. Literal-wrongness findings

### Finding 3.1 — FD#9 "product_tag is a join table with unique-pair constraint" is empirically false

**Description.** FD#9 (line 126) asserts: *"Concurrent writes from both sides could in theory race, but `product_tag` is a join table with unique-pair constraint — collisions surface as DB-level constraint violations, not silent data corruption."*

The bolded positive claim ("`product_tag` is a join table with unique-pair constraint") is the load-bearing premise for the safety claim ("not silent data corruption"). The premise is empirically false.

**Evidence (file:schema).**

`SHOW CREATE TABLE product_tag` against the running dev DB returns:

```sql
CREATE TABLE `product_tag` (
  `product_id` char(38) NOT NULL,
  `tag_id` char(38) DEFAULT NULL,
  KEY `FKA71CAC4A9740C85F` (`tag_id`),
  KEY `FKA71CAC4ADED5FAE7` (`product_id`),
  CONSTRAINT `FKA71CAC4A9740C85F` FOREIGN KEY (`tag_id`) REFERENCES `tag` (`id`),
  CONSTRAINT `FKA71CAC4ADED5FAE7` FOREIGN KEY (`product_id`) REFERENCES `product` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_general_ci
```

There is no `UNIQUE KEY` or unique constraint on `(product_id, tag_id)` (or any subset). The two `KEY` lines are single-column FK indexes only — they enforce nothing about pair uniqueness. Additionally `tag_id` is `DEFAULT NULL` (nullable), which is atypical for a strict M:N join table.

**Why this fails the literal-wrongness test.**

The asked-for behavior from FD#9 is "catalog-service can write `product_tag` rows safely alongside Grails Hibernate 5 writes." The safety argument has two parts:

- **Part A** (still holds): both sides resolve to the same DB table → no schema-level conflict.
- **Part B** (load-bearing claim): "*collisions surface as DB-level constraint violations, not silent data corruption*."

Part B is the safety guarantee. Without a unique constraint on `(product_id, tag_id)`, concurrent INSERTs of the same pair from both sides do NOT collide at the DB level — they silently succeed, producing duplicate rows. That is exactly "silent data corruption", which the spec asserts cannot happen.

**Conditional impact:**

- If Phase 5 keeps Tag write scope at the post-CDR-R1 default (GET-only per FD#5 / FD#1 / done-state §2): catalog-service never writes `product_tag`. The race cannot manifest in Phase 5. FD#9 becomes vacuous for Phase 5's deliverable but remains incorrect as a documented design claim, and becomes load-bearing at any future phase that adds Tag M:N writes (Phase 5.5? Phase 6?).
- If T1 audit finds Tag writes in scope (catalog-service POSTs/DELETEs that touch the M:N via JPA `Tag.products` collection cascade): the race is real and the spec's safety claim is broken. T1 has no spec-level guidance for what to do because FD#9 currently asserts the race is benign.

Either way the spec's design rationale is wrong — the only thing that varies is whether wrongness has an in-Phase-5 consumer.

**Proposed fix.** This is a forced decision — choose one and apply:

- **(a) Revise FD#9 to drop the "unique constraint" safety claim and add an explicit unique-pair-constraint Liquibase migration as a Phase 5 deliverable.** Adds a new task (or absorbs into T3) that issues `ALTER TABLE product_tag ADD CONSTRAINT uk_product_tag UNIQUE (product_id, tag_id)`. Risk: existing data may already have duplicate `(product_id, tag_id)` rows (since the constraint never existed) — pre-migration dedup query needed; could find unexpected data. Risk: this touches Grails schema; Phase 12 GSP-cleanup is the canonical owner of Grails schema changes; precedent matters.
- **(b) Revise FD#9 to drop the "unique constraint" safety claim and accept silent-duplicate-rows as a known issue inherited by Phase 5.** Document in §6. T1 audit's forced-decision protocol fires if T1 finds Tag writes: user decides whether to (i) accept the silent-duplicate risk, (ii) escalate to Phase 5.5/Phase 6 with explicit M:N coordination design, or (iii) implement application-layer pair-uniqueness check in `TagService` before insert (race-prone, but better than nothing). Smallest spec change; defers the real decision to T1.
- **(c) Revise FD#9 to drop the safety claim AND change Phase 5 Tag scope to GET-only definitively (override T1 default-deferred per CDR R1 §3.1 for Tag specifically).** Closes the door on the race entirely for Phase 5 by removing the only catalog-service write path that would touch `product_tag`. T1 audit no longer needs to consider Tag writes. Phase 5.5 / Phase 6 inherit the FD#9 reconsideration when Tag-writes-become-real.
- **(d) Accept the empirical reality as a design constraint: revise FD#9 to state that `product_tag` has no unique constraint and concurrent writes can produce silent duplicate rows; rely on application-layer dedup at read time.** No new task; the spec's existing tolerance for shared-DB concurrent-write hazards covers this. Minimal change; least defensible-for-future-phases but matches the existing "shared DB during transition" policy for cross-service reads.

## 3. Forced decisions

No additional forced decisions beyond Finding 3.1's proposed-fix choice (which is itself forced — pick exactly one of (a)/(b)/(c)/(d)).

## 4. Previously addressed

R1 + R2 findings remain resolved per R2's §4. No new state since R2.

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes**

§2 contains one literal-wrongness finding (Finding 3.1). The proposed-fix presents four options that are forced by the empirical evidence — pick one. §3 is empty (the choice IS the fix).

Once Finding 3.1 is resolved via `update-design-doc`, the spec is ready for `thorough-writing-plans` to resume.
