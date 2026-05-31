# Plan template defects (known issues; back-port at next plan write)

Defects in plan templates and Phase N done-gate scripts surfaced during execution. Future plan-writers should preemptively correct these.

## T12 done-gate Step 1: `down -v` is destructive (Phase 4 RC-22)

Plan templates copying the Phase 3/4 T12 done-gate Step 1 use `sudo docker-compose down -v && sudo docker-compose up -d --build`. The `-v` flag destroys the dev DB MariaDB volume, which forces Grails `BootStrap.groovy` to re-seed admin with the SHA-1 password Phase 2 identity-service rejects (per `OpenboxesPasswordEncoder`) and resets `person.active=NULL` which `AuthService` treats as disabled.

**Substitutes**:
- Omit `-v` (rebuild containers but keep volume): `sudo docker-compose stop && sudo docker-compose rm -f <changed-service> && sudo docker-compose up -d --build <changed-service>`
- OR auto-apply `init-baseline.sql` immediately after `up`: `docker exec -i openboxes-db mariadb -u root -proot openboxes < docker/init-baseline.sql`

## T12 done-gate Step 1: `prepareDocker` `generateGitProperties` failure (Phase 4 RC-22)

Plan templates use `./gradlew prepareDocker -Dgrails.env=prod` for local rehearsal. In Peter's local env (specific Java/Gradle version combination), this fails with `"No such property: out for class: com.gorylenko.writer.NormalizeEOLOutputStream"` in the `generateGitProperties` task. CI tolerates the plugin without `-x` (different Java version on ubuntu-22.04 temurin-8).

**Workaround**: add `-x generateGitProperties` to any local `prepareDocker` invocation. Only the Spring Boot Actuator `META-INF/git.properties` file is skipped (used by `/actuator/info` git block at runtime; goes blank). No controller / classpath / WAR-content impact.

**True fix**: upstream `gradle-git-properties-plugin` upgrade (Phase X — Phase 4 RC-12).

## T12 done-gate Step 9 (soak): `docker stats --filter` invalid flag (Phase 4 RC-22)

Plan templates use `sudo docker stats --no-stream --format "..." --filter name=<prefix>`. `docker stats` does NOT support `--filter`; the flag is rejected with `unknown flag: --filter`.

**Correct invocation**: pass container IDs from `docker ps` as positional args:

```bash
sudo docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" $(sudo docker ps -q --filter name=openboxes)
```

Note: `docker ps` DOES support `--filter`. The `--filter` confusion is `docker stats`-specific.

## T2 compose-file inventory (Phase 5 RC-3)

Plan template T2 (service module bootstrap + compose entry) historically lists only `docker-compose-base.yml` in the Files: section. Production needs BOTH `docker/docker-compose-base.yml` AND `docker/docker-compose.yml` updated (the latter contains the `extends:` blocks + nginx `depends_on` entries that the runtime stack actually composes).

**Fix**: when writing a T2 task that adds a service, the Files: section MUST list BOTH compose files.

## T2 Dockerfile convention drift (Phase 5 RC-4)

Plan-template-default T2 Dockerfile uses alpine base + apk + root user + flat `/app/app.jar` layout. The 5 existing services use jammy base + apt-get + non-root `spring` user + bind-mount-friendly layout for layer caching.

**Fix**: when writing a T2 task for a new service, the Dockerfile MUST mirror the convention established by the 4+ existing services. Cite the donor service in the plan body (e.g., "Dockerfile mirrors location-service:Dockerfile").

## Plan-text method/identifier names are suggestions (Phase 5 RC-5)

Plan-text code blocks naming service methods (e.g., `service.listUom()`) are suggestions, not contracts. Implementers should follow Java/language convention (e.g., `listUoms()` for collection-returning methods). T7 reviewers should NOT flag the divergence; T7 should accept the convention-aligned rename.

**Fix**: plan templates may use suggestive names in code blocks; the plan body should NOT specify identifier names as binding contracts unless there's a callsite-coupling reason (e.g., reflection, framework convention).

## T12 done-gate Step 5: real JWT required (Phase 5 RC-8)

