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
