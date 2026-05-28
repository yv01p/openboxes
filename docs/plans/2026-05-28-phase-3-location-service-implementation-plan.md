# Phase 3 Location-Service Implementation Plan

> **For agentic workers:** REQUIRED: Use `superpowers:subagent-driven-development` to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Source spec:** `docs/specs/2026-05-27-phase-3-location-service-design.md` (commit SHA: `26ae7efef090112ccf562776e45b9a16c8ebacb7`)

**Goal:** Stand up `location-service` as a new Spring Boot 3.x / Java 21 read-only HTTP slice exposing GET endpoints for the 3 location-bounded entities (Location, LocationGroup, LocationType) under `/api/location/*` (singular), while preserving all Grails writes so in-JVM events (`InventorySnapshotEvent`, `RefreshProductAvailabilityEvent`) keep firing.

**Architecture:** New 6th docker container `openboxes-location-service` on port 8083 sharing the openboxes MariaDB via `spring.jpa.hibernate.ddl-auto=validate`. 5 Liquibase shadow changelogs use `tableExists` precondition (no DDL emitted). nginx routes `/api/location/` (singular, trailing slash) to location-service while `/api/locations/` (plural) stays Grails. JWT cookie validation copied from identity-service (`JwtCookieAuthFilter` + `JwtService` subset — `validate()` only, no `issue()`). Flat DTOs expose FK IDs only — no foreign-entity inflation (FD#3 pick (c)).

**Tech stack:** Spring Boot 3.3.5 + Java 21 + Hibernate 6 + Spring Data JPA + Spring Security 6 + jjwt 0.12.5 + Liquibase + springdoc-openapi 2.5.0 + JUnit 5 + TestContainers 1.21.3 (MariaDB).

---

## File Structure

**Create — Module bootstrap:**
- `services/location-service/build.gradle`
- `services/location-service/Dockerfile`
- `services/location-service/src/main/java/org/openboxes/location/LocationServiceApplication.java`
- `services/location-service/src/main/resources/application.yml`

**Create — Liquibase shadow changelogs:**
- `services/location-service/src/main/resources/db/changelog/db.changelog-master.xml`
- `services/location-service/src/main/resources/db/changelog/changelog-shadow-create-location.xml`
- `services/location-service/src/main/resources/db/changelog/changelog-shadow-create-location-group.xml`
- `services/location-service/src/main/resources/db/changelog/changelog-shadow-create-location-type.xml`
- `services/location-service/src/main/resources/db/changelog/changelog-shadow-create-location-supported-activities.xml`
- `services/location-service/src/main/resources/db/changelog/changelog-shadow-create-location-type-supported-activities.xml`

**Create — JPA entities + enum mirrors + repositories:**
- `services/location-service/src/main/java/org/openboxes/location/entity/Location.java`
- `services/location-service/src/main/java/org/openboxes/location/entity/LocationGroup.java`
- `services/location-service/src/main/java/org/openboxes/location/entity/LocationType.java`
- `services/location-service/src/main/java/org/openboxes/location/enums/LocationTypeCode.java`
- `services/location-service/src/main/java/org/openboxes/location/enums/SupportedActivitiesEnum.java`
- `services/location-service/src/main/java/org/openboxes/location/repository/LocationRepository.java`
- `services/location-service/src/main/java/org/openboxes/location/repository/LocationGroupRepository.java`
- `services/location-service/src/main/java/org/openboxes/location/repository/LocationTypeRepository.java`

**Create — Security:**
- `services/location-service/src/main/java/org/openboxes/location/security/JwtCookieAuthFilter.java`
- `services/location-service/src/main/java/org/openboxes/location/security/JwtService.java`
- `services/location-service/src/main/java/org/openboxes/location/security/SecurityConfig.java`

**Create — Service layer + DTOs:**
- `services/location-service/src/main/java/org/openboxes/location/service/LocationTypeCache.java`
- `services/location-service/src/main/java/org/openboxes/location/service/LocationFilterService.java`
- `services/location-service/src/main/java/org/openboxes/location/dto/LocationDto.java`
- `services/location-service/src/main/java/org/openboxes/location/dto/LocationGroupDto.java`
- `services/location-service/src/main/java/org/openboxes/location/dto/LocationTypeDto.java`

**Create — REST controllers:**
- `services/location-service/src/main/java/org/openboxes/location/controller/LocationController.java`
- `services/location-service/src/main/java/org/openboxes/location/controller/LocationGroupController.java`
- `services/location-service/src/main/java/org/openboxes/location/controller/LocationTypeController.java`

**Create — Tests:**
- `services/location-service/src/test/java/org/openboxes/location/LocationServiceIntegrationTest.java`
- `services/location-service/src/test/resources/seed.sql`
- `e2e/tests/location-service.spec.ts`

**Create — Retrospective:**
- `docs/retrospectives/YYYY-MM-DD-phase-3-location-retrospective.md` (filled in at done-gate)

**Modify:**
- `services/settings.gradle` — add `include 'location-service'`
- `docker/docker-compose-base.yml` — add `openboxes-location-service` service block (mirror identity-service)
- `docker/docker-compose.yml` — add `location-service` extends+depends_on block + add `location-service: service_healthy` to nginx depends_on
- `docker/nginx/conf.d/app.conf` — add `location /api/location/` block BEFORE `/api/` catch-all
- `.github/workflows/e2e-tests.yml` — add `:location-service:bootJar` to build step + location-service healthcheck probe + log dump

---

## Inherited from spec

The 15 load-bearing assumptions verified by `thorough-brainstorming` at spec-write time (commit `2e70b7c91`), reconfirmed in CDR R1 (commit `7bc5064a6`). Trusted as ground truth — NOT re-verified at plan-write time:

| # | Assumption | Spec evidence |
|---|---|---|
| A1/F1 | 3 Grails domain classes exist (Location, LocationGroup, LocationType); `LocationStatus` is a transient computed enum, not an entity | Spec §17 row A1 |
| A2/F2 | `location_status` table does NOT exist; actual tables are location, location_group, location_type, location_role, location_supported_activities, location_type_supported_activities, location_dimension | Spec §17 row A2 |
| A3 | `/api/locations/*` URL mappings resolve to LocationApiController at `grails-app/controllers/.../UrlMappings.groovy:158-188` | Spec §17 row A3 |
| A4 | React uses `/api/locations/*` (plural) consistently across 16 files; no singular `/api/location/` usage anywhere | Spec §17 row A4 |
| A5 | identity-service maps Location as JPA entity at `services/identity-service/src/main/java/org/openboxes/identity/entity/Location.java`; AuthService reads location via shared-DB JPA | Spec §17 row A5 |
| A6/F3 | Bins and zones are rows in the SAME `location` table; distinguished by `LocationType.locationTypeCode` | Spec §17 row A6 |
| A7+A14/F4 | Real Location writes localized to ~5 Grails paths in `core/` package; cross-context callers are mostly transient constructors not writes | Spec §17 row A7+A14 |
| A8 | LocationApiController = 287 LOC, LocationService = 725 LOC, LocationController = 435 LOC | Spec §17 row A8 |
| A9 | Liquibase shadow pattern works (no FK ordering); empty body + precondition | Spec §17 row A9 |
| A10 | document-service + identity-service templates stable at `services/{document,identity}-service/` | Spec §17 row A10 |
| A11 | `services/settings.gradle` controls module inclusion | Spec §17 row A11 |
| A12/F6 | `JwtCookieAuthFilter` duplicated in document-service + identity-service (not a shared module) | Spec §17 row A12 |
| A13 | nginx config supports prefix routing per existing `/api/identity` + `/api/documents` blocks | Spec §17 row A13 |
| A15 | `Location.groovy` has no inheritance hierarchy; `afterInsert/afterUpdate/afterDelete` publishes `InventorySnapshotEvent` + `RefreshProductAvailabilityEvent` (F5 — the read-only pivot driver) | Spec §17 row A15 |

---

## Verified plan-level assumptions

Newly introduced by this plan (paths, signatures, commands, ordering, code-in-plan validity, consumer impact). Each verified at plan-write time against repo `26ae7efef`:

| # | Category | Assumption | Evidence |
|---|---|---|---|
| 1 | File path | `services/location-service/` does not yet exist | `ls -d services/location-service/` → No such file or directory |
| 2 | File path | `services/settings.gradle` contains only `document-service` + `identity-service` | Read `services/settings.gradle` lines 1-4 |
| 3 | File path | `services/identity-service/{build.gradle, Dockerfile, src/main/resources/application.yml, src/main/java/.../security/JwtCookieAuthFilter.java, src/main/java/.../service/JwtService.java, src/main/java/.../entity/Location.java}` all exist (templates for T2/T5) | Reads of each file at plan-write time |
| 4 | File path | `docker/{docker-compose-base.yml, docker-compose.yml, nginx/conf.d/app.conf}` all exist | Reads of each |
| 5 | File path | `.github/workflows/e2e-tests.yml` exists with identity-service healthcheck probe (line 44) + log dump (line 78) | Read of file |
| 6 | File path | `e2e/`, `docs/retrospectives/` directories exist | `ls -d` |
| 7 | Signature | `LocationTypeCode.listInternalTypeCodes()` returns `[BIN_LOCATION, INTERNAL]` (drives T4 enum mirror + T6 filter set) | `src/main/groovy/org/pih/warehouse/core/LocationTypeCode.groovy:55-57` |
| 8 | Signature | `ActivityCode.list()` returns **30** values (NOT 31 as spec §6 says; spec count is off-by-one). Drives T4 SupportedActivitiesEnum mirror | `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy:80-113` (count enumeration) |
| 9 | Signature | `JwtCookieAuthFilter` constructor takes `JwtService` (`services/identity-service/.../security/JwtCookieAuthFilter.java:23`); `JwtService.validate(String)` returns `Map<String, Object>` (`services/identity-service/.../service/JwtService.java:39-46`); `JwtService.COOKIE_NAME = "obx_token"` constant (line 18). T5 copies BOTH files; T5 can OMIT `JwtService.issue()` (line 26) since location-service issues no tokens and `issue()` references identity-service's `User` entity which location-service does not have | Reads of both files |
| 10 | Signature | identity-service `Location.java` maps ONLY `(id CHAR(38), name, active)` — minimal. location-service can declare more columns without ddl-auto:validate conflict (validate only checks declared columns exist; doesn't require all schema columns be declared) | Read of `services/identity-service/src/main/java/org/openboxes/identity/entity/Location.java:11-36` |
| 11 | Signature / JPA | `LocationType.locationTypeCode` is persisted as enum string (Grails convention; T4 will use `@Enumerated(EnumType.STRING)` + `@Column(name = "location_type_code")`). T6 filter uses `Set<LocationTypeCode>` (NOT `Set<String>`) for type-safe JPQL `NOT IN :internalTypes` matching | Inferred from Grails convention; T1 audit verifies actual MariaDB column type |
| 12 | Code-in-plan | Spring Boot 3.3.5 BOM; Hibernate 6; jjwt 0.12.5; springdoc-openapi 2.5.0; TestContainers 1.21.3; `api.version 1.44`; `testcontainers.ryuk.disabled=true` — all carry forward from identity-service | `services/identity-service/build.gradle` lines 1-46 |
| 13 | Command | `cd services && ./gradlew :location-service:bootJar` works once T2 adds the module to settings.gradle | CI pattern at `.github/workflows/e2e-tests.yml:34-36`: `working-directory: services` + `./gradlew :identity-service:bootJar :document-service:bootJar` |
| 14 | Command | `cd services && sudo -E ./gradlew :location-service:test` runs JUnit + TestContainers (sudo for docker socket; `-E` to preserve env) | Phase 2 retro convention |
| 15 | Command | `cd e2e && npm test` runs Playwright suite | `.github/workflows/e2e-tests.yml:69-70`: `working-directory: e2e` + `npm test` |
| 16 | Command | Local rebuild: `cd docker && sudo docker-compose down && sudo docker-compose up -d --build` (v1 hyphenated; CI uses `docker compose` v2) | Spec §11 done-gate; Phase 2 retro |
| 17 | Command | `docker exec openboxes-location-service curl -sf localhost:8083/actuator/health` is the healthcheck command (mirror identity-service at port 8082) | Existing pattern at `.github/workflows/e2e-tests.yml:44` |
| 18 | Ordering | T4 (entities) imports nothing from T5-T13; T5 (security) is independent of business logic; T6 (cache + filter + DTOs) imports only from T4; T7 (controllers) imports from T4-T6; T9 (JUnit) requires T2-T8; T10 (Playwright) requires T8 (nginx routing); T11 (CI) is independent | Plan structure (no forward references in code blocks) |
| 19 | Consumer | `services/settings.gradle` `include 'location-service'` is additive — does not break existing document/identity includes (Gradle `include` is purely additive) | Standard Gradle settings behavior |
| 20 | Consumer | Port 8083 + container name `openboxes-location-service` don't conflict with existing services (8080 app, 8081 document, 8082 identity) | Read of `docker/docker-compose-base.yml:8,32,50` |
| 21 | Consumer | nginx `location /api/location/` block (WITH trailing slash) does NOT match `/api/locations/x` — `/api/locations` has `s` after `/api/location`, breaking the trailing-slash prefix match. Existing `/api/identity` + `/api/documents` blocks (no trailing slash) work because no `/api/identityX` or `/api/documentsX` paths exist; for `/api/location` the collision with `/api/locations` is real, so trailing slash is mandatory and PATTERN DIVERGENCE from existing blocks is intentional (do not "fix" to remove trailing slash) | Spec §8 line 174 acknowledges; nginx prefix-matching semantics |
| 22 | Consumer | nginx `depends_on` lives in `docker/docker-compose.yml` (dev compose) at lines 31-41 — NOT in `docker-compose-base.yml`. T8 edits docker-compose.yml (NOT base.yml) for the depends_on update. Similarly, T2 adds the location-service `extends + depends_on` block in docker-compose.yml mirroring the identity-service block at lines 21-29. `docker-compose-hostdb.yml` + `docker-compose-remotedb.yml` overlays don't track per-service depends_on for nginx (their pattern is `depends_on: [app]` only); leave those alone | Read of `docker/docker-compose.yml:31-41` + `docker-compose-base.yml:73-80` + `docker-compose-hostdb.yml:12-17` |
| 23 | Consumer | CI workflow probe + log dump additions in `.github/workflows/e2e-tests.yml` mirror identity-service pattern (additive lines next to existing identity probes; doesn't disturb document-service or app probes) | Read of `.github/workflows/e2e-tests.yml:34-48,72-79` |
| 24 | Consumer | identity-service Location.java (minimal: id, name, active) won't conflict with location-service Location.java (full mapping) at ddl-auto:validate boot time — Hibernate validate only fails if a DECLARED column doesn't exist in the schema; both services declaring overlapping columns is fine, both validating against the same `location` table is fine | Hibernate validate mode semantics + read of identity Location.java |
| 25 | Consumer | Adding nginx `depends_on: location-service: service_healthy` doesn't break startup as long as location-service healthcheck passes (`service_healthy` requires the dependency to be healthy, not just started) | Existing identity-service + document-service pattern at `docker/docker-compose.yml:35-41` |

---

## Tasks

### Task 1: Scope audit + live-smoke-probe

**Files:** (read-only; no writes)

- [ ] **Step 1: Scope audit subagent** — Dispatch a sonnet subagent with this checklist:
  - Verify `services/location-service/` does NOT exist (`ls -d services/location-service/` → No such file).
  - Verify `services/settings.gradle` line set unchanged from `26ae7efef`.
  - Verify 5 dev containers Up healthy: `sudo docker ps --filter name=openboxes --format "table {{.Names}}\t{{.Status}}"` → 5 rows (db, app, document-service, identity-service, nginx).
  - Verify `LocationTypeCode.listInternalTypeCodes() = [BIN_LOCATION, INTERNAL]` at `src/main/groovy/org/pih/warehouse/core/LocationTypeCode.groovy:55-57`.
  - Verify `ActivityCode.list()` enum-value count at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy:80-113` (expected: 30; if differs from 30, update plan T4 SupportedActivitiesEnum to match).
  - Verify `location` table column names + types via:
    ```bash
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM location"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM location_group"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM location_type"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM location_supported_activities"
    sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SHOW COLUMNS FROM location_type_supported_activities"
    ```
  - Capture actual column names + types for T4 entity column annotations. Especially verify: `parent_location_id`, `zone_id`, `location_type_id`, `location_group_id`, `organization_id`, `address_id`, `manager_id`, `inventory_id` on `location` table.
  - Verify `LocationType.location_type_code` column type is VARCHAR (not INT) — drives T4 `@Enumerated(EnumType.STRING)` annotation choice.
  - Verify identity-service Location.java unchanged (still maps only id/name/active) at `services/identity-service/src/main/java/org/openboxes/identity/entity/Location.java:11-36`.

- [ ] **Step 2: Live-smoke-probe (regression baseline)** — Through the running dev stack:
  ```bash
  # Replace <REAL-LOCATION-ID> with an actual location UUID from the DB
  sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SELECT id, name FROM location LIMIT 3"
  # Then with valid obx_token cookie (login via admin/password first):
  curl -sf -b "obx_token=<TOKEN>" http://localhost/api/locations/<REAL-LOCATION-ID> | jq .
  ```
  Capture the response shape. Save as `/tmp/grails-location-response.json` for later T6 DTO comparison + T9 regression assertion fixture.

- [ ] **Step 3: Report findings** — Subagent reports:
  - All 25 plan-level assumptions reconfirmed (or drift surfaced).
  - Actual `location` table column list (for T4 entity annotation precision).
  - Live-probe response shape (which fields are inflated vs FKs).
  - If `ActivityCode.list()` count differs from 30, surface as plan-edit-needed before T4.
  - If `LocationType.location_type_code` column is NOT varchar, surface as plan-edit-needed (entity annotation differs).

**Done when:** Subagent reports all 25 plan-level assumptions reconfirmed AND live-probe baseline captured AND no drift items requiring plan revision. If drift surfaces, halt + present to user before T2.

---

### Task 2: Bootstrap module + docker-compose entries

**Files:**
- Create: `services/location-service/build.gradle`, `services/location-service/Dockerfile`, `services/location-service/src/main/java/org/openboxes/location/LocationServiceApplication.java`, `services/location-service/src/main/resources/application.yml`
- Modify: `services/settings.gradle`, `docker/docker-compose-base.yml`, `docker/docker-compose.yml`

- [ ] **Step 1: Add module to `services/settings.gradle`**
```diff
 rootProject.name = 'openboxes-services'
 include 'document-service'
 include 'identity-service'
+include 'location-service'
```

- [ ] **Step 2: Create `services/location-service/build.gradle`** (mirror identity-service minus `spring-boot-starter-mail`):
```gradle
ext['testcontainers.version'] = '1.21.3'

dependencies {
    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-security'
    implementation 'org.springframework.boot:spring-boot-starter-validation'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'org.liquibase:liquibase-core'
    implementation 'org.springdoc:springdoc-openapi-starter-webmvc-ui:2.5.0'
    implementation 'io.jsonwebtoken:jjwt-api:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-impl:0.12.5'
    runtimeOnly 'io.jsonwebtoken:jjwt-jackson:0.12.5'
    runtimeOnly 'org.mariadb.jdbc:mariadb-java-client:3.4.1'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
    testImplementation 'org.springframework.security:spring-security-test'
    testImplementation 'org.testcontainers:junit-jupiter:1.21.3'
    testImplementation 'org.testcontainers:mariadb:1.21.3'
}

test {
    useJUnitPlatform()
    systemProperty 'api.version', '1.44'
    systemProperty 'testcontainers.ryuk.disabled', 'true'
}
```

- [ ] **Step 3: Create `services/location-service/Dockerfile`** (mirror identity-service; change port + jar name):
```dockerfile
FROM eclipse-temurin:21-jre-jammy

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl \
    && rm -rf /var/lib/apt/lists/*

EXPOSE 8083
WORKDIR /app
COPY build/libs/location-service-*.jar /app/location-service.jar

RUN useradd -r spring
USER spring

ENTRYPOINT ["java", "-jar", "/app/location-service.jar"]
```

- [ ] **Step 4: Create `services/location-service/src/main/java/org/openboxes/location/LocationServiceApplication.java`**:
```java
package org.openboxes.location;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class LocationServiceApplication {
    public static void main(String[] args) {
        SpringApplication.run(LocationServiceApplication.class, args);
    }
}
```

- [ ] **Step 5: Create `services/location-service/src/main/resources/application.yml`** (no mail config; otherwise mirror identity-service):
```yaml
server:
  port: 8083
spring:
  application:
    name: location-service
  datasource:
    url: ${DATASOURCE_URL}
    username: ${DATASOURCE_USERNAME}
    password: ${DATASOURCE_PASSWORD}
    driver-class-name: org.mariadb.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate.dialect: org.hibernate.dialect.MariaDBDialect
  liquibase:
    enabled: true
    change-log: classpath:db/changelog/db.changelog-master.xml
management:
  endpoints:
    web:
      exposure:
        include: health,info
openboxes:
  jwt:
    secret: ${OPENBOXES_JWT_SECRET}
```

- [ ] **Step 6: Add service block to `docker/docker-compose-base.yml`** (after identity-service block at line 71, before nginx at line 73):
```yaml
    location-service:
      build:
        context: ../services/location-service
        dockerfile: Dockerfile
      container_name: openboxes-location-service
      expose:
        - "8083"
      environment:
        DATASOURCE_URL: ${DATASOURCE_URL:-jdbc:mariadb://db:3306/openboxes?serverTimezone=UTC&useSSL=false}
        DATASOURCE_USERNAME: ${DATASOURCE_USERNAME:-openboxes}
        DATASOURCE_PASSWORD: ${DATASOURCE_PASSWORD:-openboxes}
        OPENBOXES_JWT_SECRET: ${OPENBOXES_JWT_SECRET:-dev-secret-only-for-local-please-rotate-in-prod}
      healthcheck:
        test: "curl --fail --silent localhost:8083/actuator/health | grep '\"status\":\"UP\"' || exit 1"
        interval: 10s
        timeout: 5s
        retries: 5
        start_period: 30s
```

- [ ] **Step 7: Add extends+depends_on block to `docker/docker-compose.yml`** (mirror identity-service block at lines 21-29; insert before nginx at line 31):
```yaml
    location-service:
      extends:
        file: docker-compose-base.yml
        service: location-service
      depends_on:
        db:
          condition: service_healthy
        app:
          condition: service_healthy
```

- [ ] **Step 8: Build the bare service jar**
```bash
cd services && ./gradlew :location-service:bootJar
```

- [ ] **Step 9: Rebuild + boot the stack**
```bash
cd docker && sudo docker-compose down && sudo docker-compose up -d --build
```

- [ ] **Step 10: Verify location-service is up + healthy**
```bash
sudo docker ps --filter name=openboxes-location-service --format "table {{.Names}}\t{{.Status}}"
# Expected: openboxes-location-service ... Up X seconds (healthy)
sudo docker exec openboxes-location-service curl -sf localhost:8083/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 11: Commit**
```bash
git add services/settings.gradle services/location-service/build.gradle services/location-service/Dockerfile services/location-service/src/main/java/org/openboxes/location/LocationServiceApplication.java services/location-service/src/main/resources/application.yml docker/docker-compose-base.yml docker/docker-compose.yml
git commit -m "phase 3 task 2: bootstrap location-service module + docker-compose entry — bare Spring Boot 3.3.5 service on port 8083; boots healthy in 6th container; no entities/controllers yet (T3-T7 add them)"
```

---

### Task 3: Liquibase shadow changelogs

**Files:**
- Create: `services/location-service/src/main/resources/db/changelog/db.changelog-master.xml`
- Create: 5 shadow changelog XMLs under `services/location-service/src/main/resources/db/changelog/`

- [ ] **Step 1: Create master changelog**
```xml
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd">
    <include file="db/changelog/changelog-shadow-create-location.xml"/>
    <include file="db/changelog/changelog-shadow-create-location-group.xml"/>
    <include file="db/changelog/changelog-shadow-create-location-type.xml"/>
    <include file="db/changelog/changelog-shadow-create-location-supported-activities.xml"/>
    <include file="db/changelog/changelog-shadow-create-location-type-supported-activities.xml"/>
</databaseChangeLog>
```

- [ ] **Step 2: Create 5 shadow changelogs using `tableExists` precondition** (per spec §4 + R1 CDR fix). Template (substitute `X` per table):

```xml
<?xml version="1.1" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                       https://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.20.xsd"
                   logicalFilePath="services/location-service/db/changelog/changelog-shadow-create-X.xml">
    <changeSet id="phase3-shadow-create-X" author="openboxes-location">
        <preConditions onFail="MARK_RAN" onFailMessage="X table not found — Grails Liquibase must run first">
            <tableExists tableName="X"/>
        </preConditions>
        <comment>
            Shadow for X table. Grails Liquibase owns table creation.
            location-service uses spring.jpa.hibernate.ddl-auto=validate to prove entity-mapping correctness.
            tableExists works for both entity tables and pure M:N join tables (which have no `id` column).
        </comment>
        <!-- No body: table already exists per the precondition. -->
    </changeSet>
</databaseChangeLog>
```

Tables: `location`, `location_group`, `location_type`, `location_supported_activities`, `location_type_supported_activities`.

- [ ] **Step 3: Rebuild + boot to verify Liquibase shadows MARK_RAN cleanly**
```bash
cd services && ./gradlew :location-service:bootJar
cd ../docker && sudo docker-compose up -d --build location-service
sudo docker logs openboxes-location-service 2>&1 | grep -i "liquibase\|changeset"
# Expected: 5 lines like "ChangeSet ... ran successfully" or "Mark ran" (precondition satisfied)
```

- [ ] **Step 4: Verify DATABASECHANGELOG table has 5 new rows**
```bash
sudo docker exec openboxes-db mariadb -u root -proot openboxes -e "SELECT id, author FROM DATABASECHANGELOG WHERE author='openboxes-location'"
# Expected: 5 rows
```

- [ ] **Step 5: Commit**
```bash
git add services/location-service/src/main/resources/db/changelog/
git commit -m "phase 3 task 3: Liquibase shadow changelogs for location-service (5 tables via tableExists precondition) — MARK_RAN if Grails Liquibase already created the tables; no DDL body; satisfies Liquibase scope requirement for ddl-auto:validate"
```

---

### Task 4: JPA entities + enum mirrors + repositories

**Files:**
- Create: `services/location-service/src/main/java/org/openboxes/location/entity/{Location, LocationGroup, LocationType}.java`
- Create: `services/location-service/src/main/java/org/openboxes/location/enums/{LocationTypeCode, SupportedActivitiesEnum}.java`
- Create: `services/location-service/src/main/java/org/openboxes/location/repository/{Location, LocationGroup, LocationType}Repository.java`

- [ ] **Step 1: Create `enums/LocationTypeCode.java`** — Java enum mirror of Grails `LocationTypeCode` (per spec §17 A1 + verified-assumption #7). Values in declaration order:
```java
package org.openboxes.location.enums;

public enum LocationTypeCode {
    DEFAULT,
    DEPOT, ZONE, BIN_LOCATION, INTERNAL,
    DISPENSARY, WARD,
    SUPPLIER, DONOR,
    CONSUMER,
    DISTRIBUTOR, DISPOSAL, VIRTUAL;

    public static java.util.Set<LocationTypeCode> listInternalTypeCodes() {
        return java.util.Set.of(BIN_LOCATION, INTERNAL);
    }
}
```

(Note: ZONE intentionally NOT in `listInternalTypeCodes()` — preserves Grails parity per FD#2 decision.)

- [ ] **Step 2: Create `enums/SupportedActivitiesEnum.java`** — Java enum mirror of Grails `ActivityCode.list()` (per FD#1 + verified-assumption #8). Exactly the 30 values returned by `ActivityCode.list()` at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy:80-113`:
```java
package org.openboxes.location.enums;

import java.util.List;

public enum SupportedActivitiesEnum {
    MANAGE_INVENTORY, ADJUST_INVENTORY, APPROVE_ORDER, APPROVE_REQUEST,
    PLACE_ORDER, PLACE_REQUEST, FULFILL_ORDER, FULFILL_REQUEST,
    SEND_STOCK, RECEIVE_STOCK, CONSUME_STOCK,
    CROSS_DOCKING, PUTAWAY_STOCK, PICK_STOCK,
    EXTERNAL,
    ENABLE_NOTIFICATIONS, ENABLE_WEBHOOKS,
    ENABLE_REQUESTOR_APPROVAL_NOTIFICATIONS, ENABLE_FULFILLER_APPROVAL_NOTIFICATIONS,
    PACK_SHIPMENT, PARTIAL_RECEIVING,
    REQUIRE_ACCOUNTING, ENABLE_CENTRAL_PURCHASING,
    HOLD_STOCK, SUBMIT_REQUEST, DYNAMIC_CREATION,
    AUTOSAVE, ALLOW_OVERPICK, CYCLE_COUNT,
    NONE;

    public static List<String> list() {
        return java.util.Arrays.stream(values()).map(Enum::name).toList();
    }
}
```

(If T1 audit reports a different count, update this list to match `ActivityCode.list()` exact output.)

- [ ] **Step 3: Create `entity/LocationType.java`**:
```java
package org.openboxes.location.entity;

import jakarta.persistence.*;
import org.openboxes.location.enums.LocationTypeCode;
import java.util.Set;

@Entity
@Table(name = "location_type")
public class LocationType {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "location_type_code", length = 255)
    @Enumerated(EnumType.STRING)
    private LocationTypeCode locationTypeCode;

    @Column(name = "sort_order")
    private Integer sortOrder;

    @ElementCollection
    @CollectionTable(
        name = "location_type_supported_activities",
        joinColumns = @JoinColumn(name = "location_type_id"))
    @Column(name = "supported_activities_string")
    private Set<String> supportedActivities;

    // Getters only (read-only entity)
    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public LocationTypeCode getLocationTypeCode() { return locationTypeCode; }
    public Integer getSortOrder() { return sortOrder; }
    public Set<String> getSupportedActivities() { return supportedActivities; }
}
```

- [ ] **Step 4: Create `entity/LocationGroup.java`**:
```java
package org.openboxes.location.entity;

import jakarta.persistence.*;

@Entity
@Table(name = "location_group")
public class LocationGroup {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(length = 255)
    private String name;

    @Column(name = "address_id", columnDefinition = "CHAR(38)")
    private String addressId;

    public String getId() { return id; }
    public String getName() { return name; }
    public String getAddressId() { return addressId; }
}
```

- [ ] **Step 5: Create `entity/Location.java`** (per FD#3 pick (c) — flat FKs, no foreign-entity inflation):
```java
package org.openboxes.location.entity;

import jakarta.persistence.*;
import java.util.Set;

@Entity
@Table(name = "location")
public class Location {

    @Id
    @Column(columnDefinition = "CHAR(38)")
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(name = "location_number", length = 255)
    private String locationNumber;

    @Column
    private Boolean active;

    @Column(name = "sort_order")
    private Integer sortOrder;

    // FK-only mappings (FD#3 pick (c) — no foreign-entity inflation)
    @Column(name = "parent_location_id", columnDefinition = "CHAR(38)")
    private String parentLocationId;

    @Column(name = "zone_id", columnDefinition = "CHAR(38)")
    private String zoneId;

    @Column(name = "location_group_id", columnDefinition = "CHAR(38)")
    private String locationGroupId;

    @Column(name = "organization_id", columnDefinition = "CHAR(38)")
    private String organizationId;

    @Column(name = "manager_id", columnDefinition = "CHAR(38)")
    private String managerId;

    @Column(name = "address_id", columnDefinition = "CHAR(38)")
    private String addressId;

    // LocationType is loaded for filter logic (NOT_IN :internalTypes); also exposed in DTO
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_type_id")
    private LocationType locationType;

    @ElementCollection
    @CollectionTable(
        name = "location_supported_activities",
        joinColumns = @JoinColumn(name = "location_id"))
    @Column(name = "supported_activities_string")
    private Set<String> supportedActivities;

    public String getId() { return id; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLocationNumber() { return locationNumber; }
    public Boolean getActive() { return active; }
    public Integer getSortOrder() { return sortOrder; }
    public String getParentLocationId() { return parentLocationId; }
    public String getZoneId() { return zoneId; }
    public String getLocationGroupId() { return locationGroupId; }
    public String getOrganizationId() { return organizationId; }
    public String getManagerId() { return managerId; }
    public String getAddressId() { return addressId; }
    public LocationType getLocationType() { return locationType; }
    public Set<String> getSupportedActivities() { return supportedActivities; }
}
```

(T1 audit verifies exact column names; adjust `@Column(name=...)` if any differs.)

- [ ] **Step 6: Create repositories**
```java
// LocationRepository.java
package org.openboxes.location.repository;

import org.openboxes.location.entity.Location;
import org.openboxes.location.enums.LocationTypeCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;
import java.util.Set;

public interface LocationRepository extends JpaRepository<Location, String> {
    @Query("SELECT l FROM Location l WHERE l.id = :id AND l.locationType.locationTypeCode NOT IN :internalTypes")
    Optional<Location> findByIdExcludingInternal(@Param("id") String id, @Param("internalTypes") Set<LocationTypeCode> internalTypes);

    @Query("SELECT l FROM Location l WHERE l.locationType.locationTypeCode NOT IN :internalTypes AND (:active IS NULL OR l.active = :active)")
    List<Location> findAllExcludingInternal(@Param("internalTypes") Set<LocationTypeCode> internalTypes, @Param("active") Boolean active);
}

// LocationGroupRepository.java
package org.openboxes.location.repository;

import org.openboxes.location.entity.LocationGroup;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationGroupRepository extends JpaRepository<LocationGroup, String> {}

// LocationTypeRepository.java
package org.openboxes.location.repository;

import org.openboxes.location.entity.LocationType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LocationTypeRepository extends JpaRepository<LocationType, String> {}
```

- [ ] **Step 7: Rebuild + verify ddl-auto:validate passes at boot**
```bash
cd services && ./gradlew :location-service:bootJar
cd ../docker && sudo docker-compose up -d --build location-service
sudo docker logs openboxes-location-service 2>&1 | tail -50
# Expected: NO "Schema-validation: missing column" or "missing table" errors
# Expected: Hibernate "validation successful" or no validation errors
sudo docker exec openboxes-location-service curl -sf localhost:8083/actuator/health
# Expected: {"status":"UP"}
```

- [ ] **Step 8: Commit**
```bash
git add services/location-service/src/main/java/org/openboxes/location/entity/ services/location-service/src/main/java/org/openboxes/location/enums/ services/location-service/src/main/java/org/openboxes/location/repository/
git commit -m "phase 3 task 4: JPA entities + enum mirrors + repositories — Location/LocationGroup/LocationType with FK-only mappings (FD#3 pick (c)); LocationTypeCode + SupportedActivitiesEnum mirror Grails enums; ddl-auto:validate passes at boot"
```

---

### Task 5: Security (JwtCookieAuthFilter + JwtService + SecurityConfig)

**Files:**
- Create: `services/location-service/src/main/java/org/openboxes/location/security/JwtCookieAuthFilter.java`
- Create: `services/location-service/src/main/java/org/openboxes/location/security/JwtService.java`
- Create: `services/location-service/src/main/java/org/openboxes/location/security/SecurityConfig.java`

**Note:** JwtCookieAuthFilter depends on JwtService (per verified-assumption #9). Copy BOTH files from identity-service; rename packages to `org.openboxes.location.security`; OMIT `JwtService.issue()` since location-service issues no tokens (and `issue()` references identity-service's `User` entity which location-service does not have).

- [ ] **Step 1: Create `security/JwtService.java`** (validate-only subset of identity-service's JwtService):
```java
package org.openboxes.location.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Service
public class JwtService {
    public static final String COOKIE_NAME = "obx_token";
    private final SecretKey signingKey;

    public JwtService(@Value("${openboxes.jwt.secret}") String secret) {
        this.signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    public Map<String, Object> validate(String token) {
        try {
            return Jwts.parser().verifyWith(signingKey).build()
                .parseSignedClaims(token).getPayload();
        } catch (Exception e) {
            return null;
        }
    }
}
```

- [ ] **Step 2: Create `security/JwtCookieAuthFilter.java`** (copy from identity-service with package rename):
```java
package org.openboxes.location.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Component
public class JwtCookieAuthFilter extends OncePerRequestFilter {
    private final JwtService jwtService;
    public JwtCookieAuthFilter(JwtService jwt) { this.jwtService = jwt; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Cookie[] cookies = req.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (JwtService.COOKIE_NAME.equals(c.getName())) {
                    Map<String, Object> claims = jwtService.validate(c.getValue());
                    if (claims != null) {
                        String userId = (String) claims.get("sub");
                        String locationId = (String) claims.get("loc");
                        @SuppressWarnings("unchecked")
                        List<String> roleIds = (List<String>) claims.getOrDefault("roles", List.of());
                        req.setAttribute("userId", userId);
                        req.setAttribute("locationId", locationId);
                        req.setAttribute("roleIds", roleIds);
                        var authorities = roleIds.stream().map(SimpleGrantedAuthority::new).collect(Collectors.toList());
                        SecurityContextHolder.getContext().setAuthentication(
                            new UsernamePasswordAuthenticationToken(userId, null, authorities));
                    }
                    break;
                }
            }
        }
        chain.doFilter(req, res);
    }
}
```

- [ ] **Step 3: Create `security/SecurityConfig.java`** (allow actuator + openapi anonymous; require auth on everything else):
```java
package org.openboxes.location.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

@Configuration
public class SecurityConfig {
    private final JwtCookieAuthFilter jwtFilter;
    public SecurityConfig(JwtCookieAuthFilter f) { this.jwtFilter = f; }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(c -> c.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**").permitAll()
                .anyRequest().authenticated())
            .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
```

- [ ] **Step 4: Rebuild + verify auth behavior**
```bash
cd services && ./gradlew :location-service:bootJar
cd ../docker && sudo docker-compose up -d --build location-service
# Anonymous health check passes:
sudo docker exec openboxes-location-service curl -sf localhost:8083/actuator/health
# Expected: {"status":"UP"}
# Anonymous protected endpoint fails (will 401 once T7 controllers exist; for now 404):
sudo docker exec openboxes-location-service curl -sI localhost:8083/api/location/anything
# Expected: HTTP/1.1 404 (no controllers yet)
```

- [ ] **Step 5: Commit**
```bash
git add services/location-service/src/main/java/org/openboxes/location/security/
git commit -m "phase 3 task 5: security (JwtCookieAuthFilter + JwtService subset + SecurityConfig) — copy from identity-service with package rename; omit JwtService.issue() since location-service issues no tokens; allowlist actuator + openapi"
```

---

### Task 6: Caching + filter service + DTOs

**Files:**
- Create: `services/location-service/src/main/java/org/openboxes/location/service/{LocationTypeCache, LocationFilterService}.java`
- Create: `services/location-service/src/main/java/org/openboxes/location/dto/{Location, LocationGroup, LocationType}Dto.java`

- [ ] **Step 1: Create `service/LocationTypeCache.java`** (RoleTypeCache pattern per spec §6; refresh-on-miss `Map<String, LocationType>`):
```java
package org.openboxes.location.service;

import jakarta.annotation.PostConstruct;
import org.openboxes.location.entity.LocationType;
import org.openboxes.location.repository.LocationTypeRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantReadWriteLock;

@Service
public class LocationTypeCache {
    private final LocationTypeRepository repo;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();
    private volatile Map<String, LocationType> byId = new HashMap<>();

    public LocationTypeCache(LocationTypeRepository r) { this.repo = r; }

    @PostConstruct
    public void refresh() {
        lock.writeLock().lock();
        try {
            Map<String, LocationType> fresh = new HashMap<>();
            for (LocationType lt : repo.findAll()) fresh.put(lt.getId(), lt);
            this.byId = fresh;
        } finally { lock.writeLock().unlock(); }
    }

    public Optional<LocationType> getById(String id) {
        Optional<LocationType> hit = Optional.ofNullable(byId.get(id));
        if (hit.isPresent()) return hit;
        refresh();
        return Optional.ofNullable(byId.get(id));
    }

    public List<LocationType> getAll() { return List.copyOf(byId.values()); }
}
```

- [ ] **Step 2: Create `service/LocationFilterService.java`** (internal-type filter set; centralizes the filter logic for both read() + list()):
```java
package org.openboxes.location.service;

import org.openboxes.location.enums.LocationTypeCode;
import org.springframework.stereotype.Service;
import java.util.Set;

@Service
public class LocationFilterService {
    public Set<LocationTypeCode> internalTypeCodes() {
        return LocationTypeCode.listInternalTypeCodes();  // [BIN_LOCATION, INTERNAL]
    }
}
```

- [ ] **Step 3: Create `dto/LocationTypeDto.java`**:
```java
package org.openboxes.location.dto;

import org.openboxes.location.entity.LocationType;
import java.util.Set;

public record LocationTypeDto(
    String id,
    String name,
    String description,
    String locationTypeCode,
    Integer sortOrder,
    Set<String> supportedActivities
) {
    public static LocationTypeDto from(LocationType lt) {
        return new LocationTypeDto(
            lt.getId(),
            lt.getName(),
            lt.getDescription(),
            lt.getLocationTypeCode() == null ? null : lt.getLocationTypeCode().name(),
            lt.getSortOrder(),
            lt.getSupportedActivities()
        );
    }
}
```

- [ ] **Step 4: Create `dto/LocationGroupDto.java`**:
```java
package org.openboxes.location.dto;

import org.openboxes.location.entity.LocationGroup;

public record LocationGroupDto(
    String id,
    String name,
    String addressId
) {
    public static LocationGroupDto from(LocationGroup lg) {
        return new LocationGroupDto(lg.getId(), lg.getName(), lg.getAddressId());
    }
}
```

- [ ] **Step 5: Create `dto/LocationDto.java`** (flat FK-only shape per FD#3 pick (c)):
```java
package org.openboxes.location.dto;

import org.openboxes.location.entity.Location;
import java.util.Set;

public record LocationDto(
    String id,
    String name,
    String description,
    String locationNumber,
    Boolean active,
    Integer sortOrder,
    String locationTypeId,
    String locationTypeCode,
    String locationTypeName,
    String locationGroupId,
    String parentLocationId,
    String zoneId,
    String organizationId,
    String managerId,
    String addressId,
    Set<String> supportedActivities
) {
    public static LocationDto from(Location l) {
        return new LocationDto(
            l.getId(),
            l.getName(),
            l.getDescription(),
            l.getLocationNumber(),
            l.getActive(),
            l.getSortOrder(),
            l.getLocationType() == null ? null : l.getLocationType().getId(),
            l.getLocationType() == null || l.getLocationType().getLocationTypeCode() == null ? null : l.getLocationType().getLocationTypeCode().name(),
            l.getLocationType() == null ? null : l.getLocationType().getName(),
            l.getLocationGroupId(),
            l.getParentLocationId(),
            l.getZoneId(),
            l.getOrganizationId(),
            l.getManagerId(),
            l.getAddressId(),
            l.getSupportedActivities()
        );
    }
}
```

- [ ] **Step 6: Commit**
```bash
git add services/location-service/src/main/java/org/openboxes/location/service/ services/location-service/src/main/java/org/openboxes/location/dto/
git commit -m "phase 3 task 6: LocationTypeCache + LocationFilterService + 3 flat DTOs (FD#3 pick (c)) — LocationTypeCache refresh-on-miss per spec §6; LocationDto/LocationGroupDto/LocationTypeDto with FK IDs only (no foreign-entity inflation)"
```

---

### Task 7: REST controllers (7 endpoints)

**Files:**
- Create: `services/location-service/src/main/java/org/openboxes/location/controller/{Location, LocationGroup, LocationType}Controller.java`

- [ ] **Step 1: Create `controller/LocationController.java`** (5 endpoints: `/{id}`, list, supportedActivities):
```java
package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationDto;
import org.openboxes.location.entity.Location;
import org.openboxes.location.enums.SupportedActivitiesEnum;
import org.openboxes.location.repository.LocationRepository;
import org.openboxes.location.service.LocationFilterService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location")
public class LocationController {
    private final LocationRepository repo;
    private final LocationFilterService filter;

    public LocationController(LocationRepository r, LocationFilterService f) {
        this.repo = r;
        this.filter = f;
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationDto> read(@PathVariable String id,
                                             @RequestParam(defaultValue = "false") boolean includeInternal) {
        var found = includeInternal
            ? repo.findById(id)
            : repo.findByIdExcludingInternal(id, filter.internalTypeCodes());
        return found.map(l -> ResponseEntity.ok(LocationDto.from(l)))
                    .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<LocationDto> list(@RequestParam(required = false) Boolean active,
                                  @RequestParam(defaultValue = "false") boolean includeInternal) {
        List<Location> rows = includeInternal
            ? (active == null ? repo.findAll() : repo.findAll().stream().filter(l -> active.equals(l.getActive())).toList())
            : repo.findAllExcludingInternal(filter.internalTypeCodes(), active);
        return rows.stream().map(LocationDto::from).toList();
    }

    @GetMapping("/supportedActivities")
    public List<String> supportedActivities() {
        return SupportedActivitiesEnum.list();
    }
}
```

- [ ] **Step 2: Create `controller/LocationGroupController.java`** (2 endpoints):
```java
package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationGroupDto;
import org.openboxes.location.repository.LocationGroupRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location/group")
public class LocationGroupController {
    private final LocationGroupRepository repo;

    public LocationGroupController(LocationGroupRepository r) { this.repo = r; }

    @GetMapping("/{id}")
    public ResponseEntity<LocationGroupDto> read(@PathVariable String id) {
        return repo.findById(id)
            .map(lg -> ResponseEntity.ok(LocationGroupDto.from(lg)))
            .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public List<LocationGroupDto> list() {
        return repo.findAll().stream().map(LocationGroupDto::from).toList();
    }
}
```

- [ ] **Step 3: Create `controller/LocationTypeController.java`** (2 endpoints; served from cache):
```java
package org.openboxes.location.controller;

import org.openboxes.location.dto.LocationTypeDto;
import org.openboxes.location.service.LocationTypeCache;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/location/type")
public class LocationTypeController {
    private final LocationTypeCache cache;

    public LocationTypeController(LocationTypeCache c) { this.cache = c; }

    @GetMapping
    public List<LocationTypeDto> list() {
        return cache.getAll().stream().map(LocationTypeDto::from).toList();
    }

    @GetMapping("/{id}")
    public ResponseEntity<LocationTypeDto> read(@PathVariable String id) {
        return cache.getById(id)
            .map(lt -> ResponseEntity.ok(LocationTypeDto.from(lt)))
            .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 4: Rebuild + smoke-test all 7 endpoints**
```bash
cd services && ./gradlew :location-service:bootJar
cd ../docker && sudo docker-compose up -d --build location-service
# Get a real obx_token via login first (see /api/identity/login in Phase 2)
TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"password"}' \
    -c - http://localhost/api/identity/login | grep obx_token | awk '{print $7}')
# Test all 7 endpoints:
curl -sf -b "obx_token=$TOKEN" http://localhost/api/location/type | jq length
curl -sf -b "obx_token=$TOKEN" http://localhost/api/location/group | jq length
curl -sf -b "obx_token=$TOKEN" http://localhost/api/location/supportedActivities | jq length  # Expected: 30
# Pick a real location ID from DB:
LOC=$(sudo docker exec openboxes-db mariadb -u root -proot openboxes -se "SELECT id FROM location LIMIT 1")
curl -sf -b "obx_token=$TOKEN" "http://localhost/api/location/$LOC" | jq .
# Test 401 path:
curl -sI http://localhost/api/location/$LOC  # Expected: HTTP/1.1 401
```

- [ ] **Step 5: Commit**
```bash
git add services/location-service/src/main/java/org/openboxes/location/controller/
git commit -m "phase 3 task 7: REST controllers (3 controllers; 7 GET endpoints) — LocationController (read+list+supportedActivities), LocationGroupController (read+list), LocationTypeController (read+list from cache); 401 on missing JWT, 404 on missing/filtered id, ?includeInternal=true opt-in"
```

---

### Task 8: nginx routing + depends_on

**Files:**
- Modify: `docker/nginx/conf.d/app.conf`, `docker/docker-compose.yml`

- [ ] **Step 1: Add nginx location block** (`docker/nginx/conf.d/app.conf`) BEFORE the `/api/` catch-all at line 25. The trailing slash on `/api/location/` is MANDATORY to avoid collision with `/api/locations/x` (see verified-assumption #21 — do NOT remove the trailing slash to match the `/api/identity` + `/api/documents` no-trailing-slash style):
```nginx
    location /api/location/ {
        proxy_pass http://location-service:8083/;
        proxy_set_header Host $host;
        proxy_set_header X-Forwarded-For $remote_addr;
        proxy_set_header Cookie $http_cookie;
    }
```
Insert after the existing `/api/documents` block (around line 23) and BEFORE the `/api/` catch-all (around line 25).

- [ ] **Step 2: Add location-service to nginx depends_on** (`docker/docker-compose.yml` line 35-41 — the existing nginx depends_on block):
```diff
     nginx:
       extends:
         file: docker-compose-base.yml
         service: nginx
       depends_on:
         app:
           condition: service_healthy
         document-service:
           condition: service_healthy
         identity-service:
           condition: service_healthy
+        location-service:
+          condition: service_healthy
```

- [ ] **Step 3: Rebuild full stack + verify routing**
```bash
cd docker && sudo docker-compose down && sudo docker-compose up -d --build
# Wait for all 6 to be healthy:
sudo docker ps --filter name=openboxes
# Verify routing through nginx:
TOKEN=$(curl -sf -X POST -H 'Content-Type: application/json' \
    -d '{"username":"admin","password":"password"}' \
    -c - http://localhost/api/identity/login | grep obx_token | awk '{print $7}')
LOC=$(sudo docker exec openboxes-db mariadb -u root -proot openboxes -se "SELECT id FROM location LIMIT 1")
curl -sf -b "obx_token=$TOKEN" http://localhost/api/location/$LOC | jq .
# Verify NO accidental routing of /api/locations/x to location-service (should hit Grails):
curl -sI -b "obx_token=$TOKEN" http://localhost/api/locations/$LOC  # Expected: 200 (Grails response, includes inflated relations)
```

- [ ] **Step 4: Commit**
```bash
git add docker/nginx/conf.d/app.conf docker/docker-compose.yml
git commit -m "phase 3 task 8: nginx /api/location/ routing + depends_on — singular trailing-slash prefix routes to location-service:8083; existing /api/locations/ (plural) stays Grails (verified no collision); nginx waits for location-service health"
```

---

### Task 9: JUnit + TestContainers integration tests

**Files:**
- Create: `services/location-service/src/test/java/org/openboxes/location/LocationServiceIntegrationTest.java`
- Create: `services/location-service/src/test/resources/seed.sql`

- [ ] **Step 1: Create seed fixture** (`services/location-service/src/test/resources/seed.sql`) — 3-4 real locations + 2 bins + 1 zone + 2 location-types + 1 group + activity grants. Use stable IDs.

```sql
-- Location types
INSERT INTO location_type (id, name, location_type_code, sort_order) VALUES
    ('lt-depot-001', 'Depot', 'DEPOT', 10),
    ('lt-bin-001', 'Bin', 'BIN_LOCATION', 20),
    ('lt-zone-001', 'Zone', 'ZONE', 16);

-- Location group
INSERT INTO location_group (id, name) VALUES ('lg-001', 'Test Group');

-- Real locations (DEPOT)
INSERT INTO location (id, name, location_type_id, location_group_id, active) VALUES
    ('loc-depot-a', 'Depot A', 'lt-depot-001', 'lg-001', 1),
    ('loc-depot-b', 'Depot B', 'lt-depot-001', NULL, 1),
    ('loc-depot-inactive', 'Depot Inactive', 'lt-depot-001', NULL, 0);

-- Bin locations (should be filtered out by default)
INSERT INTO location (id, name, location_type_id, parent_location_id, active) VALUES
    ('loc-bin-1', 'Bin 1', 'lt-bin-001', 'loc-depot-a', 1),
    ('loc-bin-2', 'Bin 2', 'lt-bin-001', 'loc-depot-a', 1);

-- Zone location (should NOT be filtered — Grails parity, FD#2 pick a)
INSERT INTO location (id, name, location_type_id, parent_location_id, active) VALUES
    ('loc-zone-1', 'Zone 1', 'lt-zone-001', 'loc-depot-a', 1);

-- Activity grants
INSERT INTO location_supported_activities (location_id, supported_activities_string) VALUES
    ('loc-depot-a', 'RECEIVE_STOCK'),
    ('loc-depot-a', 'SEND_STOCK');
INSERT INTO location_type_supported_activities (location_type_id, supported_activities_string) VALUES
    ('lt-depot-001', 'MANAGE_INVENTORY');
```

- [ ] **Step 2: Create `LocationServiceIntegrationTest.java`** with the 15 tests per spec §10:
```java
package org.openboxes.location;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
@Sql({"/seed.sql"})
class LocationServiceIntegrationTest {

    @Container
    static MariaDBContainer<?> mariadb = new MariaDBContainer<>("mariadb:10");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("spring.datasource.url", mariadb::getJdbcUrl);
        r.add("spring.datasource.username", mariadb::getUsername);
        r.add("spring.datasource.password", mariadb::getPassword);
        r.add("openboxes.jwt.secret", () -> "test-secret-32-chars-minimum-for-hs256-key");
        r.add("spring.jpa.hibernate.ddl-auto", () -> "create");  // TestContainers gives empty DB; let JPA create schema
    }

    @Autowired MockMvc mvc;

    private static final String TEST_SECRET = "test-secret-32-chars-minimum-for-hs256-key";

    // Helper: generate valid JWT cookie value (location-service's JwtService omits issue(); use jjwt directly here)
    private String validToken() {
        var key = io.jsonwebtoken.security.Keys.hmacShaKeyFor(TEST_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return io.jsonwebtoken.Jwts.builder()
            .subject("test-user-id")
            .claim("loc", "loc-depot-a")
            .claim("roles", java.util.List.of("ROLE_BROWSER"))
            .issuedAt(new java.util.Date())
            .expiration(new java.util.Date(System.currentTimeMillis() + 3600_000L))
            .signWith(key)
            .compact();
    }

    // Helper: attach cookie to MockMvc request
    private jakarta.servlet.http.Cookie authCookie() {
        return new jakarta.servlet.http.Cookie("obx_token", validToken());
    }

    // Example test body (others follow the same pattern):
    @Test void readById_returns200() throws Exception {
        mvc.perform(get("/api/location/loc-depot-a").cookie(authCookie()))
           .andExpect(status().isOk())
           .andExpect(jsonPath("$.id").value("loc-depot-a"))
           .andExpect(jsonPath("$.locationTypeCode").value("DEPOT"));
    }

    // (readById_returns200 shown in full above; remaining 14 tests follow the same MockMvc + authCookie() pattern)
    @Test void readById_returns404ForMissing() throws Exception { /* GET /api/location/nonexistent → 404 */ }
    @Test void readById_returns404ForInternalDefault() throws Exception { /* GET /api/location/loc-bin-1 → 404 (filtered) */ }
    @Test void readById_returns200ForInternalWithOptIn() throws Exception { /* GET /api/location/loc-bin-1?includeInternal=true → 200 */ }
    @Test void list_filtersByType() throws Exception { /* GET /api/location?type=DEPOT → 2-3 depot rows */ }
    @Test void list_filtersByActive() throws Exception { /* GET /api/location?active=false → 1 inactive */ }
    @Test void list_excludesInternalByDefault() throws Exception { /* GET /api/location → no bin/INTERNAL; ZONE included */ }
    @Test void groupReadById_returns200() throws Exception {}
    @Test void groupList_returnsAll() throws Exception {}
    @Test void typeList_servedFromCache() throws Exception { /* GET /api/location/type → 3 types */ }
    @Test void typeReadById_returns404ForMissing() throws Exception {}
    @Test void supportedActivities_returnsAllEnumValues() throws Exception { /* GET /api/location/supportedActivities → 30 */ }
    @Test void cacheLoadsOnFirstCall_thenHits() throws Exception { /* verify LocationTypeCache.byId populated after first refresh */ }
    @Test void jwtMissing_returns401() throws Exception { /* GET /api/location/loc-depot-a without cookie → 401 */ }
    @Test void jwtInvalid_returns401() throws Exception { /* GET /api/location/loc-depot-a with garbage cookie → 401 */ }
}
```

(Implementer fills in each test body using MockMvc + token helpers; mirror identity-service test patterns.)

- [ ] **Step 3: Run the suite**
```bash
cd services && sudo -E ./gradlew :location-service:test
# Expected: 15 tests pass; BUILD SUCCESSFUL
```

- [ ] **Step 4: Commit**
```bash
git add services/location-service/src/test/
git commit -m "phase 3 task 9: JUnit + TestContainers integration tests (15 tests per spec §10) — covers all 7 endpoints, auth paths, internal-location filter (BIN/INTERNAL excluded by default; ZONE included per Grails parity), includeInternal opt-in; seed.sql fixture with 3-4 real locations + 2 bins + 1 zone"
```

---

### Task 10: Playwright E2E specs

**Files:**
- Create: `e2e/tests/location-service.spec.ts`

- [ ] **Step 1: Create E2E spec** with the 4-5 tests per spec §10:
```typescript
import { test, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost';
const USER = process.env.E2E_USER ?? 'admin';
const PASS = process.env.E2E_PASSWORD ?? 'password';

async function login(request: any) {
    const res = await request.post(`${BASE}/api/identity/login`, {
        data: { username: USER, password: PASS },
    });
    expect(res.ok()).toBeTruthy();
    return res.headers()['set-cookie'];
}

test.describe('location-service via nginx', () => {
    test('GET /api/location/{id} returns 200 with valid obx_token', async ({ request }) => {
        const cookie = await login(request);
        // Fetch a real location ID (e.g., via /api/identity/me or /api/locations)
        const meRes = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        const me = await meRes.json();
        const locId = me.locationId ?? process.env.E2E_LOCATION_ID;
        const res = await request.get(`${BASE}/api/location/${locId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.id).toBe(locId);
        expect(body).toHaveProperty('locationTypeCode');
    });

    test('GET /api/location/type returns reference data', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/location/type`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const types = await res.json();
        expect(Array.isArray(types)).toBeTruthy();
        expect(types.length).toBeGreaterThan(0);
    });

    test('regression: /api/locations/{id} (plural) still routes to Grails', async ({ request }) => {
        const cookie = await login(request);
        const meRes = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        const me = await meRes.json();
        const locId = me.locationId ?? process.env.E2E_LOCATION_ID;
        const res = await request.get(`${BASE}/api/locations/${locId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        // Grails returns {data: {...}} wrapper; location-service returns flat
        expect(body.data).toBeTruthy();
    });

    test('regression: /api/internalLocations/* still works via Grails', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/internalLocations`, { headers: { Cookie: cookie } });
        // Either 200 or method-specific status; not 502 (would mean nginx misroute)
        expect(res.status()).not.toBe(502);
    });

    test('regression: existing Phase 1+2 baseline (login + /api/identity/me) unchanged', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });
});
```

- [ ] **Step 2: Run Playwright suite**
```bash
cd e2e && npm test
# Expected: 5 new tests pass + all baseline (existing 24 tests) still pass
```

- [ ] **Step 3: Commit**
```bash
git add e2e/tests/location-service.spec.ts
git commit -m "phase 3 task 10: Playwright E2E specs (5 tests) — location-service via nginx + regression for /api/locations/ (plural→Grails) + /api/internalLocations/ + Phase 1+2 baseline"
```

---

### Task 11: CI workflow probe + log dump

**Files:**
- Modify: `.github/workflows/e2e-tests.yml`

- [ ] **Step 1: Add location-service to bootJar build step** (line 36):
```diff
-        run: ./gradlew :identity-service:bootJar :document-service:bootJar
+        run: ./gradlew :identity-service:bootJar :document-service:bootJar :location-service:bootJar
```

- [ ] **Step 2: Add location-service healthcheck probe to the boot-wait loop** (line 44; insert after identity-service probe):
```diff
           for i in {1..60}; do
             curl -sf http://localhost/openboxes/health \
               && docker exec openboxes-document-service curl -sf localhost:8081/actuator/health \
               && docker exec openboxes-identity-service curl -sf localhost:8082/actuator/health \
+              && docker exec openboxes-location-service curl -sf localhost:8083/actuator/health \
               && [ "$(curl -s -o /dev/null -w '%{http_code}' http://localhost/api/documents)" != "502" ] \
               && break
             sleep 5
           done
```

- [ ] **Step 3: Add location-service log dump to diagnostic step** (line 78):
```diff
           echo "---identity-service---" && docker logs openboxes-identity-service 2>&1 | tail -100 || true
+          echo "---location-service---" && docker logs openboxes-location-service 2>&1 | tail -100 || true
           echo "---nginx---" && docker logs openboxes-nginx 2>&1 | tail -50 || true
```

- [ ] **Step 4: Commit**
```bash
git add .github/workflows/e2e-tests.yml
git commit -m "ci: e2e-tests workflow builds location-service jar + probes its health + dumps logs on failure — mirrors identity-service Phase 2 BP pattern"
```

---

### Task 12: Done-gate verification + 15-minute soak + tag `phase-3-location`

**Files:** (no code changes; verification + tag)

Per spec §11 done-gate checklist:

- [ ] **Step 1: Clean rebuild from scratch**
```bash
cd services && ./gradlew :location-service:bootJar :identity-service:bootJar :document-service:bootJar
cd ../docker && sudo docker-compose down -v && sudo docker-compose up -d --build
```

- [ ] **Step 2: Wait for 6 containers Up healthy**
```bash
for i in {1..60}; do
    HEALTHY=$(sudo docker ps --filter name=openboxes --filter health=healthy --format '{{.Names}}' | wc -l)
    if [ "$HEALTHY" -eq 5 ]; then break; fi  # nginx has no healthcheck; 5 healthy + 1 nginx running
    sleep 5
done
sudo docker ps --filter name=openboxes --format "table {{.Names}}\t{{.Status}}"
# Expected: 6 containers (db, app, document-service, identity-service, location-service, nginx) all Up
```

- [ ] **Step 3: Healthcheck**
```bash
sudo docker exec openboxes-location-service curl -sf localhost:8083/actuator/health | jq .
# Expected: {"status":"UP"}
```

- [ ] **Step 4: Smoke-test all 7 GET endpoints** (with valid obx_token)

- [ ] **Step 5: Verify ddl-auto:validate passes**
```bash
sudo docker logs openboxes-location-service 2>&1 | grep -iE "validation|schema|missing column|missing table"
# Expected: no error lines; possibly INFO-level Hibernate startup
```

- [ ] **Step 6: Grep gates** (spec §11):
  - **No Grails write paths changed:** `git diff phase-2-identity..HEAD --name-only -- 'grails-app/**'` should be empty (or only test-related touches).
  - **No location-service write endpoints:** `grep -rn '@PostMapping\|@PutMapping\|@DeleteMapping' services/location-service/src/main/java/` should be empty.
  - **identity-service Location.java unchanged:** `git diff phase-2-identity..HEAD -- services/identity-service/src/main/java/org/openboxes/identity/entity/Location.java` should be empty.

- [ ] **Step 7: Run JUnit suite**
```bash
cd services && sudo -E ./gradlew :location-service:test :identity-service:test :document-service:test
# Expected: all 3 test suites pass; BUILD SUCCESSFUL
```

- [ ] **Step 8: Run Playwright suite**
```bash
cd e2e && npm test
# Expected: 5 new + 24 baseline = ~29 tests pass
```

- [ ] **Step 9: 15-minute soak** (memory + log monitoring)
```bash
# Capture memory snapshots every 5 min over 15 min:
for i in 1 2 3 4; do
    sudo docker stats --no-stream --format "table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}" --filter name=openboxes | tee /tmp/soak-$i.txt
    sleep 300
done
# Expected: memory steady (no upward trend)
# Verify no exceptions:
sudo docker logs openboxes-location-service 2>&1 | grep -iE "exception|error" | grep -v "INFO\|DEBUG" | head -20
# Expected: empty
```

- [ ] **Step 10: Push to origin** (per per-push gate — STOP and ask user before this step)
```bash
# After explicit user confirmation:
git push origin main
# Wait for CI to go green (typically 8-12 minutes):
gh run watch
```

- [ ] **Step 11: Tag `phase-3-location` at done-gate-green HEAD** (after CI green)
```bash
# After CI confirms green:
git tag phase-3-location $(git rev-parse HEAD)
# Per per-push gate — STOP and ask user before this step:
git push origin phase-3-location
```

- [ ] **Step 12: Commit** (none for this task — verification + tagging only)

---

### Task 13: Phase 3 retrospective

**Files:**
- Create: `docs/retrospectives/YYYY-MM-DD-phase-3-location-retrospective.md` (replace `YYYY-MM-DD` with done-gate date)

Mirror Phase 1 + Phase 2 retro structure:

- [ ] **Step 1: Write YAML frontmatter** (date, phase, tag = phase-3-location, commit_range = `2e70b7c91..<done-gate-HEAD>`, plan, spec_section)

- [ ] **Step 2: TL;DR paragraph** — 1 paragraph summary of slice outcome

- [ ] **Step 3: What worked** — Phase 3-specific:
  - Read-only pivot decision (avoided F5 event-cascade risk)
  - bin/zone vs internal-location filter distinction (FD#2 pick a — Grails parity)
  - flat FK-only DTOs (FD#3 pick c — minimal foreign coupling)
  - JwtCookieAuthFilter + JwtService subset copy (no token-issuance code needed)
  - tableExists precondition (R2 lesson — works uniformly for entity + join tables)

- [ ] **Step 4: Codebase / env gotchas** — sub-grouped (Build & deploy, Code-level, Container, Runtime):
  - Carry forward from Phase 1+2 retros (Docker compose v1 syntax, sudo for TestContainers, etc.)
  - NEW this slice: nginx `/api/location/` trailing-slash mandatory for collision avoidance; FD#3 forced decision pattern (DTO shape ambiguity in spec)

- [ ] **Step 5: Process / meta-lessons** — capture:
  - F1-F6 verification findings effectiveness
  - CDR R1+R2 partial-fix gap pattern (UDD missed §0 + §2 summary updates)
  - Plan-level FD#3 surfaced during TWP verification (DTO shape forced decision spec didn't catch)

- [ ] **Step 6: Forward to Phase 4** — Organization slice (whatever consumes location data)

- [ ] **Step 7: Phase X carry-forward** — list deferred items:
  - Bin/zone admin UI rewrite (spec §14 item 4)
  - identity-service Location.java removal (spec §14 item 6)
  - JwtCookieAuthFilter DRY refactor (3-service duplication now)
  - Internal-type-code list sync (LocationTypeCode mirror)
  - ActivityCode sync (SupportedActivitiesEnum mirror, 30 values)
  - Parent design `LocationStatus` enumeration correction (spec §15)
  - Phase X items 1-6 (Phase 6+ inventory-service for write decoupling)

- [ ] **Step 8: Artifacts** — links to spec, plan, audit (none), tag, commit range

- [ ] **Step 9: Commit + push** (per per-push gate — STOP and ask user)
```bash
git add docs/retrospectives/YYYY-MM-DD-phase-3-location-retrospective.md
git commit -m "phase 3: location-service retrospective"
# After user confirmation:
git push origin main
```

---

## Tasks NOT in this plan

Inherited from spec §1 "Not in scope (carve-outs)" + §14 Phase X (Deferred). A new spec → new plan cycle is required to add any of these:

**Carve-outs (spec §1):**
- `LocationRole` (stays with identity-service per Phase 2; parent design §4.3)
- `LocationDimension` (reporting-service concern; parent design §4.3)
- Bin/zone configuration UI (LocationsConfigurationController + 6 React modals — AddBinModal, AddZoneModal, AddLocationGroupModal, ImportBinModal, LocationDetails, ZoneAndBinLocations) — admin-rare, deferred to Phase X
- `/api/internalLocations/*` (bin/zone REST surface) — stays on Grails
- All Location/LocationGroup/LocationType WRITE paths — stay on Grails (see spec §13)

**Phase X items (spec §14) — deferred until post-Phase 6:**
1. **Inventory snapshot saga (Phase 6 prerequisite).** location-service emits `LocationChangedEvent` to outbox; inventory-service consumes and runs `productAvailabilityService.updateProductAvailability(...)` + `inventorySnapshotService.updateInventorySnapshots(...)`. Replaces in-JVM events. Requires saga infrastructure (parent design §4.5; Phase 7+).
2. **Shipping-workflow internal-location creation (Phase 8 prerequisite).** `LocationService.findOrCreateInternalLocation` migrates to shipping-service + sync HTTP call to location-service POST endpoint.
3. **CSV bulk import migration.** `LocationImportDataService` moves to location-service `POST /api/location/importCsv` (or stays Grails until Phase 12).
4. **Bin/zone configuration UI rewrite.** Rewrite as React + location-service POST endpoints OR delete in Phase 12 cleanup.
5. **`/api/locations/*` (plural) → `/api/location/*` (singular) consolidation.** Migrate 16 React files + ~200 Grails `Location.get(id)` callsites; delete Grails LocationApiController/LocationController/LocationGroupApiController/LocationService/Location.groovy/LocationGroup.groovy/LocationType.groovy + views + URL mappings.
6. **identity-service Location.java entity removal.** Once location-service has POST endpoints and identity-service can HTTP-call location-service for chooseLocation's `location.active` check.

---

## Known issues inherited from spec

From spec §15. These exist in the implementation by design — accepted by the user during brainstorming + CDR:

- **No write endpoints in Phase 3.** Phase 4+ slices that need to MUTATE Location data continue calling Grails (or stay on direct JDBC). The deferral is intentional (spec §13 events rationale).
- **identity-service still maps Location as a JPA entity** against the shared DB. After Phase 3, identity-service's Location.java entity coexists with location-service's Location.java entity, both mapping the same table. Schema changes require updating BOTH entities in lock-step. Phase 2 → Phase 3 accepts this debt; Phase X resolves.
- **JwtCookieAuthFilter is duplicated.** Now in 3 services (document, identity, location). DRY violation deferred to Phase X.
- **Internal-type-code list duplicated.** location-service's enum of bin/zone codes mirrors Grails `LocationTypeCode.listInternalTypeCodes()`. If Grails adds a new internal type, location-service's enum must be updated; mitigated by retrospective documentation + future test that exercises both lists.
- **ActivityCode enum duplicated.** location-service's `SupportedActivitiesEnum` mirrors Grails `ActivityCode` enum (30 values at `src/main/groovy/org/pih/warehouse/core/ActivityCode.groovy`). If Grails adds a new activity code, location-service's enum must be updated in lock-step; mitigated by retrospective documentation + future test that exercises both lists. Same pattern as the LocationTypeCode debt above.
- **Parent design enumerated LocationStatus** (entity + table) that doesn't exist. Spec deviation noted in retrospective for parent design correction.
- **No saga infrastructure.** Parent design's saga support arrives in Phase 7+. Phase 3 (read-only) doesn't need it; Phase X (write decoupling) does.
