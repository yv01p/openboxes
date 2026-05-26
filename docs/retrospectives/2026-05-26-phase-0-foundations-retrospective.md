---
date: 2026-05-26
phase: 0 (Foundations)
tag: phase-0-foundations
commit_range: 8fe544e..91b1bba
plan: docs/plans/2026-05-25-phase-0-foundations-implementation-plan.md
spec_section: §7 (Phase 0)
---

# Phase 0 Foundations — Retrospective

## TL;DR

Phase 0 shipped (nginx routing + JWT plumbing + ProductApi fix + Playwright harness, tagged `phase-0-foundations`). The thorough-brainstorming → CDR → UDD → TWP → CIR → SDD loop produced internally-consistent designs and the 5-task SDD execution committed cleanly, but the done-gate failed on first run because three live-environment assumptions weren't probed at plan-write or CIR time: (1) the docker-compose stack pulls the upstream `:latest` image with no `build:` directive — local source never reaches the running container, (2) seed `admin@MainWarehouse` cannot access `/invoice/list` (chosen as the React-route test target), and (3) seed admin has no `rememberLastLocation` so `/admin/index` always bounces to `chooseLocation`. All three were resolved with no design changes — just test patches and a documented build prelude — but the meta-lesson is that **static review (CIR) cannot catch live-environment assumptions; a smoke-probe verification step belongs in TWP for any plan whose done-gate runs against a live stack**.

## What worked

