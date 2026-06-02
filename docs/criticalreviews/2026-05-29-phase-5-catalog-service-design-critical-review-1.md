# Critical Design Review: 2026-05-29-phase-5-catalog-service-design (Round 1)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-29-phase-5-catalog-service-design.md`
**Verified Assumptions section:** present (§8, A1–A26)

## 1. Verified-assumptions cross-check

Spot-checked the highest-stakes load-bearing items by re-running the cited evidence cold. All reconfirmed:

- **A11** (no JPA inheritance in 9 in-scope entities) — fresh grep `extends|@Inheritance|abstract` across the cited 9 domain files returned zero hits. FD#2 ("nullability rule N/A") stands.
- **A15** (`ProductApi.js` is READ-ONLY for Product) — fresh read of `src/js/api/services/ProductApi.js` (37 lines) shows only `apiClient.get(...)` calls; zero `apiClient.post/put/delete`. FD#1 Product R/O stands.
- **A24** (port 8085 unused) — `grep -nE '808[0-9]' docker/docker-compose-base.yml` returned 8080 (app), 8081 (document), 8082 (identity), 8083 (location), 8084 (organization); 8085 free. Minor: A24 cites the file as `docker-compose-base.yml` (no `docker/` prefix) — actual path is `docker/docker-compose-base.yml`. Citation nit only; content correct.

Other §8 assumptions accepted as ground truth per skill protocol.

## 2. Literal-wrongness findings

### Finding 2.1 — FD#5 ProductGroup row: "Full CRUD (per `ProductGroupApi.js`)" citation is factually wrong

**Description.** FD#5 table row for ProductGroup (line 79) asserts `Full CRUD` with the explicit evidence parenthetical `(per ProductGroupApi.js)`. The cited file does not support this claim.

**Evidence (file:line).**

`src/js/api/services/ProductGroupApi.js` is 7 lines total:

```javascript
import { PRODUCT_GROUP_OPTION } from 'api/urls';
import apiClient from 'utils/apiClient';

export default {
  getProductGroupsOptions: () => apiClient.get(PRODUCT_GROUP_OPTION),
};
```

There is a single GET method. No `apiClient.post`, `apiClient.put`, or `apiClient.delete` exists anywhere in the file. The cited evidence does not show writes — it shows a single read against the dropdown-options endpoint `PRODUCT_GROUP_OPTION` (which `urls.js:96` defines as `${API}/productGroupOptions`).

Because FD#1 (line 27) explicitly grounds the write decision in `where React *Api.js POSTs/PUTs/DELETEs today`, and the cited file shows no such calls, FD#5's `Full CRUD (per ProductGroupApi.js)` row cites evidence that affirmatively *contradicts* the row's claim. The same factual error propagates to the §2 done state (line 17, `GET/POST/PUT/DELETE /api/productGroup{,/{id}}`).

**Proposed fix.** Remove the false citation. Either (a) change the row to `GET only (per ProductGroupApi.js: getProductGroupsOptions only)`, matching FD#1's rule, OR (b) keep `Full CRUD` and replace the citation with the *actual* basis (e.g., "anticipating Phase 5.5 React surface", "covering GSP admin migration", or whatever the real reason is — the spec must say). Update §2 done-state line 17's `/api/productGroup` endpoint listing to match.

This finding is a narrow factual error; the broader scope-question for the same entity (and three others) is in §3.1 below.

## 3. Forced decisions

### Finding 3.1 — Write scope for Category, Tag, Synonym, ProductGroup is not picked

**The choice.** Should catalog-service expose `POST/PUT/DELETE` for Category, Tag, Synonym, ProductGroup — or only GET? The spec asserts "Full CRUD" for all four in FD#5 (lines 72, 75, 76, 79) and §2 done state (line 17), but FD#1 (line 27) establishes the rule "writes only where React `*Api.js` POSTs/PUTs/DELETEs today", and the empirical React surface does not support the FD#5 assertion.

