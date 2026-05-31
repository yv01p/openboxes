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
