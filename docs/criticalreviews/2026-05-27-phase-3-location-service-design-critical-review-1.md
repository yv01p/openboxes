# Critical Design Review: 2026-05-27-phase-3-location-service-design (Round 1)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-27-phase-3-location-service-design.md`
**Verified Assumptions section:** present (§17)
**Reviewer commit baseline:** `7bc5064a68c07b518c0614375cd8d506a323d536`

## 1. Verified-assumptions cross-check

| # | Assumption / Finding | Cross-check verdict |
|---|---|---|
| A1 / F1 | "3 of 4 Grails domain classes exist; LocationStatus.groovy does NOT" | **Reconfirmed in design intent, with a precision correction.** `LocationStatus` exists as a Groovy `enum` at `src/main/groovy/org/pih/warehouse/core/LocationStatus.groovy:3-6` (values `ENABLED`, `DISABLED`). It is referenced by `Location.groovy:357-362 (getStatus())` and registered as a transient at `Location.groovy:132 (static transients = [..., "status", ...])`. The design decision (3 JPA entities) is still correct because LocationStatus is a derived computed enum, not a persisted entity. But A1's literal wording — "LocationStatus.groovy does NOT exist" — is false at the file-system level. Suggest updating §17 row A1 to: "LocationStatus is a transient computed enum (ENABLED/DISABLED) derived from `active` + `organization.active`; no domain class, no table; design correctly omits it." |
| A2 / F2 | "`location_status` table does NOT exist" | Reconfirmed in spirit (the LocationStatus enum existence is corroborating evidence — a transient computed status would not have a backing table). Full schema verification deferred to plan-time T1 audit per spec §9. |
| A3 | "`/api/locations/*` URL mappings resolve to LocationApiController" | Not re-verified at file level this round (handoff already verified). Holds. |
| A4 | "React uses `/api/locations/*` consistently" | Not re-verified this round (handoff already verified). Holds. |
| A5 | "identity-service maps Location as JPA entity" | Reconfirmed in handoff Step 1 (files exist). Holds. |
| A6 / F3 | "Bins/zones in same `location` table" | **Reconfirmed.** `Location.groovy:53` `Location parentLocation` + `Location.groovy:57` `Location zone` confirm self-referential FKs. `Location.groovy:368-378 (listNonInternalLocations)` confirms application-layer filtering by `locationType.locationTypeCode`. |
| A7 + A14 / F4 | "Writes localized to ~5 paths in `core/`" | Reconfirmed via `LocationApiController.groovy:149/170/216` (`save/update/delete`) and the noted callers. Holds. |
| A8 | "LocationApiController endpoints map cleanly" | **Partial caveat surfaced as §2 finding** — see §2 item 1 below. `LocationApiController.list()` is NOT a clean read mapping; it has role-based result filtering at `LocationApiController.groovy:73-92`. |
| A9 | "Liquibase shadow pattern works (no FK ordering)" | Reconfirmed via Phase 1 + Phase 2 precedent (`services/document-service/src/main/resources/db/changelog/changelog-2017-03-06-1953-document-code-shadow.xml`; `services/identity-service/src/main/resources/db/changelog/changelog-create-table-user-role.xml`). |
| A10 | "document-service + identity-service templates stable" | Reconfirmed via handoff Step 1 (containers Up healthy). |
| A11 | "services/settings.gradle controls module inclusion" | Reconfirmed (handoff already verified). |
| A12 / F6 | "JwtCookieAuthFilter is duplicated" | Reconfirmed (handoff already verified). |
| A13 | "nginx config supports prefix routing" | Reconfirmed (handoff already verified). |
| A15 | "Location has no inheritance hierarchy" | **Reconfirmed.** `Location.groovy:25` shows `class Location implements Comparable<Location>, java.io.Serializable` — no `extends`. F5 lifecycle-hook discovery noted at A15 is also reconfirmed (`Location.groovy:27-40`). |

## 2. Literal-wrongness findings

### Finding 1: §5 auth model claim is empirically false

**Spec text** (§5 line 125):
> "no role-based authorization needed (read-only endpoints; any authenticated user can read location data, matching current Grails LocationApiController behavior)"

**Spec's asked-for behavior** (§3 line 81):
> "Field-for-field match with existing Grails LocationApiController GET response shapes (so Phase 4+ callers can switch from `/api/locations/{id}` → `/api/location/{id}` with no payload reshape required)"

**Evidence:** `grails-app/controllers/org/pih/warehouse/api/LocationApiController.groovy:47-100` (the `def list()` method). Lines 73-92 perform extensive role-based result filtering driven by `params.locationChooser` + `userService.isUserRequestor()` + `currentUser.locationRoles` + role flags (`inRoleBrowser`, `inRoleAssistant`, etc.). Different authenticated users get DIFFERENT row sets for the same query parameters.

