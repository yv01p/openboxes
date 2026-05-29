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
