# Dev Environment Setup

Recipes for dev-env prerequisites + one-off cleanups surfaced during Phase 5 / 5.1.
New contributors should run these once during onboarding; existing contributors may
need them as ad-hoc cleanups when the underlying conditions recur.

## Docker Compose v2 (Phase 5 RC-17)

The standalone `docker-compose` v1 (Python script, version 1.29.x) ships with a
`KeyError: 'ContainerConfig'` bug that surfaces during multi-service `up -d`
operations and silently renames containers with hash prefixes (e.g.
`a5362504bfb0_openboxes-db`). This bug recurred 7+ times across Phase 5 + 5.1
multi-service smoke startups before being closed in this section.

Install the v2 plugin and purge the v1 standalone:

```bash
sudo apt install docker-compose-v2
sudo apt remove docker-compose python3-compose
```

Verify both invocation paths behave as expected:

```bash
docker compose version    # → Docker Compose version 2.40.3 (or newer)
docker-compose --version  # → command not found  (loud fail; intended)
```

The project's `docker/README.MD` documents `docker compose <command>` (with a
space) as the canonical invocation; the legacy hyphenated `docker-compose` form
is intentionally not preserved so scripts written against it fail loudly rather
than route to the buggy v1 binary.

## Docker group membership (Phase 5 RC-18)

TestContainers and most `docker` CLI workflows expect to talk to the docker
socket (`/var/run/docker.sock`, group-owned by `docker`) without `sudo`. Add the
dev user to the docker group:

```bash
sudo usermod -aG docker $USER
```

Then log out and back in, or use `sg docker -c '<command>'` to run a single
command in a subshell with the new group activated immediately.

**Security note.** Docker group membership is equivalent to root on the host —
any user in the group can `docker run -v /:/host ...` and bind-mount the host
filesystem into a container. This is acceptable on single-user dev VMs where the
user already has unrestricted (NOPASSWD) `sudo` — the marginal privilege delta
is zero. Do not adopt this convention on shared or production hosts, on hosts
where the dev user does NOT already have `sudo`, or in any context where
audit-logged privilege escalation matters.

## Build artifact ownership (Phase 5 RC-19)

When Gradle has been run via `sudo` (for example to build the docker-COPY'd jars
during a multi-service rebuild before docker-group membership was set up),
`services/*/build/` ends up root-owned. Subsequent non-sudo Gradle invocations,
particularly `--rerun-tasks`, fail with permission errors.

One-time cleanup:

```bash
sudo chown -R $USER:$USER /home/$USER/openboxes/services/*/build/
```

Prevention: once the dev user is in the docker group (per the section above),
Gradle no longer needs `sudo` for docker-related tasks, and TestContainers runs
without `sudo`. The chown above should be a one-time recovery action, not a
recurring maintenance task.

## JDK toolchain split: Temurin 8 (Grails) + JDK 17 (services) (Phase 5.5 RC-20)

This repo builds with **two JDKs**, by design (decided at migration start):

- **Root Grails monolith** (Grails 3.3.16 / Gradle 4.10.3 / Groovy 2.4.21,
  `sourceCompatibility = 1.8`) → **Temurin 8**. This is what `docker/Dockerfile`
  (`FROM eclipse-temurin:8-jre-jammy`) and every `.github/workflows/*` backend job
  (`setup-java` with `java-version: 8, distribution: temurin`) use.
- **`services/` Spring Boot modules** (Gradle 8.5) → **JDK 17** (JDK 21 also works,
  being a 17 superset; the e2e workflow provisions JDK 21 "for services").

**Symptom when the Grails build is run under a modern JDK (e.g. 21):** Gradle 4.10.3
only supports up to Java 11, so under JDK 17/21 the root build breaks in two ways that
look unrelated but share this one root cause:

1. `:generateGitProperties` fails with
   `No such property: out for class: com.gorylenko.writer.NormalizeEOLOutputStream`
   (the `gradle-git-properties` 2.2.4 plugin's `FilterOutputStream.out` field is not
   reachable via Groovy property access under JDK 17+ module encapsulation).
2. The Spock **test worker hangs indefinitely** — it compiles, then sits at ~0% CPU
   producing no results (illegal reflective access in the Grails 3.3 test runtime).

Both vanish under Temurin 8. (Surfaced in Phase 5.5 T13, the first task to touch the
root build; T6–T12 only used the `services/` Gradle 8.5 build, so they never hit it.)

Install Temurin 8 (Adoptium repo, matching CI/Docker):

```bash
sudo mkdir -p /etc/apt/keyrings
wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public \
  | sudo tee /etc/apt/keyrings/adoptium.asc > /dev/null
echo "deb [signed-by=/etc/apt/keyrings/adoptium.asc] https://packages.adoptium.net/artifactory/deb $(. /etc/os-release && echo $VERSION_CODENAME) main" \
  | sudo tee /etc/apt/sources.list.d/adoptium.list
sudo apt-get update && sudo DEBIAN_FRONTEND=noninteractive apt-get install -y temurin-8-jdk
```

Run root Grails Gradle tasks with `JAVA_HOME` pointed at Temurin 8 (leave the default
`java` as the services JDK):

```bash
JAVA_HOME=/usr/lib/jvm/temurin-8-jdk-amd64 ./gradlew test integrationTest
```

**Do NOT** set `org.gradle.java.home` in `~/.gradle/gradle.properties` to fix this —
that variable is global to the user and would force the `services/` Spring Boot build
onto JDK 8 too (which it cannot use). Scope JDK 8 to the root build via `JAVA_HOME`
per-invocation, exactly as CI does (separate `setup-java` steps per module).

## `sg docker` for local Playwright + Gradle TestContainers (Phase 6 RC-62)

(Extends "Docker group membership (RC-18)".) Local commands that talk to the docker socket must run under `sg docker -c '...'` when the shell wasn't started with docker-group membership active:

- **Playwright specs that shell out to `docker exec`** (e.g. self-seeding round-trips) fail at their own `execSync('docker exec ...')` helper without it — NOT a product bug. Run: `sg docker -c 'BASE_URL=http://localhost npx playwright test'`.
- **Gradle TestContainers runs** need `sg docker -c './gradlew --no-daemon ...'` — `--no-daemon` avoids reusing a stale daemon started without docker-group access.

CI runners have docker access, so this is local-only.

## Minimal seed against the live Grails schema (Phase 6 RC-65)

The dev/CI DB is empty of most domain rows, so a non-empty read/round-trip e2e needs a seed. Recipe for a minimal, idempotent seed that survives schema drift:

1. Find the columns you MUST populate: `SELECT column_name FROM information_schema.columns WHERE table_name='<t>' AND is_nullable='NO' AND column_default IS NULL;` — INSERT only those + the field(s) under test.
2. Make it idempotent: prefix synthetic ids (e.g. `rc16-%`) and `DELETE FROM <t> WHERE id LIKE '<prefix>-%';` before INSERT (delete-first, not UPDATE).
3. Respect FK preconditions: document which demo rows the seed depends on (it FK-fails loudly if they change).

**Rationale**: Phase 6 T7's RC-16 read-through seed (`docker/seed-rc16-abc-class.sql`) used exactly this; product needed only id/version/name/dates, inventory_level only id/version.