Concretely:
- Line 73-75: requestors without locationRoles + not browser → filtered to locations supporting `SUBMIT_REQUEST` AND `status == ENABLED`
- Line 76-78: requestors-in-any-location + browser → union of `getRequestorLocations` + `getLocations(..., locationChooser=true)`
- Line 80-92: branching based on `locationChooser` flag + role membership

**Why this breaks the asked-for behavior:** the spec's "drop-in replacement" promise (callers switch `/api/locations/...` → `/api/location/...` with no payload reshape) is broken for any caller that depends on the role-filtered list response. If location-service skips role-based filtering, the response BODY differs (different row count, different row IDs) for the same user. "Field-for-field" is structural but the spec's prose explicitly justifies the no-auth choice by "matching current Grails LocationApiController behavior" — which it does not.

The single-id `read()` endpoint at `LocationApiController.groovy:42-45` IS a clean unauthenticated read (no role filtering). The `list()` endpoint is not.

**Proposed fix:** Choose one:
- **(a)** Narrow the §5 claim to "matches `LocationApiController.read()` behavior; `list()` role-based filtering is intentionally NOT replicated — Phase 4+ list callers will see unfiltered results (any authenticated user sees all non-internal locations). Document as Phase X TBD if any current React caller depends on role-filtered lists." Then also amend §3 line 81 to remove the unconditional "drop-in" claim for list endpoints, or qualify it.
- **(b)** Implement role-based filtering in location-service. Requires location-service to know about `LocationRole`, `RoleType`, `User`-`role`-`location` joins — a significant Phase 2 / Phase X coupling that contradicts the "read-only minimal slice" framing.
- **(c)** Restrict Phase 3 to just `read()` and `type/*` endpoints (drop `GET /api/location?...` list endpoint entirely); add list when role-based filtering can be properly modeled.

### Finding 2: Shadow-changelog precondition template uses `columnName="id"` which doesn't apply to the 2 M:N join tables

**Spec text** (§4 lines 105-116, the shadow-pattern XML example):
```xml
<changeSet id="phase3-shadow-create-X" author="openboxes-location">
    <preConditions onFail="MARK_RAN" onFailMessage="X table not found — Grails Liquibase must run first">
        <columnExists tableName="X" columnName="id"/>
    </preConditions>
    ...
</changeSet>
```

**Spec also lists** 5 shadow changelogs at §4 lines 98-102, including:
- `changelog-shadow-create-location-supported-activities.xml`
- `changelog-shadow-create-location-type-supported-activities.xml`

**Evidence:** These two tables are pure GORM join tables created by `static hasMany = [supportedActivities: String]` (declared at `grails-app/domain/org/pih/warehouse/core/Location.groovy:71` and `grails-app/domain/org/pih/warehouse/core/LocationType.groovy:28`). GORM's default join-table columns for `hasMany: String` are `<owner>_id` + `<property>_string` — there is NO `id` column on `location_supported_activities` or `location_type_supported_activities`.

