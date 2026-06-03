# SDD controller practices (project-local)

Practices for the SDD *controller* (the orchestrating session that dispatches implementer subagents), beyond the per-task STOP + two-stage review cadence.

## Controller groundwork before dispatching infra/data-coupled tasks (Phase 6 RC-63)

For a task whose correctness depends on live data or infrastructure state (DB contents, routing, container wiring), the controller should **de-risk the data/infra layer itself before dispatching** — discover the live facts and hand the implementer verified facts + exact expectations — rather than dispatching a fresh subagent into the unknown to reconstruct them.

**Rationale**: Phase 6 T7 — before dispatching, the controller discovered live that the dev/CI DB is empty of products/inventory_levels (so the seed must INSERT not UPDATE), found the minimal NOT-NULL footprint via `information_schema`, confirmed nginx routes RC-16 to inventory-service while `/api/` falls through to Grails, and proved the seeded union end-to-end. The implementer then shipped a green spec first try.

**When to apply**: tasks coupled to DB data, routing/nginx, container build/wiring, or any environment state a fresh subagent would otherwise have to rediscover. For pure code-logic tasks the implementer can self-discover, this is unnecessary.
