# Process docs

Codified lessons accumulated across migration phases. Future plan-writers + SDD reviewers consult these before starting a new phase.

## Files

- `sdd-reviewer-checklist.md` — additional checks for spec/code reviewers (e.g., JPA SINGLE_TABLE nullability)
- `plan-template-defects.md` — known defects in plan templates (e.g., T12 done-gate scripts)
- `plan-ordering-rules.md` — task-ordering invariants for plans (compose-modifying ⇒ CI workflow rule + file-conflict rule)
- `synthetic-payload-blind-spot.md` — verifying frontend-facing migrations against reality: real SPA payload shapes (RC-43), a cutover is a verification task not wiring (RC-44), and an empty DB hides every write path → seeded round-trip mandatory (RC-45)
- `sdd-controller-practices.md` — SDD controller practices (groundwork before dispatching infra/data-coupled tasks)
- `phase-decomposition-and-sequencing.md` — how to carve and order the *phases themselves* (decompose by coupling not boundary; verify phase scope vs code; the "N.5 bucket" smell; demand-driven sequencing; name the keystone) — Phase 6.5 analysis

## How to add a new lesson

A lesson goes here when (a) it would prevent a recurring class of error AND (b) it's project-local (i.e., not a fix to the Claude Code skill itself, which lives in plugin cache and is fragile to edit).

Mark each entry with the phase that surfaced it for backreference (e.g., "Phase 4 RC-1").

## Codify-mid-stream vs defer-to-retro (Phase 5 RC-25)

When a process discipline gap surfaces during a phase with a clear codification target, capturing it inline (≈5 min) is strictly better than waiting for the retro.

**Codify-mid-stream when ALL of:**
- The lesson has a clear codification target file (e.g., `plan-ordering-rules.md`, `sdd-reviewer-checklist.md`).
- The recurrence risk is concrete and bounded (e.g., a pattern repeats across remaining tasks in this phase or the next phase's planned scope).
- Capturing inline doesn't bloat the active phase (the codification commit is < 30 lines and references existing infrastructure).

**Defer to retro when:**
- The lesson needs cross-RC pattern-matching to formulate.
- The codification target file doesn't exist yet.
- The lesson is meta-process (not a single rule that fits one doc).

**Empirical precedents:**
- Phase 5 RC-6 (nginx prefix-vs-Grails-sub-route): codified mid-Phase-5 at commit `7291864fc` as Rule 3 in `plan-ordering-rules.md` immediately after T9's nginx fix. Phase 5.5+ plan-writers consult the rule at plan-write time.
- Phase 5 RC-26 (ESLint max-len cache mask): structurally codified mid-Phase-5 at commit `3b87aab6d` via new L1 CI lint job in `e2e-tests.yml` (not a doc — a CI structural change).

## L1+L2 lint-defense layered pattern (Phase 5.1 post-tag carry-in)

The project uses a 2-layer defense against ESLint regressions:

- **L1** (CI-enforced): `lint` job in `.github/workflows/e2e-tests.yml` runs `npm run lint` (eslint without `--fix`) on a clean ubuntu runner in ≈1 min before the 6-min Gradle/Docker e2e job. The `e2e` job uses `needs: lint` to short-circuit on lint failure. Surfaced violations cannot reach `main` via PR.
- **L2** (developer-machine): `.husky/pre-commit` runs `npx lint-staged` which invokes `eslint --fix` on staged `src/js/**/*.{js,jsx}` only. Auto-fixes whitespace/semis/etc + blocks unfixable errors at commit time. Bypass-able with `git commit --no-verify` (single-dev workflow).

**Empirical motivation**: Phase 5 RC-26 — 6 ESLint `max-len` violations from T9 React URL changes silently passed local Gradle build (cached `:npm_run_bundle` UP-TO-DATE hid them) but failed fresh CI 6 minutes into prepareDocker. Layered defense prevents the next iteration of this trap.

**Benign noise on non-JS commits** (Phase 5.1 RC-41): lint-staged is configured for `src/js/**/*.{js,jsx}` only. On commits that touch only Java / Groovy / `.md` / Gradle / `.github/` files, the pre-commit hook emits `lint-staged could not find any staged files matching configured tasks`. This is intended behavior — the hook ran, lint-staged correctly identified zero JS files to lint, and the commit proceeds. The message is NOT "hooks were skipped"; it's "hooks ran and had nothing to do".

**When adding a new lint rule**: update `.eslintrc` (the project's ESLint config; `eslint.config.js` would apply if/when migrated to ESLint 9+ flat config) once; both L1 and L2 inherit automatically.
