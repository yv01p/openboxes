# Critical Implementation Review: 2026-06-03-phase-6-inventory-service-implementation-plan (Round 1)

**Plan:** /home/yv01p/openboxes/docs/plans/2026-06-03-phase-6-inventory-service-implementation-plan.md
**Verified plan-level assumptions section:** present

⚠️ 1 commit since plan-write time (spec SHA `478321bf2`): `5f7bf7b96 add phase-6-inventory-service implementation plan` — this is the plan-doc commit itself; it changes no source the plan references. All cited `file:line` evidence is byte-identical to plan-write time; the load-bearing subset was re-read fresh under §1.

Note on header form: the plan's `**Source spec:**` line uses `` (commit `478321bf2`) `` rather than the canonical `(commit SHA: <SHA>)` form, so automated drift parsing would skip; drift was computed manually from the embedded SHA. This is cosmetic (does not affect execution) and is recorded here, not as a §2 finding.

## 1. Verified-plan-assumptions cross-check

The only commit since plan-write is the plan doc itself (no source tree change), so every cited evidence reference is unchanged. The load-bearing subset was re-read fresh this round and all reconfirm:

- **PA9** (compose paths) — still holds: no root `docker-compose-base.yml`; `docker/docker-compose-base.yml` + `docker/docker-compose.yml` present.
- **PA18** (catalog `Product` entity omits `abcClass`) — still holds: `services/catalog-service/src/main/java/org/openboxes/catalog/entity/Product.java` has no `abcClass` field/getter; T4's add is required and correct. `abc_class` VARCHAR(255) exists in the `product` table (`changelog-create-tables.groovy:1969`), so `ddl-auto=validate` will accept the addition.
- **PA21/PA22** (JWT filter + cookie) — still holds: `SecurityConfig.java:11` imports `org.openboxes.auth.common.JwtCookieAuthFilter`; `JwtService.java:14` defines `COOKIE_NAME = "obx_token"`.
- **PA35/PA51** (nginx has no regex locations; `/api/` is a plain prefix) — still holds: `docker/nginx/conf.d/app.conf` contains only `location =`, `location /prefix/`, and `location /` blocks; the `/api/` block at line 236 is a plain prefix (no `^~`), so a new regex location intercepts before it.
- **PA39** (React consumer shape) — still holds **and strengthened**: `src/js/api/urls.js:5` `const API = '/api'`, so `PRODUCT_CLASSIFICATIONS_API` (`urls.js:205`) resolves to the external path `/api/facilities/{facilityId}/products/classifications` — the exact path the plan's nginx regex matches. This closes the only integration-boundary gap that could have silently broken the cutover (had `API` carried an `/openboxes` prefix, the regex would not have matched and the path would have fallen through to Grails, 404-ing after T8). It does not.
- **PA48** (outbound-HTTP precedent = Spring `RestClient`) — still holds: `identity-service/.../RecaptchaService.java:5,16` uses `org.springframework.web.client.RestClient` / `RestClient.create()`.
- **PA49/PA50** (8 table names; install changelog is a stale column source) — still holds: tables `inventory`/`inventory_item`/`inventory_level`/`product_availability`/`transaction`/`transaction_entry`/`transaction_type` in `changelog-create-tables.groovy`; `transaction_source` in `0.9.x/changelog-2025-10-08-1700-create-table-transaction-source.xml`; `inventory.warehouse_id` absent from the install `inventory` block (drift) — DESCRIBE-first mandate validated.

All remaining verified plan-level assumptions (PA1–PA8, PA10–PA17, PA19–PA20, PA23–PA29, PA36–PA38, PA40–PA47) reference source that is unchanged since plan-write and are reconfirmed.

## 2. Literal-wrongness findings

No literal-wrongness findings.

The dynamic / integration-boundary pass (CIR's distinctive mode) examined the runtime paths that no upstream skill covers; each holds under the spec's stated outcome:

- **Cutover routing** — React's external path is `/api/...` (PA39 above); the nginx regex matches it and beats the `/api/` prefix; `proxy_pass` (no URI) forwards the full path; the inventory-service controller `@GetMapping` matches it. End-to-end the live path reroutes correctly.
- **Token-forwarding round-trip** — nginx forwards the cookie (`proxy_params` sets `Cookie $http_cookie`); inventory-service's filter authenticates (so the controller's cookie read always finds `obx_token` on a request that reached it); the forwarded `Cookie: obx_token=<jwt>` is a valid cookie header that catalog-service's same `jwt-auth-common` filter parses; both services share `OPENBOXES_JWT_SECRET` (compose env), so the forwarded token authenticates at catalog. JWT chars are cookie-safe.
- **Cross-service union semantics** — `TreeSet<String>` reproduces Grails' dedup + natural-order sort; both source queries filter `is not null` and `<> ''`, so no null reaches the set; `CatalogReadClient` returns `List.of()` on a null body. Output matches the Grails contract.
- **Liquibase coexistence on the shared DB** — namespaced changeset IDs (`phase6-shadow-create-*`, author `openboxes-inventory`, per-file `logicalFilePath`) keep inventory-service's rows distinct from catalog-service's and Grails' in the shared `DATABASECHANGELOG`; the `DATABASECHANGELOGLOCK` serializes concurrent startup. This is the established A23 pattern.
- **`transaction` reserved word** — no core query reads the `Transaction` entity (no repository for it); `ddl-auto=validate` uses `information_schema` lookups (no `FROM transaction`), so the reserved word does not bite in core. The plan's backtick fallback covers the latent case.
- **Build → route → test → delete ordering** — the Grails `ProductClassification*` source and its integration specs remain intact through T6/T7 (so they still compile/pass), and are deleted only at T8 after the inventory-service path is built (T4), routed (T5), contract-tested (T6), and e2e-verified (T7). nginx already shadows the Grails endpoint from T5, so the T8 delete removes dead code.

## 3. Forced decisions

No forced decisions found.

The decisions a reviewer might expect to be silently buried are all explicitly named and resolved in the plan: service-to-service auth (forward `obx_token`, user-decided, §"verification-driven adjustments" #5 + T4); the new catalog endpoint path (`/api/products/abcClasses`, with the `/api/product/{id}` collision explicitly avoided, PA45); the TestContainers `ddl-auto=create` correction (#6); and the `inventory-transactions-summary` candidate (explicitly routed to T1's audit + user-approval gate, not pre-picked). None is an unnamed either/or.

## 4. Recommendation

✅ **Approve as-is** — §1 has no failed assumptions; §2 and §3 are both empty. The plan is ready for `superpowers:subagent-driven-development` (the user may run a further CIR round if desired, mirroring the CDR cadence).