**Why it's forced.** The spec contains the rule and asserts a scope that the rule contradicts when applied to the current codebase. T1 audit is positioned to "confirm exact write surface per entity by reading `src/js/api/services/*Api.js` files" (§2 done state, line 17), but T1 cannot resolve the contradiction without a user-level decision on which side gives — the rule, or the scope. Implementation cannot proceed without knowing which endpoints to build.

**Empirical evidence (fresh greps, all paths absolute).**

| Entity | React `*Api.js` file in `src/js/api/services/` | Writes found |
|---|---|---|
| Category | **does not exist** (`ls src/js/api/services/Categ*` → no results) | none |
| Tag | **does not exist** (`ls src/js/api/services/Tag*` → no results) | none |
| Synonym | **does not exist** (`ls src/js/api/services/Synonym*` → no results) | none |
| ProductGroup | exists at `src/js/api/services/ProductGroupApi.js` | **GET-only** (see Finding 2.1) |

Broad fallback grep: `grep -rE 'apiClient\.(post\|put\|delete)' /home/yv01p/openboxes/src/js/` filtered for `category|/tag|synonym|productGroup` returns **zero hits**. There are no React writes to these URL families anywhere in the React codebase — neither in `*Api.js` services nor inline in components.

Inline-read evidence (also load-bearing for the resolution): `src/js/utils/option-utils.jsx:281` calls `apiClient.get('/api/categoryOptions')` and `:291` calls `apiClient.get('/api/tagOptions')` — both *inline string URLs*, NOT routed through `src/js/api/urls.js`. T1 audit's stated React-side enumeration scope is `src/js/api/urls.js` only (per line 255: "React `src/js/api/urls.js` enumeration per migrated endpoint"); inline-string callers would be missed under that scope.

**Options.**

- **(a) Shrink scope to GET-only** for Category, Tag, Synonym, ProductGroup. Matches FD#1 rule applied literally to current React surface. Reduces catalog-service controllers' write logic + DTOs + tests; reduces T8 React URL migration; reduces T9 test surface; reduces T10 Playwright write specs. Phase 5 done state §2 changes to GET-only for these four (still keeps full CRUD for Product per FD#1 — wait, Product is R/O too — actually, with this option, the only entity with writes in Phase 5 catalog-service is *none*, which makes the service effectively read-only across all 9 entities). Implication worth surfacing: if shrinking to all-GET, Phase 5 has the same shape as Phase 3 location-service (R/O), not Phase 4 organization-service (partial-strangler) — the FD#1 "partial-strangler per Phase 4" framing then no longer applies, and §1 TL;DR's "Partial-strangler per Phase 4 FD#1 pattern" should be revised to "Read-only slice per Phase 3 location-service pattern".
- **(b) Maintain Full CRUD scope** despite no current React callers. Rationale: anticipating Phase 5.5 React surface migration, or covering GSP admin paths that the spec wants to eventually replace. Requires modifying FD#1's rule to a less restrictive one (e.g., "writes for any client React + GSP admin + planned-Phase-5.5"), or replacing FD#1 with a different rule entirely. Spec must state which.
- **(c) Expand T1 audit's React enumeration scope** to grep all `src/js/**/*.{js,jsx,ts,tsx}` for inline `/api/*` URL strings (catches `option-utils.jsx` callers + any other inline-URL paths), AND enumerate every action of each catalog-area `*ApiController.groovy` (catches the `categoryOptions`/`tagOptions`/`productGroupOptions` actions). Then let T1 finalize the per-entity write scope empirically. The spec's done state §2 must then be downgraded from `GET/POST/PUT/DELETE /api/category...` to "per T1 audit (default: GET only, expand if T1 finds writers)" — i.e., remove the Full CRUD assertion entirely from the spec and defer to T1.

