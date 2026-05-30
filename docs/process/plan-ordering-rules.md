# Plan task-ordering invariants

Rules that prevent recurring categories of error in plan execution. Future plan-writers must verify these when authoring task ordering and dependencies.

## Rule 1: compose-modifying ⇒ CI workflow update same-commit-or-prior (Phase 4 RC-2)

Any plan task that adds a new service entry to `docker/docker-compose.yml` OR makes `nginx depends_on` a new service entry MUST be in the same commit as — or strictly AFTER — the task that updates `.github/workflows/e2e-tests.yml` to build that service's jar + probe its health + dump its logs on failure.

**Why**: `docker compose up --build` in CI tries to build every service in `docker-compose.yml`. If a service is in compose but the workflow doesn't build its jar (i.e., doesn't include `:<service>:bootJar` in the gradle invocation before `up`), then the compose-build step fails at `COPY build/libs/<service>-*.jar` with `lstat /build/libs: no such file or directory`.

**Phase 4 violation evidence**: Phase 4 plan ordered T11 (CI workflow update) AFTER T10 (React + Playwright). T2 (added org-service to compose) + T8 (made nginx depend on org-service) landed on main BEFORE T11. Main CI went RED for ~14 hours until T11 fix landed at commit `2e25af837`.

**Verification at plan-write time**:
- For each task `T_compose` that modifies `docker/docker-compose.yml` or `docker/docker-compose-base.yml` to add a new service:
  - Identify the corresponding `T_ci` that updates `.github/workflows/e2e-tests.yml`
  - Assert `T_ci.position <= T_compose.position` in the task list
  - If `T_compose` already exists in a previous phase but `T_ci` is in this phase, surface as a forced decision

## Rule 2: file-conflict between sibling tasks (Phase 4.1 design-time A17)

If two tasks in the same plan touch the same file, EITHER:
- Merge them into one task, OR
- Establish strict ordering via TodoWrite `addBlockedBy` so the later task operates on the post-modification file state, OR
- Verify the edits don't actually conflict (different sections, no overlapping context lines)

**Phase 4.1 violation prevented**: 4.1-T4 (nginx restructure) and 4.1-T5 (RC-10 nginx block-ordering comment) both touched `docker/nginx/conf.d/app.conf`. CDR R1 surfaced; spec was revised to merge RC-10 into 4.1-T4.

**Verification at plan-write time**:
- For each pair of tasks (T_a, T_b), grep their `Files:` declarations for overlap
- If overlap exists: pick a resolution from the three above and document in the plan

## Rule 3: nginx prefix-match must not silently capture Grails sub-routes (Phase 5 T9 follow-up)

When a plan adds a nginx prefix-match `location /api/<entity>/` that proxies to a new Spring Boot service, it silently captures every URL under that prefix — including Grails sub-routes the new service does NOT implement. Such captured routes return 404 (no handler in the new service) instead of falling through to the `/api/` catch-all and reaching Grails.

**Why**: nginx prefix-match (`location /api/<entity>/`) wins over the `/api/` catch-all for any URL starting with `/api/<entity>/`. If Grails serves `/api/<entity>/<action>` (e.g., `UnitOfMeasureApiController.currencies()` at `/api/unitOfMeasure/currencies`) and the new service does not, the request gets routed to the new service which returns 404. Production React breaks silently — the React URL constant resolves to a real-looking path, the network request returns 404, the UI shows an empty/broken state without an obvious cause.

**Phase 5 violation evidence**: T8 added `location /api/unitOfMeasure/` → catalog-service:8085. UrlMappings.groovy:504 has explicit Grails mapping `/api/unitOfMeasure/currencies` → `UnitOfMeasureApiController.currencies` (non-UoM reference data, kept on Grails per T1 audit). Post-T8 the React `CURRENCIES_OPTIONS` URL returned 404. Spec reviewer surfaced at T9 gate; fixed at commit `c505e5842` by adding `location = /api/unitOfMeasure/currencies` exact-match BEFORE the catalog prefix (nginx exact-match has priority over prefix-match regardless of file order).

**Verification at plan-write time**:
- For each task `T_nginx` that adds a `location /api/<entity>/` prefix-match block, identify every explicit Grails sub-route under that prefix:
  ```bash
  grep -nE '"/api/<entity>/[a-zA-Z]' grails-app/controllers/org/pih/warehouse/UrlMappings.groovy
  ```
- For EACH such mapping, decide:
  - **Stays Grails**: add `location = /api/<entity>/<action>` exact-match block to `T_nginx` task description, routed to `http://app:8080/openboxes/api/<entity>/<action>`, placed BEFORE the prefix-match
  - **Migrates to the new service**: confirmed by the relevant per-entity audit (e.g., T1 for catalog) — implement the handler in the service before T8
- Smoke MUST include explicit curl for every Grails-stays sub-route to confirm non-404 status post-reload
