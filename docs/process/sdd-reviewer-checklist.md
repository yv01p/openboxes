# SDD reviewer checklist (project-local additional checks)

Items to include in every SDD spec/code-quality reviewer subagent prompt, beyond the default code-review template at `~/.claude/plugins/cache/.../requesting-code-review/code-reviewer.md`.

## JPA inheritance + nullability (Phase 4 RC-1)

When reviewing entity changes touching JPA `@Inheritance(SINGLE_TABLE)`:

- **Cross-check subclass field nullability against the actual base-table schema in the production database** — do NOT just rely on Hibernate `ddl-auto: validate` (which checks column existence + type only, NOT nullability).
- Subclass-only fields under SINGLE_TABLE inheritance MUST be `nullable = true` (or `@Column` with no `nullable` hint — JPA default is true) when the base table allows the column to be NULL (typical when bare-base-class rows coexist with subclass rows).
- Business-rule nullability ("must be non-null for create") belongs in DTO validation (`@NotBlank`, `@NotNull` on command/request types), NOT on the entity field.

**Rationale**: Phase 4 T9 surfaced this trap. Spring Boot Organization entity declared `@Column(nullable = false)` on `code`/`name`/`active` matching business rules; production `party` table has these as NULLABLE so bare Party rows can coexist. `ddl-auto: validate` did NOT catch the divergence. Surfaced only when T9 tests used `ddl-auto: create` which generates DDL FROM annotations → bare-Party INSERT failed with NOT NULL violation. Fix landed at commit `8cba628f1` (Phase 4 T9 fixup).

**Verification**: when reviewing such changes, ask the implementer to either (a) run `SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '<table>' AND column_name IN (<subclass-fields>);` against production OR (b) cite the migration that created the table to confirm nullability.

## Schema CHAR/TINYINT divergence (Phase 5 RC-1)

When reviewing entity changes touching JPA `@Id` columns (which Hibernate defaults to VARCHAR(255) for String types) or `Boolean` fields (which Hibernate defaults to BIT(1)):

- **Cross-check actual production-schema column types** before relying on Hibernate defaults. Production database may use `CHAR(38)` for entity IDs (requires `@Column(length=38)` or `columnDefinition="CHAR(38)"`) or `TINYINT(3)` for booleans (requires `@Column(columnDefinition="TINYINT")` since Hibernate maps Boolean to `bit(1)` by default).
- `ddl-auto: validate` catches type mismatches but NOT length mismatches on VARCHAR vs CHAR; tests using `ddl-auto: create` against TestContainers can mask the divergence.

**Rationale**: Phase 5 T4 surfaced this 5 of 5 times (all 5 service phases). Codified after Phase 5 to prevent recurrence in Phase 5.5+ entity additions.

**Verification**: when reviewing entity additions, ask the implementer to run `SHOW COLUMNS FROM <table> LIKE '<column>'` against production for every `@Id` String field + every `Boolean` field.

## @ElementCollection inner-column names (Phase 5 RC-2)

When reviewing entity additions with `@ElementCollection` (e.g., `Set<String> supportedActivities`), the inner table's column names rarely follow the JPA convention `<owning_entity>_<field>`. Production schemas often use compact names like `product_activity_code` instead of `activity_code` or `product_type_supported_activity_<field>`.

- **Run `DESCRIBE <collection_table>` against production BEFORE plan-write** to enumerate exact inner column names; do NOT trust the convention.
- `ddl-auto: validate` catches these at first smoke run (recoverable trap, unlike nullability divergence which `validate` silently passes).

**Rationale**: Phase 5 T4 hit this 5-of-5 for ProductType's `supportedActivities` / `requiredFields` / `displayedFields` / `reportingActivities` element collections.

**Verification**: for each `@ElementCollection` in the entity change, the plan-writer must cite the `DESCRIBE` output (or migration source) confirming the actual inner column names.

## Reproduce "production has X limitation" claims (Phase 5 RC-7)

When a fix's commit body OR test-comment block asserts "this is a production bug" or "production has limitation X" — the code-quality reviewer MUST reproduce X against the running production stack BEFORE accepting the framing. Code-reading alone validates the patch shape but NOT the root-cause attribution.

**Procedure**:
1. Boot the prod-like stack (e.g., `docker compose up -d` + apply baseline).
2. Reproduce the asserted limitation via `curl` / `sudo docker exec` (whatever surface the framing references).
3. If the limitation does NOT reproduce, the framing is wrong; the fix may still be correct but the rationale/comments need retraction.

