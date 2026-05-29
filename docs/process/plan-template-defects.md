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