Plan templates historically used hand-crafted JWT (`sub='test'`) for T12 done-gate's "verify endpoints return 200" check. Hand-crafted JWTs lack the right claims structure (`loc`, `roles`) and aren't authenticated against the Grails session model, leading to mixed 401/400/302 responses that look like spurious failures.

**Fix**: T12 Step 5 must obtain a real JWT via `POST /api/identity/login` (admin/password) and use that cookie for all per-service GET smoke checks. Hand-crafted JWTs are only valid when the test is specifically validating the parser (e.g., expired-token rejection).

## package-lock.json regenerator discipline (Phase 5.1 post-tag carry-in)

When regenerating `package-lock.json` for the project, use Gradle's bundled npm (invoked via `./gradlew :npmInstall` or `services/.gradle/nodejs/.../bin/npm install` directly), NOT host npm. The Grails build pipeline resolves dependencies against the bundled npm; host-npm-generated locks ENOTSUP-cascade at `:npmInstall` because host npm (often v9+/v10+) writes lockfileVersion 2 while bundled npm v6 reads lockfileVersion 1.

**Cascade history**: Phase 5 RC-26 L2 follow-ups (commits `bc2d1e370` through `c5f531851`) hit this 4 times before the root-cause fix landed at `c5f531851` (regenerate via Gradle's bundled npm).

**Fix**: any task that bumps a frontend dep + needs to regenerate package-lock.json MUST use Gradle's bundled npm, OR bump the Gradle node toolchain first (Phase 5.1 T9 does the latter; subsequent phases can use host npm directly once the toolchain matches).

**Verification before commit** (Phase 5.1 RC-42): after regenerating the lockfile via the right npm version, verify with `npm ci --dry-run` (or a full `npm ci` against a clean `node_modules/`) BEFORE committing. `:npm_run_bundle` and the underlying `npm install` are permissive — they mutate the lockfile to satisfy `package.json` rather than failing — so a lockfile that passes them may still be rejected by CI's `npm ci` (strict; fails on any `package.json` ↔ `package-lock.json` drift). Phase 5.1 T9 amend 2 hit this trap: a `:npmInstall`-regenerated lockfile passed local `:npm_run_bundle` but failed CI with `EUSAGE` on `@types/react@19.2.7` + `acorn@7.4.1` + `babel-plugin-macros@2.8.0` mismatches. Resolved at commit `d5dde90f7` via clean regen with host npm 11 (post-RC-28 Node 22 bump put host npm in the supported range) plus a `npm ci` verification before push.

## Dep `engines.node` floor check (Phase 5.1 RC-32)

When a plan picks a major version for a runtime / tool dep (e.g., `"lint-staged": "^17"`), query `npm view <pkg>@<picked-major> engines.node` BEFORE plan-write and verify the dep's `engines.node` floor is satisfied by the project's `engines.node` declaration. Tool deps in particular raise their Node floor between minor versions inside a major.

**Verification** (at plan-write time):

```bash
npm view <pkg>@<picked-major> engines.node
```

Cite the result in the plan body (e.g., "lint-staged@17.0.7 requires Node ≥22.22.1; project `engines.node=>=22` satisfies").

**Rationale**: Phase 5.1 T9 plan prescribed `"lint-staged": "^17.0.0"` alongside Node 18 LTS. `lint-staged@17.0.7` (the resolved version) requires Node ≥22.22.1; Node 18 fails the engine check. Path pivot to Node 22.22.3 corrected mid-task. See sibling RC-33 for the caret-on-active-major refinement.

## Caret-on-active-major: verify both floor and latest-patch engines (Phase 5.1 RC-33)

When a plan pins a dep as `"^<major>.0.0"` (caret-on-major), `npm install` resolves to the latest `<major>.x.y` at install-time. The resolved patch's `engines.node` floor may differ from `<major>.0.0`'s. The plan-write `engines.node` check (per sibling RC-32) must query BOTH the floor of `<picked-major>.0.0` AND the latest `<picked-major>.x.y`; if they diverge, plan for the higher floor.

**Verification** (at plan-write time):

```bash
npm view <pkg>@<major>.0.0 engines.node      # floor for the major
npm view <pkg>@<major> engines.node          # latest patch in the major
```

If they differ, document the divergence in the plan body and pick the higher floor as the binding requirement.

**Rationale**: Phase 5.1 T9 — `lint-staged@17.0.0` declared a lower Node floor than `lint-staged@17.0.7` (the actually-resolved version). A plan-write check against `17.0.0` alone would have passed; install-time resolution against `^17` then failed the engines check on CI.

## Spec-target EOL check (Phase 5.1 RC-34)

For any plan that bumps a runtime / language / framework version, verify the target version's end-of-life date is at least 12 months in the future from plan-write date. Picking an already-EOL or imminently-EOL target invites a forced second bump in the next phase and locks the project into an unsupported runtime in the interim.

**Verification** (at plan-write time): cite the target version's EOL date from its official source (e.g., nodejs.org/dist/index.json for Node; endoflife.date for most ecosystems). Reject plans where EOL is < 12 months out from plan-write date.

**Rationale**: Phase 5.1 T9 plan prescribed Node 18 LTS as the modernization target; Node 18 entered full EOL April 2025 (13+ months before plan-write 2026-05-30). Path b corrected mid-task to Node 22 LTS (current LTS "Jod", supported through 2027). A pre-plan-write EOL check would have caught this.

## Pre-commit-hook framework version bumps: verify helper-dir layout (Phase 5.1 RC-35)

When a plan bumps a pre-commit hook framework's major version (husky, pre-commit, lefthook), verify the new major's on-disk layout against the old major BEFORE writing `rm -rf` directives. Husky v8 → v9 in particular moved the helper script location AND repurposed `.husky/_/` from an old-install-artifact to a runtime helper dir that the framework itself manages.

**Verification** (at plan-write time): consult the framework's migration guide for the picked major bump (e.g., husky v9 release notes); enumerate which directories the framework now OWNS at runtime; preserve those across the upgrade rather than deleting them.

**Rationale**: Phase 5.1 T9 plan Step 4 prescribed `rm -rf .husky/_` under the v8-install-artifact mental model; husky v9 owns `.husky/_/` as a runtime helper dir (gitignored by husky itself). The `rm -rf` was a no-op in practice but the plan's mental model was wrong; under different timing the directive could have raced husky's regeneration.

## Commit-message templates: placeholders for path-pivot-mutable claims (Phase 5.1 RC-37)

When a plan prescribes a commit message verbatim AND the task allows in-flight Path pivots (e.g., "if plugin v7 doesn't apply, fall back to v1.5.3"), the commit message's version numbers will bit-rot under any pivot. Prescribe templates with `<placeholder>` slots for path-pivot-mutable values rather than frozen strings; require the implementer to fill them in at commit time.

**Fix**: when a plan task has explicit Path A / Path B contingencies, the commit-message block MUST use `<...>` placeholders for any value that differs between paths. Frozen literal version numbers / file lists in the commit message are only acceptable when the task has no contingencies.

**Rationale**: Phase 5.1 T9 plan prescribed a commit message naming `1.5.3→7.0.1` (Path A killed mid-task), `14→18` (Path b changed to 22), and omitted the CI workflow edit (D1 added mid-task). The implementer amended all three correctly at commit time, but the pattern recurs and the cognitive load of catching plan-vs-actual divergence on every task is non-trivial.

## Verbatim BEFORE blocks: Read-tool verification at plan-review (Phase 5.1 RC-39)

When a plan provides a "Replace this verbatim" code block (often paired with a "with this verbatim" block), the BEFORE block is a contract — if it doesn't match the actual file byte-for-byte, the implementer's `Edit` will fail or (worse) silently transform the wrong code. Plan authors must read the actual file with the `Read` tool at plan-write time to verify the BEFORE block matches; plan reviewers must confirm this verification was done.

**Verification** (at plan-review time): for each "Replace verbatim" block, ask the plan author to cite the file path + line number of the BEFORE text. If the plan author can't cite, run a `Read` against the cited path to confirm match before approving.

**Rationale**: Phase 5.1 T10 plan Step 3 provided a "Replace with" code block; the corresponding BEFORE block (reflection inlined in `clearCaches()`) was stale. Current state had reflection extracted to a `clearCache(Object)` helper. The implementer caught the divergence via a pre-edit STOP per RC-26 discipline; an Option B pivot resulted. Verifying BEFORE blocks at plan-write time prevents this class of plan-implementation gap entirely.
