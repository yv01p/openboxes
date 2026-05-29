# SDD reviewer checklist (project-local additional checks)

Items to include in every SDD spec/code-quality reviewer subagent prompt, beyond the default code-review template at `~/.claude/plugins/cache/.../requesting-code-review/code-reviewer.md`.

## JPA inheritance + nullability (Phase 4 RC-1)

When reviewing entity changes touching JPA `@Inheritance(SINGLE_TABLE)`:

- **Cross-check subclass field nullability against the actual base-table schema in the production database** — do NOT just rely on Hibernate `ddl-auto: validate` (which checks column existence + type only, NOT nullability).
- Subclass-only fields under SINGLE_TABLE inheritance MUST be `nullable = true` (or `@Column` with no `nullable` hint — JPA default is true) when the base table allows the column to be NULL (typical when bare-base-class rows coexist with subclass rows).
- Business-rule nullability ("must be non-null for create") belongs in DTO validation (`@NotBlank`, `@NotNull` on command/request types), NOT on the entity field.

**Rationale**: Phase 4 T9 surfaced this trap. Spring Boot Organization entity declared `@Column(nullable = false)` on `code`/`name`/`active` matching business rules; production `party` table has these as NULLABLE so bare Party rows can coexist. `ddl-auto: validate` did NOT catch the divergence. Surfaced only when T9 tests used `ddl-auto: create` which generates DDL FROM annotations → bare-Party INSERT failed with NOT NULL violation. Fix landed at commit `8cba628f1` (Phase 4 T9 fixup).

**Verification**: when reviewing such changes, ask the implementer to either (a) run `SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = '<table>' AND column_name IN (<subclass-fields>);` against production OR (b) cite the migration that created the table to confirm nullability.