A literal copy-paste of the §4 example template using `columnName="id"` for these two join-table shadows would produce preconditions that ALWAYS fail (column doesn't exist), the `onFail="MARK_RAN"` then silently records the changeset as ran WITHOUT having verified the table exists at all. The stated precondition purpose ("X table not found — Grails Liquibase must run first") is bypassed. The downstream safety net is `spring.jpa.hibernate.ddl-auto: validate`, which would catch the table-missing case at boot — but only because the M:N tables are mapped through `@ElementCollection` on Location/LocationType (the join-table-only entities have no own JPA class to validate). For the entity tables (Location, LocationGroup, LocationType), the example works fine because they each have an `id` column.

**Phase 2 precedent** (correct pattern for join tables): `services/identity-service/src/main/resources/db/changelog/changelog-create-table-user-role.xml:9` uses `<columnExists tableName="user_role" columnName="user_id"/>` — i.e., a column known to exist on the join table.

**Why this is literal-wrongness:** the spec's example template, when applied verbatim to the listed M:N changelogs (which §4 explicitly enumerates), silently fails its stated invariant (asserting Grails ran first). With `@ElementCollection`, Hibernate's validate mode doesn't sub-verify the join-table schema as strictly as an entity table, so a missing join table could in some scenarios reach runtime queries before failing.

**Proposed fix:** clarify §4's example template, either:
- **(a)** Per-table column documentation: e.g., `location_supported_activities` use `columnName="location_id"`; `location_type_supported_activities` use `columnName="location_type_id"`; entity tables use `columnName="id"`. Add a table mapping changeset → precondition column to §4.
- **(b)** Switch all 5 shadow changelogs to `<tableExists tableName="X"/>` precondition (simpler, no column-name dependency, mirrors actual intent).

## 3. Forced decisions

### Decision 1: Source of values for `SupportedActivitiesCache` / `GET /api/location/supportedActivities`

**Why it's forced:** spec §3 line 77 + §6 line 135-136 describe `SupportedActivitiesCache` as a "small `Set<String>` of activity code values" served by `GET /api/location/supportedActivities`. But there is NO `activity_code` table in MariaDB — `ActivityCode` is a Groovy enum at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy:12-121` (~31 enum values). The existing Grails endpoint at `grails-app/controllers/org/pih/warehouse/api/LocationApiController.groovy:144-147` literally returns `ActivityCode.list().collect { it.name() }` — pure enum reflection, no DB. location-service has no enum to read from unless it declares its own.

The spec is silent on where location-service sources this list. The choice is forced and has implications for §15 (Known Issues) — the chosen approach adds a synchronization debt that should be documented there.

**Options:**
- **(a)** Hard-coded Java enum in location-service mirroring Grails `ActivityCode` (31 values). Adds enum-sync debt similar to the `LocationTypeCode.listInternalTypeCodes()` debt already in §15. The "cache" terminology becomes misleading (it's just enum reflection); rename to `SupportedActivitiesEnum`.
- **(b)** Query `SELECT DISTINCT supported_activities_string FROM location_supported_activities UNION SELECT DISTINCT supported_activities_string FROM location_type_supported_activities`. Returns only codes currently *in use*, not the full enum surface — likely incorrect for Phase 4+ callers that need to know about codes a UI could potentially set.
- **(c)** Add a new `activity_code` reference table during Phase 3 (with seed data). Substantial schema change; out of read-only Phase 3 scope.
- **(d)** Drop the `/api/location/supportedActivities` endpoint from Phase 3; defer to Phase X. Phase 4+ slices that need the enum hardcode it themselves until then.

### Decision 2: Definition of "internal" for the bin/zone filter

**Why it's forced:** the spec inconsistently references two different definitions of which `LocationTypeCode` values are "internal" and should be filtered out by default.

- §9 line 192: "The list of internal type codes is sourced from a Java enum in location-service mirroring `LocationTypeCode.listInternalTypeCodes()` from Grails."
- §3 line 71: "Returns 404 if id is a bin/zone (LocationType.locationTypeCode in **BIN_LOCATION/ZONE/INTERNAL**) unless `?includeInternal=true` query param"
- §9 line 182: "Bins/zones (internal): **`BIN_LOCATION`, `ZONE`, `INTERNAL`**, etc."

**Grails source-of-truth:** `src/main/groovy/org/pih/warehouse/core/LocationTypeCode.groovy:55-57` defines `static listInternalTypeCodes() { return [BIN_LOCATION, INTERNAL] }` — **NO ZONE**. ZONE is listed separately at `LocationTypeCode.groovy:67-69` `static listZoneTypeCodes() { return [ZONE] }`. The Grails `Location.listNonInternalLocations()` at `grails-app/domain/org/pih/warehouse/core/Location.groovy:368-378` filters using ONLY `listInternalTypeCodes()` — so ZONE locations ARE returned by Grails "non-internal" queries today.

If location-service mirrors `listInternalTypeCodes()` literally (per §9 line 192), ZONE locations pass through unfiltered. If location-service uses the §3/§9 prose set (`[BIN_LOCATION, ZONE, INTERNAL]`), it filters more aggressively than Grails — ZONE locations disappear from default responses, breaking drop-in compatibility for any caller that today gets ZONE rows from `/api/locations/...`.

**Options:**
- **(a)** Mirror Grails exactly: filter set = `[BIN_LOCATION, INTERNAL]`. ZONE locations passed through. Update §3 and §9 prose to remove ZONE from the example filter list. Matches existing Grails behavior; preserves drop-in promise.
- **(b)** Extend to filter ZONE too: filter set = `[BIN_LOCATION, INTERNAL, ZONE]` (union of `listInternalTypeCodes()` + `listZoneTypeCodes()`). Update §9 line 192 to read "union of `listInternalTypeCodes()` + `listZoneTypeCodes()`" with rationale. Document the deviation from Grails behavior in §15.
- **(c)** Configurable via query param: default = `[BIN_LOCATION, INTERNAL]` (Grails parity), `?includeInternal=true` includes BIN_LOCATION+INTERNAL+ZONE. Documents that "internal" semantics already differ between bin/zone and zones; gives callers control.

## 5. Recommendation

🛑 **Surface forced decisions to user**

§3 (forced decisions) has 2 items requiring user input — both rooted in source-of-truth ambiguities that the design hasn't resolved. §2 (literal-wrongness) has 2 items that should be addressed via `update-design-doc`. The verified-assumptions cross-check (§1) reconfirms the design's core decisions; one minor precision-of-wording note on A1/F1.
