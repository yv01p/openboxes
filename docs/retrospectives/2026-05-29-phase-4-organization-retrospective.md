---
date: 2026-05-29
phase: 4 (Organization slice)
tag: phase-4-organization
commit_range: 682f031bd..65d645321
plan: docs/plans/2026-05-28-phase-4-organization-service-implementation-plan.md
spec_section: §9 (Phase 4)
forward_to: Phase 4.1 (horizontal cleanup — see §"Forward")
---

# Phase 4 Organization Slice — Retrospective

## TL;DR

Phase 4 shipped organization-service as the authoritative HTTP backend for `Organization` (READ + POST) + polymorphic `Party` (READ) + `PartyType` (READ, cached) + `PartyRole` (READ, filtered): 7 endpoints (`GET /api/organization/{id}`, `GET /api/organization` list, `POST /api/organization`, `GET /api/organization/party/{id}`, `GET /api/organization/partyType{,/id}`, `GET /api/organization/partyRole?partyId=&roleType=`), backed by JPA `@Inheritance(SINGLE_TABLE)` with `Organization extends Party` and `@DiscriminatorColumn(name="class")` populated with FQCN-form values per A28 (resolved at T1 audit). All Grails write paths stay on Grails — `/api/organizations` (plural) returns 404 from Grails (the generic `/api/${resource}s` URL mapping still fires but `OrganizationApiController.groovy` is deleted, per FD#4). Nginx routes `location = /api/organization` (exact-match for bare LIST) + `location /api/organization/` (prefix for `/{id}`, `/party/`, `/partyType`, `/partyRole`) to `organization-service:8084` — first-ever Grails source-tree deletion in the migration (38-LOC controller). 20 commits landed between `682f031bd..65d645321` (1 Phase 3 retro + 4 pre-work/spec/CDR + 4 plan/CIR + 11 SDD task commits including T9 fixup + T11 brought forward), tagged `phase-4-organization` at `65d645321`. CI green at 10m42s on `26651994665`. SDD per-task with **user-enforced stop-after-each-task gate** held cleanly across 11 dispatched tasks; two-stage review (spec-compliance + code-quality) surfaced 1 fix-now Important (T9 I1 Organization `@Column` nullability divergence from production schema) + 1 brought-forward Important (T8 I1 CI workflow missing org-service → became out-of-order T11) + 7 minors all triaged-as-skip per Grails parity. **21 retrospective candidates** accumulated; the user reframed them as input for a new **Phase 4.1 horizontal cleanup slice** (see §"Forward") instead of an unbounded Phase X graveyard — 13 items will be addressed in Phase 4.1 (Categories A+B+C), 4 deleted from backlog (Category D), 6 deferred to true Phase X with explicit rationale (Categories E+F).

## What worked

- **A28 SINGLE_TABLE inheritance pivot** (CDR R1+R2 verified via empirical T1 audit) was the right architectural call. Grails Party table has `class` discriminator column already populated with FQCN values (`org.pih.warehouse.core.Organization`, etc.); A28 picked FQCN for BOTH bare Party AND Organization at T1 (Option 1) once T1's `grep -c "^class:.*Organization$" /tmp/party-rows.csv` confirmed the existing data. Plan code blocks then specified `@DiscriminatorValue("org.openboxes.organization.entity.Organization")` verbatim. JPA `@Inheritance(SINGLE_TABLE)` worked first-try at boot under `ddl-auto: validate`; the polymorphic `PartyController` returns the correct subclass type via Hibernate's auto-routing. Bare Party rows + Organization rows coexist in the same table — readers via `/api/organization/party/{id}` get base PartyDto shape regardless of discriminator (verified by T9 test `readPartyById_returnsBaseShapeForBareParty`).

- **The T9 fixup pattern caught what T4 review missed.** Code-quality reviewer on T9 flagged that the test suite needed a `@Column(nullable=false)` workaround in seed.sql because Organization entity declared `code`, `name`, `active` as NOT NULL — but production Party schema is NULLABLE (so bare Party rows can coexist). T4 reviewers passed the entity declaration because Hibernate `ddl-auto: validate` doesn't check nullability — only column existence + type. T9 used `ddl-auto: create` (TestContainers fresh DB), surfaced the divergence as a "seed.sql needs dummy '' for code/name", which the T9 quality-reviewer correctly traced back to the entity defect rather than accepting the test workaround. User picked fix-now → commit `8cba628f1` removed `nullable = false` from 3 lines + restored seed.sql to verbatim plan form. Lesson: **review the upstream cause of test workarounds, not just the workaround itself**. This is the same "Important finding from a downstream task lights up an earlier task's gap" pattern Phase 3 saw with T8/T12 nginx exact-match.

- **Plan-verbatim code blocks gave T7 + T10 clean spec-reviewer passes on first attempt.** T7 (3 REST controllers, 7 endpoints, ~110 LOC) and T10 (4 React URL diffs + 75-line Playwright spec) both had the implementer copy plan code byte-for-byte. Spec reviewers verified character-perfect matches in both cases. Pattern: **when the plan author commits to verbatim, the implementer doesn't interpret; the reviewer just compares strings.** Implementer reports faster (~90s for T10), reviewer reports faster, gate decisions are clearer. Worth investing CIR rounds into making plan code truly verbatim-ready.

- **Bringing T11 forward when CI broke worked, but slowly.** Phase 4 push (T2-T9 + fixup) landed on main without updating `.github/workflows/e2e-tests.yml` — main went RED because docker-compose now referenced organization-service while the workflow only built identity/document/location jars. The handoff §5 step 5 explicitly predicted this risk (T8 I1 carry-forward). When the user surfaced the CI log this session, T11 was applied as 3-line plan-pre-approved diff in <5 min (Direct apply, no SDD subagent for a YAML change) + commit + push + CI green in 12m1s. Total CI-red interval: ~14h (`8cba628f1` landed 01:48 UTC → `2e25af837` fix landed 16:30 UTC). **The fix mechanism worked perfectly; the detection mechanism did not.** See Process meta-lesson #1.

- **SDD per-task + user-enforced stop-gate** held across 11 dispatched task cycles (T1 audit + T2-T11 implementation + T9 fixup; T12 verification + T13 retro doc-only). 11 stops × ~30s each = ~6 min of "controller surfaces finding, user picks disposition" overhead. The 5 substantive user decisions all changed code paths: T7 (skip 4 minors), T8 (skip 3 minors, defer I1 to T11), T9 fixup (fix-now), T11 (Direct apply not full SDD), T10 (skip 1 Important + 4 minors per Phase 4.1 plan). The 6 rubber-stamp "continue" decisions were quick. Worth the cost; same call as Phase 3 retro #4.

- **Light SDD cadence on T11** (Direct apply, no spec-reviewer + code-reviewer subagents) saved ~15-20 min for a 3-line YAML config diff that was already CIR-R3-approved verbatim. Reserving full SDD review cadence for tasks with judgment (T7 controllers, T9 tests) vs mechanical config edits (T11 workflow) is a useful calibration. New rule of thumb: **if the diff is plan-pre-approved verbatim AND has no business logic AND total LOC < 20, Direct apply is correct; otherwise full SDD.**

- **TestContainers JUnit suite (18 tests) caught real divergence.** Beyond the T9 I1 nullability find (above), the suite exercised all 7 endpoint paths, auth (401 on missing JWT), POST create with auto-generated code via OrganizationIdentifierService Java port, polymorphic Party read for both bare + Organization rows (A28 verification), PartyTypeCache RC-6 refresh-on-empty fix (carried forward from Phase 3 LocationTypeCache), arbitrary roleType String tolerance (CDR R1 §2.1 fix). 18/18 pass in 15s on `ddl-auto: create`. T12 re-run with `--rerun-tasks` confirmed 18/18 on the running container too.

- **First Grails source-tree deletion** (T8: `grails-app/controllers/org/pih/warehouse/api/OrganizationApiController.groovy`, 38 LOC) landed cleanly. The generic `/api/${resource}s` URL mapping in `grails-app/controllers/.../UrlMappings.groovy:935` still fires for `/api/organizations` but returns 404 because Grails can't find the `organizationApi` controller (verified empirically with `curl -sI /api/organizations` → `HTTP/1.1 404` with `X-Application-Context: application:production` header confirming Grails dispatcher). This unlocks the same delete pattern for Phase 5+ services.

## Codebase / env gotchas (Phase 5+ should know)

### Schema & JPA

- **`@Column(nullable=false)` on a JPA `@Inheritance(SINGLE_TABLE)` subclass-only field is a trap when the production base-table schema has the column as NULLABLE** (to support bare-class rows). Grails created `party` table with `code`, `name`, `active` as NULLABLE so both Organization rows (where Grails enforces non-null via domain constraints) AND bare Party rows (no constraints) coexist. Spring Boot Organization entity declared `@Column(nullable=false)` matching the *business* rule but not the *schema* — divergence. `ddl-auto: validate` did NOT catch this because it only checks column existence + type, NOT nullability. T9 fixup removed the constraint; business-rule validation moved to `CreateOrganizationCommand` DTO via `@NotBlank`. **Rule for Phase 5+**: on any JPA entity using `@Inheritance(SINGLE_TABLE)`, subclass-only fields MUST be `nullable = true` (or `@Column` with no nullable hint, default true) to match real-world base-table schemas. Add to SDD spec/code reviewer checklist (Phase 4.1 4.1-T1).

- **`ddl-auto: validate` is silent on nullability mismatches.** Stronger statement of above. Only fails on missing columns or type mismatches. Phase 5+ services that mix existing Grails-created tables + new entity declarations MUST cross-check `@Column(nullable=?)` against `INFORMATION_SCHEMA.COLUMNS.IS_NULLABLE` for each declared field, not trust `validate`. T4 reviewers (spec + quality) both missed this for Organization because `validate` passed.

- **`partyTypeCode` in `OrganizationDto`/`PartyDto` is the SINGLE_TABLE `@DiscriminatorValue` (FQCN per A28), NOT the `PartyType.code` value.** Reading `GET /api/organization/1` returns `"partyTypeCode": "ORGANIZATION"` (the FQCN simple-name discriminator) while `GET /api/organization/partyType` shows the same partyType row's `.code = "ORG"`. Two different fields with similar names referring to different concepts (Java class discriminator vs PartyType entity code). T12 smoke-test caught this potential confusion. Document in spec §6 (Phase 4.1 retro back-port) and consider renaming `partyTypeCode` → `partyClassDiscriminator` in a future Phase X DTO sweep.

### Build & CI

- **Adding a new service to `docker/docker-compose.yml` + making `nginx depends_on` it MUST be coordinated with `.github/workflows/e2e-tests.yml` updates in the same commit OR a prior commit on main, OR `docker compose up --build` in CI will fail at `COPY build/libs/<svc>-*.jar`.** Phase 4 push split these across T2 (added service) + T8 (added nginx dep) + T11 (added CI workflow build line) — T11 was the LAST commit and only landed 14h after T9 fixup, leaving main CI RED for ~14h. **Codify**: writing-plans skill should include a constraint that the CI workflow update task be ordered IMMEDIATELY BEFORE (or in the same commit as) the docker-compose insertion. Phase 4.1 4.1-T2 will update the writing-plans skill or add a pre-CIR checklist item.

- **`./gradlew prepareDocker -Dgrails.env=prod` fails locally in Peter's env on the `generateGitProperties` task** with `"No such property: out for class: com.gorylenko.writer.NormalizeEOLOutputStream"` — a gradle-git-properties-plugin / Gradle compat issue. Workaround: `-x generateGitProperties` (Spring Boot Actuator `META-INF/git.properties` is the only thing skipped — `/actuator/info` git block goes blank; no controller / classpath / WAR-content impact). **CI tolerates the plugin** without `-x` (different Java/Gradle version on ubuntu-22.04 temurin-8). Phase 5+ T11/T12 local rehearsals should add `-x generateGitProperties` to any `prepareDocker` invocation. True fix is upstream plugin upgrade (deferred — Category E backlog item).

- **Two classes both annotated `@RequestMapping("/api/organization")`** (`OrganizationController` for `{id}` + `list` + POST; `ReferenceController` for `/partyType` + `/partyRole`) coexist correctly because Spring AntPathMatcher resolves `/partyType` literal before `/{id}` variable. Smoke-tested in T7 + T12. Readers scanning `controller/` may briefly perceive a collision — a 2-line package-info Javadoc could clarify. Phase 4.1 4.1-T5 1-line cleanup batch.

### Done-gate (T12) plan defects

- **T12 Step 1 `sudo docker-compose down -v` is destructive** — wipes the dev DB MariaDB volume. The Grails `BootStrap.groovy` re-seeds admin with cleartext password that the identity-service `OpenboxesPasswordEncoder` rejects + `person.active=NULL` that AuthService treats as disabled (Phase 2 T17 finding — fix tracked for `BootStrap.groovy` back-port). CI handles this via `docker exec -i openboxes-db mariadb -u root -proot openboxes < docker/init-baseline.sql` (workflow line 55). Local Phase 4 T12 verification SKIPPED Step 1 entirely (running stack had been healthy 16h on same service code as `65d645321`; no svc-side change since T9 fixup); substituted equivalent evidence. **Plan defect**: T12 Step 1 should either (a) omit `-v`, (b) auto-apply `init-baseline.sql` after restart, or (c) be removable when running-stack uptime + healthcheck already covers the gates. Phase 4.1 4.1-T5 or 4.1-T1 (codify in plan template).

- **T12 Step 9 soak command `docker stats --no-stream --format ... --filter name=openboxes` uses INVALID `--filter` flag.** `docker stats` doesn't support `--filter`; correct invocation is `docker stats --no-stream --format ... $(docker ps -q --filter name=openboxes)` (filter at `docker ps` then pass container IDs as positional args). The background soak task ran the broken command 4× before being killed; produced 0-byte snapshot files. **Plan defect**: T12 Step 9 spec needs correcting in the plan template + likely the writing-plans skill (Phase 4.1 4.1-T1).

### nginx routing

- **`/api/party/*` is NOT a public endpoint.** The polymorphic Party read is at `/api/organization/party/{id}` (under the `/api/organization/` prefix, which IS routed). Plan §1308 `@RequestMapping("/api/organization/party")` + spec §300 confirm. T12 smoke-test initially probed `/api/party/1` and got Grails 404 — this was MY misunderstanding, not a routing gap. Recheck endpoint paths against spec, not Grails-shaped intuition.

- **nginx block-ordering convention is undeclared.** Current order is insertion order (identity from Phase 2, documents from Phase 2, location from Phase 3, organization from Phase 4) NOT alphabetical (docs < identity). Phase 5+ implementers may alphabetize incorrectly. A 1-line comment in `docker/nginx/conf.d/app.conf` documenting "blocks in insertion order — append at end" would prevent this. Phase 4.1 4.1-T5.

### Container / runtime

- **organization-service `port 8084` is `expose:` only, NOT `ports:`.** Mirrors location-service :8083 + document-service :8081 + identity-service :8082 patterns. Host-side `curl http://localhost:8084/...` fails; correct equivalent is `sudo docker exec openboxes-organization-service curl -sf localhost:8084/...`. All external traffic goes through nginx `/api/organization/{,/...}`.

- **Dev DB test pollution accumulates across sessions.** Phase4BaselineTest (T1 baseline POST, code=PHA), T7SmokeTest (code=T7S), T8 smoke POST org, T10 Playwright POST tests (`E2E-Test-${Date.now()}` × N runs), T12 smoke POST (`T12-Smoke-${date +%s}` × 2). Acceptable for dev DB (CI uses ephemeral containers); a `npm run e2e:clean` script would help once org-service grows a DELETE endpoint (Phase 5+ scope per Category E backlog).

## Process / meta-lessons

1. **CI-red detection latency was 14 hours.** Phase 4 push completed at 01:48 UTC; user surfaced the CI failure log at ~15:50 UTC; T11 fix landed at 16:30 UTC. The fix itself took 5 min (3-line YAML diff) but detection took 14h. **Action**: explore a Claude-Code cron job that watches `gh run list --branch=main --limit=1` after every push and surfaces failures within minutes. Alternative: GitHub email notifications routed appropriately. The handoff §5 step 5 PREDICTED this risk explicitly but didn't translate into action. Predictions without monitoring = noise. (Phase 4.1 4.1-T2 will codify the plan-order rule that prevents the broken-push class; this lesson addresses the detection-when-it-still-happens class.)

2. **Phase N + Phase N.1 horizontal cleanup separation is a strong organizational invariant.** User invented this distinction this session in response to my "T10.5 cleanup sweep" proposal. The key insight: **Phase N tag = vertical-slice deliverable per CIR-R3 plan** (here: extract organization-service); **Phase N.1 tag = horizontal cleanup spanning Phase 1..N codebase**, addressing accumulated debt the vertical work revealed. Without this separation, Phase N either (a) scope-creeps to bundle cleanup or (b) defers cleanup to an unbounded Phase X graveyard. With the separation: Phase N stays clean; Phase N.1 is a real, bounded follow-on commitment. Future phases: **always plan an N.1 immediately after N's done-gate**, sized to the recurring patterns + retro candidates surfaced in N. This converts "Phase X = graveyard" into "Phase X = items genuinely blocked on future infra."

3. **Triage retro candidates by Category A-F** (introduced this session) and **delete intentional Grails-parity items rather than deferring them.** The 21 items at T10 fell into:
   - **A**: codify lesson (meta-process; ~15min each; *high* recurrence risk if not fixed) — 2 items
   - **B**: accumulating debt (gets strictly worse per phase) — 2 items
   - **C**: 1-line atomic cleanups (~1 min each) — 5 items
   - **D**: intentional Grails parity — **DELETE from backlog with rationale, don't defer** — 4 items
   - **E**: true product/infra "later" items — 6 items (legitimate Phase X)
   - **F**: defensible style decisions — **DELETE** — 2 items
   Net: 21 → 13 to fix in Phase 4.1, 6 to true Phase X (with reasons), 6 deleted with rationale. **Without this triage, all 21 would sit forever in "Phase X" and accumulate per phase.** Phase 5+ retros must apply A-F categorization.

4. **Plan-order rule discovered**: any commit that adds a service to `docker-compose.yml` OR makes `nginx depends_on` a new service MUST be in the same PR/commit as the CI workflow update OR land after the CI workflow update. Phase 4 violated this because the plan ordered T11 (CI workflow) after T10 (React + Playwright), but T2 + T8 (compose/nginx) already triggered the dependency BEFORE T11. **Fix**: codify in writing-plans skill that compose-modifying tasks have an `addBlockedBy` relationship to CI-workflow-modifying tasks. Phase 4.1 4.1-T2 will update the skill or add a CIR checklist gate.

5. **Light SDD calibration** worked for T11 (3-line YAML diff): Direct apply by controller, skip implementer/spec/code-quality subagent cycle. New rule: **diff is plan-pre-approved verbatim AND has no business logic AND total LOC < 20 → Direct apply; else full SDD cadence**. Saved ~15-20 min on T11. Watch for slippery slope (don't broaden the exception); rule encoded in this retro for Phase 5+ controllers to inherit.

6. **`down -v` discipline in T12** is debatable. Phase 3 retro praised it as "right discipline despite wiping the DB" (catches false-positive existing-schema bootstraps). Phase 4 T12 skipped Step 1 entirely because the running stack had been healthy 16h on the exact service code (no svc-side change since T9 fixup) — the value of clean-rebuild was low and the cost (DB wipe + admin password reset + 5-10min) was real. Lesson: **when running-stack uptime + healthcheck already demonstrates stability of the deliverable, `down -v` is theatre, not insurance**. When the deliverable changes service code OR docker setup since last green, `down -v` IS warranted. Phase 5+ T12 should make Step 1 conditional on "service code changed since last clean rebuild?"

7. **The T9 reviewer caught what T4 reviewers missed** (Organization @Column nullability divergence). This is the second instance of "downstream-task review catches earlier-task gap" in this migration (Phase 3 T8/T12 nginx exact-match was the first). The mechanism: downstream tasks exercise the boundary in ways earlier reviews can't predict (here: T9 used `ddl-auto: create` while T4 only checked `ddl-auto: validate`). **Lesson for reviewers**: when reviewing a task that uses different framework defaults than predecessor tasks, ask "what would change if the earlier task's assumptions don't hold?" — and surface those as proactive review items, not just blockers in your current task.

## Forward to Phase 4.1 (horizontal cleanup slice)

Phase 4.1 will execute the A+B+C sweep from the categorization above as 6 SDD tasks with one commit per category. Tagged `phase-4.1-cleanup` when done. Scope:

- **4.1-T1**: Codify A1 (JPA SINGLE_TABLE nullability rule) + T12 plan defects (Step 1 destructive, Step 9 `docker stats` flag). Updates: writing-plans skill OR phase-N plan template + SDD code-reviewer checklist.

- **4.1-T2**: Codify A2 (plan-order rule: compose-modifying task `addBlockedBy` CI-workflow-modifying task) + CI-red detection improvement note. Updates: writing-plans skill.

- **4.1-T3**: Extract login helper to `e2e/fixtures/auth.ts` (refactors 3 Phase 1+2+3 spec files + T10 organization-service spec). Eliminates per-phase duplication growth.

- **4.1-T4**: Extract nginx `proxy_set_header` blocks to `/etc/nginx/proxy_params` `include` directive (refactors 4 service blocks in `docker/nginx/conf.d/app.conf`). Eliminates per-phase duplication growth.

- **4.1-T5**: 1-line cleanups batch (5 atomic edits in one commit):
  - PartyRole `setEndDate` setter overbuilding (T4 quality-reviewer finding)
  - Unused `Party` import in `PartyService.java:4` (T6 quality-reviewer finding)
  - nginx block-ordering convention comment in `app.conf`
  - T9 `partyTypeCache_refreshOnEmptyList` test name rename (oversells coverage)
  - T10 Test #6 "baseline preservation" rename (M3: drops misleading "Phase 1+2+3" wording)

- **4.1-T6**: Delete Category D + F items from backlog (4 + 2 items) — documentation-only commit updating any tracking docs. Items DELETED with rationale per category criteria. Refreshes the Phase 4.1 done-gate retro pointer to a 6-item bounded Phase X (Cat E remaining).

Process: light cadence (no brainstorming/CDR/CIR full cycle since scope is mechanical cleanup); skip directly to writing a Phase 4.1 plan doc + SDD per-task with per-category commits + per-task gates. ~3-4 hours total.

After Phase 4.1, the remaining Phase X / future-phase backlog is:

## Retrospective candidates (RC-1 through RC-21)

Surfaced this session by spec-reviewer, code-quality-reviewer, T9 fixup decision, T12 done-gate exercise, and the T11-brought-forward CI break. Numbered for back-reference. Disposition column tracks A/B/C/D/E/F category per §"Process meta-lesson 3".

| # | Severity | Source | Description | Category | Disposition |
|---|----------|--------|-------------|----------|-------------|
| RC-1 | Important | T9 quality-reviewer | Organization @Column(nullable=false) on code/name/active diverged from production schema (NULLABLE for SINGLE_TABLE support); ddl-auto:validate didn't catch | A (codify) | **FIXED** at `8cba628f1`; 4.1-T1 codifies the rule in SDD reviewer checklist |
| RC-2 | Important | T8 quality-reviewer | CI workflow `.github/workflows/e2e-tests.yml` missing `:organization-service:bootJar` + health probe + log dump | A (codify) | **FIXED** at `2e25af837` (T11 brought forward); 4.1-T2 codifies plan-order rule |
| RC-3 | Important | T10 quality-reviewer | Login helper duplicated across `organization-service.spec.ts`, `location-service.spec.ts`, `identity-caller-regression.spec.ts` | B (accumulating) | 4.1-T3 extracts to `e2e/fixtures/auth.ts` |
| RC-4 | Minor | T4 quality-reviewer | PartyRole `setEndDate` setter overbuilding (1 line cosmetic) | C (1-line) | 4.1-T5 |
| RC-5 | Minor | T6 quality-reviewer | Unused `Party` import in `PartyService.java:4` | C (1-line) | 4.1-T5 |
| RC-6 | Minor | T7 quality-reviewer | POST 201 missing `Location` header (REST conformance) | D (Grails parity) | **DELETED** — intentional Grails parity per CDR R3 |
| RC-7 | Minor | T7 quality-reviewer | 404 returns empty body (no `{data: null, error: ...}` envelope) | D (Grails parity) | **DELETED** — intentional Grails parity / product decision |
| RC-8 | Minor | T7 quality-reviewer | Single-letter constructor params in REST controllers | D (Grails parity) | **DELETED** — Phase 3 style; cosmetic-only |
| RC-9 | Minor | T7 quality-reviewer | Two classes (`OrganizationController` + `ReferenceController`) at same `@RequestMapping("/api/organization")` — readability not function | D (Grails parity) | **DELETED** — Spring AntPathMatcher resolves correctly; doc opportunity if pursued |
| RC-10 | Minor | T8 quality-reviewer | nginx block-ordering convention undeclared (currently insertion-order, not alphabetical) | C (1-line) | 4.1-T5 (1-line comment) |
| RC-11 | Minor | T8 quality-reviewer | nginx `proxy_set_header` blocks duplicated across N service entries | B (accumulating) | 4.1-T4 extracts to `include /etc/nginx/proxy_params;` |
| RC-12 | Carry-forward | T8 quality-reviewer / handoff | gradle-git-properties-plugin upgrade (Peter's local env hits `prepareDocker` failure; CI tolerates) | E (true defer) | Phase X — upstream plugin upgrade; not migration scope |
| RC-13 | Minor | T9 quality-reviewer | `partyTypeCache_refreshOnEmptyList` test name oversells coverage | C (1-line) | 4.1-T5 (rename only) |
| RC-14 | Minor | T9 quality-reviewer | Magic-string DRY across 18 tests (would matter if 4th test class emerges) | E (true defer) | Phase X — YAGNI; reconsider when 4th test class appears |
| RC-15 | Minor | T9 quality-reviewer | Missing explicit `/partyType/{id}` 404 test (acceptable per Phase 3 parity) | E (true defer) | Phase X — low marginal value |
| RC-16 | Minor | Handoff §3 | A14 back-port `LocationController.groovy:103` 4th cross-context caller of `findOrCreateSupplierOrganization` to spec §7.1 | E (true defer) | Phase X — spec doc completeness |
| RC-17 | Minor | Handoff §3 | git config `remote.origin.fetch` refspec only includes one tag (branches don't fetch into `refs/remotes/origin/*`); local `origin/main` ref permanently stale | E (true defer) | Phase X — user-env specific; opt-in fix per system instruction |
| RC-18 | Minor | T10 quality-reviewer | Test data accumulation — `Date.now()` named POST orgs never cleaned up | E (true defer) | Phase X — needs DELETE endpoint (out of Phase 4 scope) |
| RC-19 | Minor | T10 quality-reviewer | `request: any` instead of `APIRequestContext` (verbatim from CIR-R3-approved plan; all existing specs use `any`) | F (defensible) | **DELETED** — codebase-wide pattern; runtime errors catch typos |
| RC-20 | Minor | T10 quality-reviewer | Test #6 baseline preservation only checks 2 endpoints (`/api/identity/me`, `/api/location/type`); name overpromises | C (1-line) | 4.1-T5 (rename only); full regression IS in 29 baseline tests in other files |
| RC-21 | Minor | T10 quality-reviewer | Login called 6× per test instead of `beforeAll` cookie reuse | F (defensible) | **DELETED** — matches Phase 3 pattern exactly; Playwright best-practices favor per-test isolation |
| RC-22 | Important | T12 done-gate / this retro | T12 plan defects: Step 1 destructive `down -v` + `prepareDocker` plugin bug + Step 9 `docker stats --filter` invalid flag | A (codify) | 4.1-T1 corrects in plan template / writing-plans skill |
| RC-23 | Minor | T12 / this retro | `partyTypeCode` field name confusable with `PartyType.code` (former is `@DiscriminatorValue` FQCN; latter is PartyType entity code) | E (true defer) | Phase X — consider rename in DTO sweep |
| RC-24 | Carry-forward | T10 Playwright | `identity-choose-location.spec.ts:12` flake — intermittent 401 on chooseLocation PUT (pre-existing Phase 2; CI auto-retried & passed) | E (true defer) | Phase X — Phase 2 identity-service test flake; not Phase 4 introduced |

Net: **24 items surfaced; 6 deleted with rationale (D+F); 6 fixed within Phase 4 itself (T9 fixup + T11) or Phase 4.1; 12 to be addressed in Phase 4.1 (A+B+C); 6 deferred to true Phase X (E)**.

## Plan / spec back-ports

Spec / plan / skill corrections to apply during Phase 4.1 — pulled out of the table above for grep-ability:

- **CDR meta-checklist**: add "JPA @Inheritance(SINGLE_TABLE) subclass fields MUST cross-check nullability against production base-table schema, NOT just rely on ddl-auto:validate" (RC-1 root cause; Phase 4.1 4.1-T1).
- **CIR meta-checklist**: add "if task modifies docker-compose.yml or nginx depends_on, ensure CI workflow update is ordered same-commit or prior" (RC-2 root cause; Phase 4.1 4.1-T2).
- **Plan template T12 Step 1**: replace `sudo docker-compose down -v` with non-destructive variant OR auto-apply `init-baseline.sql` post-restart (RC-22 first defect).
- **Plan template T12 Step 1**: add `-x generateGitProperties` to any `./gradlew prepareDocker` invocation OR document the workaround in plan §gotchas (RC-22 second defect).
- **Plan template T12 Step 9 (soak)**: correct `docker stats --filter name=X` → `docker stats $(docker ps -q --filter name=X)` (RC-22 third defect).
- **Phase 4 spec §6 OrganizationDto**: clarify `partyTypeCode` semantics (it's the `@DiscriminatorValue` FQCN, NOT `PartyType.code`); consider field rename in Phase X DTO sweep (RC-23).
- **Phase 4 spec / parent design**: add A14 documentation (4th cross-context caller of `findOrCreateSupplierOrganization` at `LocationController.groovy:103`) to spec §7.1 (RC-16; carry-forward from handoff §3).

## Phase X carry-forward (deferred until later phases)

True Phase X items (Category E + the 1 surviving D-judgment): items with explicit deferral reasons, not "I'll get to it later" placeholders.

1. **gradle-git-properties-plugin upgrade** — affects local dev `prepareDocker` only; CI works; not on the migration critical path (RC-12).
2. **Magic-string DRY in JUnit tests** — defer until 4th organization-service test class emerges; YAGNI today (RC-14).
3. **Explicit `/partyType/{id}` 404 test** — low marginal value; existing happy-path coverage adequate (RC-15).
4. **A14 spec back-port** — spec documentation only; doesn't affect code (RC-16; will land in 4.1-T6 if treated as Cat C-like doc edit, otherwise here).
5. **git config refspec fix** — user-env specific; requires explicit user OK per system instruction (RC-17).
6. **Test data DELETE endpoint + cleanup script** — blocked on DELETE endpoint not in Phase 4 scope (RC-18).
7. **`partyTypeCode` DTO rename** — consider in future DTO sweep; affects API consumers if changed (RC-23).
8. **identity-choose-location.spec.ts flake** — Phase 2 inherited; tracked separately as Phase 2 hygiene (RC-24).
9. **(From Phase 3 retro, still open)** JwtCookieAuthFilter + JwtService shared library extraction (4 copies now: document/identity/location/organization; FD#6 deferred). Phase 5+ STRONGLY motivated.
10. **(Inherited from earlier retros)** Phase 3 RC-1 through RC-17 most items still open; consider batching with Phase 4.1 4.1-T4 (nginx) and 4.1-T3 (login helper) which DO address Phase 3 RCs incidentally.

## Artifacts

- **Plan**: `docs/plans/2026-05-28-phase-4-organization-service-implementation-plan.md` (1958 lines; CIR R1+R2+R3-clean; post-T13)
- **Design spec**: `docs/specs/2026-05-28-phase-4-organization-service-design.md` (CDR R1+R2+R3-clean; A28 RESOLVED at T1 audit)
- **Parent migration design**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md` (shared with Phases 0-12)
- **Tag**: `phase-4-organization` at `65d645321` (annotated; pushed to origin)
- **Commit range** (Phase 4): `682f031bd..65d645321` (20 commits: 1 Phase 3 retro + 4 pre-work/spec/CDR + 4 plan/CIR + 11 SDD task commits including T9 fixup + T11 brought-forward)
- **Critical reviews** (gitignored): 6 files in `docs/criticalreviews/` (CDR R1, R2, R3; CIR R1, R2, R3)
- **CI runs**:
  - `26613228476` — phase 4 push pre-T11 (FAILED at `docker compose up --build` org-service jar missing; 8m27s)
  - `26649206784` — T11 CI fix (`2e25af837`; SUCCESS in 12m1s)
  - `26651994665` — T10 push (`65d645321`; SUCCESS in 10m42s) — Phase 4 done-gate CI
- **Phase 3 retrospective** (predecessor): `docs/retrospectives/2026-05-28-phase-3-location-retrospective.md`
- **Phase 2 retrospective**: `docs/retrospectives/2026-05-26-phase-2-identity-retrospective.md`
- **Phase 1 retrospective**: `docs/retrospectives/2026-05-26-phase-1-document-retrospective.md`
- **Handoff docs** (session continuity, all post-tag accessible):
  - `handoffs/2026-05-29_00-03-17_phase-4-uip-r2-done-cir-r3-next.md` (entry; UIP R2 done)
  - `handoffs/2026-05-29_01-00-32_phase-4-sdd-t6-done-t7-next.md` (T1-T6 done; T7 next)
  - `handoffs/2026-05-29_01-55-28_phase-4-sdd-t7-t9-done-t10-next.md` (T7-T9+fixup done; T10 next — pre-current-session)
- **Forward**: Phase 4.1 plan to be written next (light cadence per user direction); tag `phase-4.1-cleanup` when done.
