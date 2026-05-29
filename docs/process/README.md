# Process docs

Codified lessons accumulated across migration phases. Future plan-writers + SDD reviewers consult these before starting a new phase.

## Files

- `sdd-reviewer-checklist.md` — additional checks for spec/code reviewers (e.g., JPA SINGLE_TABLE nullability)
- `plan-template-defects.md` — known defects in plan templates (e.g., T12 done-gate scripts)

## How to add a new lesson

A lesson goes here when (a) it would prevent a recurring class of error AND (b) it's project-local (i.e., not a fix to the Claude Code skill itself, which lives in plugin cache and is fragile to edit).

Mark each entry with the phase that surfaced it for backreference (e.g., "Phase 4 RC-1").
