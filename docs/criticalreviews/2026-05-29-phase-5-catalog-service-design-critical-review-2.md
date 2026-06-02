# Critical Design Review: 2026-05-29-phase-5-catalog-service-design (Round 2)

**Spec:** `/home/yv01p/openboxes/docs/specs/2026-05-29-phase-5-catalog-service-design.md`
**Verified Assumptions section:** present (§8, A1–A26; A15 updated since R1)

## 1. Verified-assumptions cross-check

Spot-checked the assumption most affected by R1's revisions:

- **A15** (updated from ✅ to ⚠️ in R1 follow-up: "URL constants location" + inline-string caveat). Fresh grep `grep -nE "/api/(category|tag)Options" src/js/utils/option-utils.jsx` returned the two cited hits: `:281 const response = await apiClient.get('/api/categoryOptions');` and `:291 const response = await apiClient.get('/api/tagOptions', { params });`. A15's claim reconfirmed.

Other §8 assumptions accepted as ground truth per skill protocol (R1 already spot-checked A11, A24; no new evidence to reopen).

## 2. Literal-wrongness findings

No literal-wrongness findings.

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

R1 findings now resolved by the spec's current state:

- **R1 §2.1** — FD#5 ProductGroup row falsely citing `(per ProductGroupApi.js)` as evidence for "Full CRUD". RESOLVED: FD#5 line 80 now reads `Per T1 (default GET only)`; misleading citation removed.
- **R1 §3.1** — Write scope for Category, Tag, Synonym, ProductGroup contradicted FD#1 rule + empirical React surface. RESOLVED: user picked option (c) — defer per-entity write scope to T1 audit with expanded enumeration. Spec edits applied at §1 TL;DR (line 11), §2 done state (line 17), FD#1 (line 27), FD#5 rows (lines 73, 76, 77, 80), architecture diagram (lines 185–187, 194), T1 task description (line 256), A15 verified assumption (line 351). Default GET-only for the four; POST/PUT/DELETE added only for (entity, verb) pairs T1 finds callers for. T1 enumeration now covers all `src/js/**/*.{js,jsx,ts,tsx}` for inline `/api/*` strings + every action of each catalog-area `*ApiController.groovy`.
- **R1 §3.2** — ProductClassification surface unaddressed (ProductClassificationApi.js + ProductClassificationApiController.groovy exist). RESOLVED: user picked option (c) — keep alive in Grails. Spec edits applied at FD#4 (line 60, new "DEFINITIVELY STAYS" bullet citing the Location + InventoryLevel + Product cross-context query) and §6 known issues (line 296, ProductClassificationApiController stays-alive bullet alongside ProductApiController).

## 5. Recommendation

✅ **Approve as-is**

§2 and §3 are both empty. R1's three findings are all resolved by the spec's current state. Spec is ready for implementation planning (`thorough-writing-plans`).