**Rationale**: Phase 5 T10 surfaced this — implementer's "production caching limitation" framing was disproved by 5 sequential prod-stack curls (all returned 200 with populated `supportedActivities`); the actual root cause was test isolation, not a production limitation. Retraction landed at commit `9581f7bbc`.

## Duplication extraction triggers — the "5th copy" rule (Phase 5 RC-24)

When a duplication propagates cleanly across N services with zero per-service variance, extract it. The empirical inflection from Phase 1..5: 5 services × clean Direct-apply of the same code = strongly motivated extraction.

**Decision criteria** (all must hold for clean extraction):
- The duplication has propagated to at least N=4 sites (early extraction has insufficient evidence of "no per-service variance").
- Each propagation was a clean copy with at most package-rename diffs (Direct apply per the SDD calibration rule).
- No site has per-service customization that the extraction would need to parameterize.

**Rationale**: Phase 4 retro flagged JWT triple-copy as "STRONGLY MOTIVATED" at the 4th copy; Phase 5 confirmed at the 5th (clean 3-line package rename across 3 files). Phase 5.1 extracted to `services/jwt-auth-common`. Don't wait for the 6th copy.

**Application**: future N-service duplication patterns (e.g., shared OpenAPI config, shared TestContainers base class, shared @ControllerAdvice exception handlers) should trigger this rule at copy 4 — promote to next-phase B-disposition.

## Gradle plugin version compatibility (Phase 5.1 RC-31)

When reviewing plans that bump a Gradle plugin's version, verify the new plugin major's minimum-Gradle-version requirement against the project's actual Gradle version (`gradle/wrapper/gradle-wrapper.properties:distributionUrl`). Plugin majors routinely raise their Gradle floor; a plan that names "latest plugin" without this check can land an apply-time failure that no test catches before T-commit.

**Verification**: read the plugin's CHANGELOG (or compatibility section in its README) for the picked major. Cite the result in the plan body (e.g., "node-gradle plugin 1.5.3 supports Gradle 4+, satisfies the project's `gradle-wrapper.properties` at 4.10.3").

**Rationale**: Phase 5.1 T9 plan prescribed `com.github.node-gradle.node:7.0.1`; plugin v7 requires Gradle ≥5 via the `DirectoryProperty` API. The project is locked to Gradle 4.10.3 by Grails 3.x compat. BUILD FAILED at apply-plugin time; T9 pivoted to plugin v1.5.3.

## Sentinel-rule pre-verification (Phase 5.1 RC-36)

When a plan prescribes a sentinel test (e.g., "introduce a one-line lint violation to prove the pre-commit hook blocks it"), verify the chosen sentinel string actually triggers the rule the plan names against the project's actual lint config — not against an idealized ruleset. Multiple rules may fire on the same string; the first one wins.

**Verification**: at plan-review time, paste the prescribed sentinel into the target file and run the project's linter locally; confirm the named rule appears in the violation list (preferably as the only violation, or the first one). If a different rule fires first, either change the sentinel or update the plan to name the actually-triggered rule.

**Rationale**: Phase 5.1 T9 plan Step 7 named `max-len` as the sentinel rule for the husky pre-commit hook test; the implementer's sentinel string actually triggered `no-unused-vars` + `quotes` first. The hook chain was exercised correctly, but the plan-vs-actual rule mismatch obscured what was being tested.

## `throws X` removal cascade (Phase 5.1 RC-40)

When a refactor removes a `throws X` from a method signature, audit all calling-method `throws` declarations for now-unreachable exceptions. Java doesn't flag stale `throws` declarations as errors, but they bit-rot the signature contract and mislead callers into wrapping calls in try-catch blocks that can never fire.

**Verification**: when reviewing a change that drops `throws X` from method M, grep for callers of M (`grep -rn "M(" --include="*.java"`). For each caller, check whether its own `throws` clause still needs to declare X — and whether the caller's callers transitively still need to.

**Rationale**: Phase 5.1 T10 refactor extracted reflection-using cache-clearing into a helper and de-reflected it. The helper's `throws Exception` signature could be dropped; the calling test method's `throws Exception` was also now stale. The implementer caught the cascade only by being rigorous; the plan didn't prompt the check.

## `@EntityGraph` / `JOIN FETCH` on a `Pageable` collection → in-memory pagination (Phase 5.5 RC-56)