- **5-task SDD structure** held up — each task was self-contained enough for a fresh subagent to execute. No cross-task coordination needed.
- **API-driven Playwright tests** (vs. browser-selector tests) — faster, more deterministic, didn't require live UI exploration. Recommend for all future E2E work.
- **CIR Round 1 + Round 2** caught real design-quality issues before execution (SecurityInterceptor JWT bypass; test endpoint that didn't exist). The static-review loop is worth its cost.
- **`thorough-writing-plans` per-assumption verification** (37 P-assumptions) caught the nginx service-name (`app` not `grails`), the symmetric `ApiController.logout`, and the dead axios import — all things spec literals missed.
- **"Pause to confirm destructive actions"** (asking before sudo-rebuilding the image, before restarting compose, before committing) caught no errors but kept the user informed of blast radius. Worth the friction.
- **Single transactional commit** for test-fix + plan-update + CI-update at done-gate — keeps the retrospective story coherent.

## Codebase / env gotchas (Phase 1+ should know)

### Build & deploy

- **`docker-compose-base.yml` has no `build:` directive.** The `app` service uses `image: ${OB_IMAGE_REPOSITORY-ghcr.io/}openboxes/openboxes:${OB_VERSION:-latest}` and pulls upstream `:latest`. Local Groovy/JS changes are invisible until you build a local image with the same tag. The blessed flow is:
  ```bash
  ./gradlew prepareDocker -Dgrails.env=prod --console=plain   # → build/docker/{openboxes.war, Dockerfile}
  sudo docker build -t ghcr.io/openboxes/openboxes:latest build/docker/
  cd docker && sudo docker-compose down && sudo docker-compose up -d
  ```
  Future phases should consider adding a `build:` directive to `docker-compose-base.yml` once, instead of repeating the retag-as-`:latest` workaround per phase.

- **JDK 11+ breaks `:generateGitProperties`.** The `gradle-git-properties` plugin's `NormalizeEOLOutputStream` is incompatible on JDK 11+. Workaround for local dev: `./gradlew prepareDocker -x generateGitProperties`. CI uses JDK 8 (mirrors upstream `.github/workflows/docker-image.yml`) and does not need the flag.

- **Compose files live under `docker/`, not repo root.** `docker-compose up` from repo root fails (no compose file present). Use `cd docker && docker-compose ...` or `working-directory: docker` in GH Actions.

- **`docker` group membership** — this dev box's user isn't in the `docker` group; every docker command needs `sudo`. Consider `usermod -aG docker $USER` if a developer-only box, or document the `sudo` prefix.

### Seed data

- **`admin` is `ROLE_AUTHENTICATED`-ish but cannot access `/invoice/list`** at `Main Warehouse` (location id=1). The InvoiceController gates on a warehouse activity that the default seed location doesn't have — request lands at `/openboxes/errors/handleForbidden`. Other React routes admin@warehouse=1 CAN reach: `stockMovement/list?direction=INBOUND` (the post-chooseLocation default landing), `dashboard/index`, `order/list?orderType=PURCHASE_ORDER`, `productAvailability/list`, `requisitionTemplate/list`.

- **Seed admin has no `rememberLastLocation`.** Any GSP route that requires a warehouse will redirect to `dashboard/chooseLocation` on first request after login. Tests must do an explicit `GET /openboxes/dashboard/chooseLocation/{id}?targetUri=...` before fetching the actual target.

- **Default active locations** (from the seeded MariaDB): `id=1 Main Warehouse`, `id=2 Main Supplier`. `E2E_LOCATION_ID=1` is the safe default.

- **Grails default table naming is singular**: `user`, `location` (not `users`, `locations`). Easy to trip over when probing seed data with raw SQL.

### Code-level

- **`User.id` is a UUID `String`** (not an integer), but JWT subject for seed admin happens to be `"1"` — verify whether this is `user.id`, `user.username`, or a row index. If `id`, the seed must have an admin with `id="1"` explicitly set, which is unusual for a UUID column.
- **`grails-app/controllers/org/pih/warehouse/user/AuthController.groovy`** (not `…/warehouse/AuthController.groovy` as the design spec literal suggested) — verified by Task 2's commit stat.

## Process / meta-lessons

1. **CIR is static review only.** It cannot reproduce errors, probe a live stack, or interrogate seed data. All three Phase 0 done-gate surprises were live-environment facts. Recommendation: add a **"live smoke probe" task** to TWP for any plan whose done-gate runs against a running stack. For Phase 0 this would have been: `docker-compose up -d && curl localhost/openboxes/health && curl -X POST /api/login` BEFORE writing the E2E specs — surfaces the image-pull issue, the auth shape, and the seed-data shape in one pass.

2. **Plan-writing should grep CI workflows.** The upstream `.github/workflows/docker-image.yml` had `./gradlew prepareDocker` as a first-class step; the Phase 0 plan didn't reference it. A `find .github -name "*.yml" | xargs grep gradlew` during TWP verification would have surfaced the blessed build path and obviated the discover-during-execution moment.

3. **E2E tests written from a static plan need a "seed-data sanity" verification.** For each test target route, the plan author should be able to answer: *who* in the seed data can reach it, and from *which warehouse*? The `/invoice/list` pick failed because nobody asked that question at plan time. A `docker exec openboxes-db mysql ...` probe per target during TWP would have caught it.

4. **Implementer report's "critical infrastructure issue" framing was correct.** The SDD agent (Task 4 implementer) flagged the docker issue clearly enough that the resume-handoff loop could pick it up. The framing — *report the surprise, don't try to fix in-task* — is right. Don't change.

5. **The 4-files single-commit pattern at done-gate** (test fix + plan update + CI update + retro reference) is a good template. It tells one coherent story in `git log` and gives Phase 1+ a clean diff to learn from.

## Forward to Phase 1+

- Phase 1 is **identity-service extraction** per spec §6 (first Spring Boot service alongside the Java 8 Grails container). Multi-JDK build is a new concern.
- Before TWP for Phase 1: decide whether to **add a `build:` directive** to `docker-compose-base.yml` permanently (covers Phase 1+ and removes the per-phase retag friction). This is a one-line spec/plan change that prevents the entire class of issues hit in Phase 0.
- Before TWP for Phase 1: probe seed data for whatever the Phase 1 done-gate exercises (likely: authenticate via identity-service, then access a Grails route via the new JWT) — confirm `admin@MainWarehouse` can actually reach whatever the test target is.
- Phase 1 plan should reference this retrospective in its `## Verified plan-level assumptions` section so the assumptions list inherits the live-environment knowledge above instead of rediscovering it.
- If a "live smoke probe" task gets added to the TWP loop (see meta-lesson #1), Phase 1 is the natural place to try it.

## Artifacts

- **Plan**: `docs/plans/2026-05-25-phase-0-foundations-implementation-plan.md` (committed `91b1bba`, includes inline lessons in Task 4 Step 4/6 and Task 5 Step 1)
- **Design spec**: `docs/specs/2026-05-25-grails-to-spring-boot-migration-design.md`
- **Tag**: `phase-0-foundations` at `91b1bba` (pushed to `origin/main`)
- **Commit range** (Phase 0): `8fe544e..91b1bba` (6 commits)
- **CDR reviews** (gitignored): `docs/criticalreviews/2026-05-25-grails-to-spring-boot-migration-design-critical-review-{1..6}.md`
- **CIR reviews** (gitignored): `docs/criticalreviews/2026-05-25-phase-0-foundations-implementation-plan-critical-review-{1,2}.md`
- **Handoffs** (gitignored): `handoffs/2026-05-25_*.md` (3 files spanning the design + plan + execution sessions)
