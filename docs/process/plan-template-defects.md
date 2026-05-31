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
