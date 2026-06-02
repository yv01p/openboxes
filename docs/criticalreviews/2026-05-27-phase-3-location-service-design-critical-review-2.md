# Critical Design Review: 2026-05-27-phase-3-location-service-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-27-phase-3-location-service-design.md`
**Verified Assumptions section:** present (§17)
**Reviewer commit baseline:** `0919955fd8622e39d32e41449109e0bee8bbc12c` (post-R1 UDD)

## 1. Verified-assumptions cross-check

All 15 verified assumptions (A1–A15) reconfirmed in Round 1 with cited evidence; no spec change in this round has invalidated any of them. The UDD edits only touched §0/§2/§3/§4/§5/§6/§9/§10/§15 — none of which modify §17's evidence base. Reconfirmed in bulk: no per-row re-litigation required this round.

## 2. Literal-wrongness findings

### Finding 1: §0 done-state paragraph contradicts the FD#2 decision applied in §3 + §9

**Spec text** (§0 line 38, the "Done-state, one paragraph"):
> "...serves `/api/location/{id}`, `/api/location?type=...`, `/api/location/group/{id}`, `/api/location/group`, `/api/location/type`, `/api/location/type/{id}`, `/api/location/supportedActivities` GETs; **bins/zones filtered out of GET responses by default**."

**Spec's actual asked-for behavior** (per FD#2(a) applied in R1 UDD, now at §3 lines 71–72 + §9 lines 186–187):
- §9 line 186: "Internal locations: `BIN_LOCATION`, `INTERNAL` (per Grails `LocationTypeCode.listInternalTypeCodes()` ...) — filtered by default"
- §9 line 187: "ZONE locations: NOT in the internal filter set (Grails parity ...)"
- §3 line 71: "Returns 404 if id is an internal location (LocationType.locationTypeCode in BIN_LOCATION/INTERNAL ...) ... ZONE locations are NOT filtered (Grails parity)."

**Evidence of the contradiction:** the §0 done-state says "bins/zones filtered" (i.e., all three of BIN_LOCATION + INTERNAL + ZONE filtered) — this was the pre-R1 behavior the spec used to claim. After R1 UDD applied FD#2(a), the actual filter set is `[BIN_LOCATION, INTERNAL]` (no ZONE). §0 was missed by the UDD pass; it still asserts the rejected pre-R1 behavior.

**Why this is literal-wrongness:** the spec's stated outcome — across §3 + §9 + §10 tests #3/#4/#7 + §16 risk row — is "internal locations (bins) filtered, zones pass through". §0 contradicts this with "bins/zones filtered". An implementer reading §0 alone would build the wrong filter set; an implementer reading §3+§9 would build the correct one. The spec asserts two mutually exclusive outcomes for the same behavior. By definition, at least one of them is "literally wrong".

**Proposed fix:** §0 line 38, replace:
> `bins/zones filtered out of GET responses by default.`

with:
> `internal locations (BIN_LOCATION/INTERNAL) filtered out of GET responses by default; ZONE locations pass through (Grails parity).`

### Finding 2: §2 Service Architecture row "Schema migrations" still references `columnExists` precondition

**Spec text** (§2 line 52, the Schema migrations row):
> "Schema migrations | Per-service Liquibase under `services/location-service/src/main/resources/db/changelog/`; shadow pattern (**`columnExists` precondition** + empty body) from commit 1 per Phase 2 retro lesson #5"

**Spec's actual asked-for behavior** (per R2 #2 fix applied in R1 UDD, now at §4 lines 105–117):
```xml
<changeSet id="phase3-shadow-create-X" author="openboxes-location">
    <preConditions onFail="MARK_RAN" onFailMessage="X table not found — Grails Liquibase must run first">
        <tableExists tableName="X"/>
    </preConditions>
    ...
</changeSet>
```

**Evidence of the contradiction:** the §2 row's parenthetical "(`columnExists` precondition + empty body)" describes the shadow pattern. The §4 detailed template now uses `<tableExists tableName="X"/>` (changed by R1 UDD). §2 was missed by the UDD pass; it still references the pre-R1 precondition type.

**Why this is literal-wrongness:** the spec internally documents two different shadow preconditions for the same pattern. A reader skimming §2 forms a different mental model than a reader of §4. While the detailed §4 template would win at implementation time (and the `tableExists` choice is the post-R1 correct one), the §2 summary asserts the pre-R1 wrong one. The spec asserts two mutually exclusive descriptions of the same primitive.

**Proposed fix:** §2 line 52, replace:
> `shadow pattern (\`columnExists\` precondition + empty body) from commit 1 per Phase 2 retro lesson #5`

with:
> `shadow pattern (\`tableExists\` precondition + empty body) from commit 1 per Phase 2 retro lesson #5 (template at §4)`

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

The following Round 1 findings are resolved by the R1 UDD pass (commit `0919955fd`):

- **R1 §2 #1 (auth claim narrowing)** — §5 line 125 now explicitly notes that `LocationApiController.list()` role-based filtering is NOT replicated; calls out the gap for Phase X retro. Applied per CDR option (a).
- **R1 §2 #2 (shadow precondition template)** — §4 lines 105–117 now use `<tableExists tableName="X"/>` uniformly for all 5 shadow changelogs. Applied per CDR option (b). (See R2 §2 Finding 2 for the partial-fix gap in §2 line 52 that this commit missed.)
- **R1 §3 FD#1 (SupportedActivitiesCache source)** — §3 line 77 + §6 lines 137–140 + §15 line 308 now describe the cache as `SupportedActivitiesEnum`, a hardcoded Java enum mirroring Grails `ActivityCode`, with sync debt explicit. Applied per user pick (a).
- **R1 §3 FD#2 (internal filter set)** — §3 lines 71–72 + §9 heading + §9 lines 186–187 + §10 tests #3/#4/#7 now filter only `[BIN_LOCATION, INTERNAL]` with ZONE-parity notes. Applied per user pick (a). (See R2 §2 Finding 1 for the partial-fix gap in §0 line 38 that this commit missed.)
- **R1 §1 A1/F1 precision note** — not applied; the assumption "still holds" in design intent (3 JPA entities is correct) and the skill rule says no-action on still-holds rows. The spec's §17 row A1 wording remains as-was.

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes**

Both R2 §2 findings are partial-fix gaps from R1 — UDD updated detail sections (§3, §4, §9) but missed the summary mentions (§0 done-state, §2 schema-migrations row). Both fixes are single-line text replacements; no design change. Apply via UDD, then proceed to TWP.