When a paginated repository query (`Page<T>` / takes `Pageable`) eagerly loads a `@OneToMany`/`@ManyToMany` **collection** via `@EntityGraph` or `JOIN FETCH`, Hibernate cannot apply `LIMIT`/`OFFSET` at the SQL level (the join multiplies rows per parent). It silently fetches ALL rows and paginates in memory, emitting `HHH000104: firstResult/maxResults specified with collection fetch; applying in memory`. On a large table this is a latent full-table-scan / OOM, and the warning is easy to miss in a green test run.

**Verification**: for any repository method returning `Page<T>` or taking `Pageable`, check the `@EntityGraph` attributePaths (and any `JOIN FETCH`). `@ManyToOne`/`@OneToOne` paths are safe (no row multiplication); a `@OneToMany`/`@ManyToMany` collection is NOT. If list rows need collection-derived data, load the collection in a SECOND batch query keyed by the page's ids (`findByParentIdIn(ids)`), not via the paginated fetch graph.

**Rationale**: Phase 5.5 LQ2 enriched the ProductSupplier list with preferences. The naive instinct — add `productSupplierPreferences` to the list query's `@EntityGraph` — would have triggered HHH000104 + in-memory pagination. The fix batch-loads preferences via `findByProductSupplierIdIn` over the page's supplier ids, keeping `LIMIT`/`OFFSET` DB-side. The pricing fields (`defaultProductPackage.uom`, `.productPrice`) ARE in the `@EntityGraph` because they're all `@ManyToOne` (no multiplication).

## Changelog evidence misattribution (Phase 6 RC-57)

When a plan or spec cites a changelog/migration line as the origin of a specific column (or other table-scoped attribute), verify the **enclosing `createTable` block**, not the nearest line — and confirm against the live schema with `DESCRIBE <table>`. A line number inside a large generated changelog can sit in a different table's block than the one being attributed.

**Rationale**: Phase 6 PA19/spec-V1/A16 cited `changelog-create-tables.groovy:3659 warehouse_id` as `inventory`'s column; it actually belongs to the `user` table (`createTable(tableName:"user")` opens at `:3644`), and `DESCRIBE inventory` has no `warehouse_id`. The misattribution would have shipped `InventoryRepository.findByWarehouseId(...)`; resolved via Option B (native `location.inventory_id` read).

**Verification**: when a plan cites `<changelog>:<line>` as a column's origin, scroll up to the enclosing `createTable(tableName:"<t>")` and confirm `<t>` is the attributed table; then cite `DESCRIBE <t>` (or `SHOW COLUMNS`) output confirming the column's presence/absence.

## Discriminating-fixture rule (Phase 6 RC-59)

A green test whose fixture's insertion/input order coincides with the expected sorted/transformed output does NOT prove the transform — a no-op (or wrong) implementation would pass identically. For any sort/dedup/filter/merge behavior the reviewer MUST ask: **"could this test pass against a deliberately-broken implementation?"** If yes, the fixture is non-discriminating; require inputs where correct-output ≠ any naive-output.

**Rationale**: Phase 6 T6's first RC-16 fixture mocked global `{A,B}` ∪ facility `{A,C}` → the only natural insertion order `[A,B,C]` equalled the sorted order, so `contains("A","B","C")` couldn't distinguish a `TreeSet` from a `LinkedHashSet`. The fix made it sort-distinguishing (mock `{B,D}` ∪ facility `{A,D}` → sorted `[A,B,D]` ≠ insertion `[B,D,A]`). Commit `0f4b40063`.

**Verification**: for each sort/dedup/filter assertion, confirm the fixture's raw input order differs from the asserted output order (and that duplicates/empties are present when those are filtered), so a non-transforming impl fails.

## New-service SecurityConfig must permit the ERROR dispatch (Phase 6 RC-60)

Every Spring Boot service's `SecurityConfig` MUST include `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` as the first matcher in its `authorizeHttpRequests` block. Spring Security's filter chain re-runs on the internal `/error` forward; without the permit, an unhandled controller exception is re-intercepted by `anyRequest().authenticated()` and masked as a spurious 401 instead of surfacing its real status. A `@RestControllerAdvice` shapes *known* errors but cannot cover exceptions thrown outside controllers (filters/async) — the permit is the universal baseline.

**Rationale**: Phase 6 RC-16's invalid-facility 500 surfaced as 401 until the permit was added (`50835b52a`); catalog/identity/document dodged it only via their GlobalExceptionHandlers, and organization/location were fully exposed. Harmonized across all 6 in Phase 6.1.

**Verification**: when reviewing a new service (or a SecurityConfig change), confirm the ERROR-dispatch permit is present, and assert error-status contracts per `synthetic-payload-blind-spot.md` § "Error-status contracts can't be proven by MockMvc (RC-58)" — not via MockMvc.