Option (a) is the most consistent with FD#1's stated rule. Option (c) preserves the spec's "T1 finalizes" deferral pattern but requires removing the FD#5 Full CRUD claim and adopting GET-only as the default until T1 evidence overrides. Option (b) requires the user to explicitly relax FD#1.

### Finding 3.2 — ProductClassification controller is unaddressed in spec scope

**The choice.** Should the catalog-area `ProductClassification` React + Grails surface move to catalog-service in Phase 5, defer to Phase 5.5, or stay alive in Grails indefinitely?

**Why it's forced.** Both files exist as a real catalog-area surface, but the spec does not enumerate `ProductClassification` anywhere — not in the 9 IN-SCOPE entities (FD#5 lines 71–79), not in the 13 DEFERRED list (FD#5 lines 81–95), not in the per-controller decision table (FD#4 lines 57–62 enumerates only `ProductApiController`, `CategoryApiController`, `ProductPackageApiController`, `ProductsConfigurationApiController`). T1 audit has no rule by which to decide ProductClassification's disposition — FD#4 explicitly says "per-controller decision", but ProductClassification isn't in the enumerated set.

**Empirical evidence.**

- `src/js/api/services/ProductClassificationApi.js` exists; reads file shows it has one GET method: `getProductClassifications: (facilityId) => apiClient.get(PRODUCT_CLASSIFICATIONS_API(facilityId))`.
- `grails-app/controllers/org/pih/warehouse/api/ProductClassificationApiController.groovy` exists; reads file shows it is 16 lines with a single `list(facilityId)` action that returns `ProductClassificationDto` from `ProductClassificationService.list(params.facilityId)`.
- `grails-app/domain/org/pih/warehouse/product/ProductClassification.groovy` **does not exist** (`ls grails-app/domain/.../product/` confirms — no entity at that name). ProductClassification is a controller-level abstraction with a service-layer projection, not a JPA-mappable entity.
- The controller is `facilityId`-scoped (`params.facilityId`), suggesting a Location/Facility coupling — analogous to how `ProductApiController.list` couples to inventory and `productAvailabilityService`. The pattern matches FD#12's "cross-context abstraction → stays alive" rationale for ProductApiController.

**Options.**

- **(a) Include ProductClassification in Phase 5.** Migrate the single `list(facilityId)` action + `ProductClassificationDto` + `ProductClassificationService.list(facilityId)` projection to catalog-service. ~1 controller, ~1 DTO, ~1 service projection. React URL constant `PRODUCT_CLASSIFICATIONS_API` migrates in T8. ProductClassificationApiController.groovy deleted in T8. nginx route added in T7. Note: this requires the `facilityId` parameter to be usable in catalog-service without taking a Location dependency — verify in T1 whether `ProductClassificationService` reads Location-context data.
- **(b) Defer to Phase 5.5.** Add to FD#5 deferred list with rationale ("Location-context coupling via `facilityId` param — defer to Phase 5.5 or roll into Phase 6 inventory with location data"). Phase 5 leaves ProductClassificationApiController + ProductClassificationApi.js untouched.
- **(c) Keep alive in Grails indefinitely.** Add to "stays alive" list alongside ProductApiController per FD#4 + FD#12. Rationale: cross-context abstraction depending on Location/Facility; analogous to ProductApiController's stays-alive decision. Phase 5 done state explicitly excludes ProductClassification.

## 5. Recommendation

🛑 **Surface forced decisions to user**

§2 contains one narrow literal-wrongness finding (Finding 2.1) — the ProductGroup citation in FD#5 is factually wrong and should be corrected.

§3 contains two forced decisions: (3.1) write scope for the four reference-data entities the spec claims are "Full CRUD" without empirical React-write support, and (3.2) ProductClassification surface unaddressed. Both require user input before plan-write can finalize T1, T6, T8, T9, T10 deliverables.

Resolving Finding 3.1 may also subsume Finding 2.1: if the user picks option (a) — shrink to GET-only matching FD#1 — the ProductGroup citation gets corrected in passing, and the spec internal consistency restores.
