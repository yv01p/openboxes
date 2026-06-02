# Critical Implementation Review: 2026-05-26-phase-1-document-slice-implementation-plan (Round 2)

**Plan:** `/home/yv01p/openboxes/docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md`
**Verified plan-level assumptions section:** present

⚠️ 13 commits since plan-write time (SHA `34283a7`); 3 since Round 1 (`72ace6b` + `8a965aa` CI-only, `587d12c` UIP Round 1). cited file:line references re-checked under §1.

## 1. Verified-plan-assumptions cross-check

All 46 verified plan-level assumptions (P1–P46) reconfirmed under fresh reads. The plan's P-table is unchanged from Round 1 (UIP Round 1's 9 edits modified body content only: Task 8b Step 1 code block, Task 8b Steps 8/10 prose, Task 8b Step 1 Note, Task 3/4/5 code blocks for the missing endpoints + methods, Task 6 Step 5 for preconditions). The 3 post-Round-1 commits do not touch any Document-related code path:

- `72ace6b` — `.github/workflows/e2e-tests.yml` only (docker-compose v1 → v2 syntax)
- `8a965aa` — `.github/workflows/*.yml` only (actions/* @v4 → @v5 bump)
- `587d12c` — `docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md` only (UIP Round 1 edits)

No assumption-evidence file changed. All verified plan-level assumptions reconfirmed.

## 2. Literal-wrongness findings

### Finding 1: nginx `location /api/documents/` (trailing slash) does not match Task 11's `POST /api/documents`; the upload test gets 404 and the done-gate fails

**Description:** Task 9 Step 1's nginx block uses prefix `location /api/documents/` (trailing slash). nginx prefix-match requires the request URI to literally start with `/api/documents/`. Task 11 Step 1's Playwright spec sends `request.post('/api/documents', ...)` (no trailing slash). The URI `/api/documents` does not start with `/api/documents/`, so nginx skips this block and falls through to the existing `location /api/` block (`docker/nginx/conf.d/app.conf:7`), which proxies to `http://app:8080/openboxes/api/`. Grails has no `/api/documents` POST endpoint → 404.

This breaks the Phase 1 done-gate at Task 13 Step 2 ("All Phase 0 + Phase 1 Playwright tests green"). The upload spec never reaches document-service.

Even if the test URL were changed to `/api/documents/` (with trailing slash), document-service's handler is at `@RequestMapping("/api/documents")` and Spring Boot 3.x defaults `useTrailingSlashMatch=false` (deprecated since 6.0), so Spring returns 404 for `/api/documents/`. The mismatch is bidirectional: the existing `location /api/documents/` block can only route URIs that include path content AFTER `/api/documents/`, and Spring only handles `/api/documents` (no slash) — there is no overlap for the bare `POST /api/documents` case.

This applies to every endpoint at the collection root: `POST /api/documents` (create), `GET /api/documents?code=` (listByCode), `GET /api/documents?name=` (listByName), `GET /api/documents?typeIds=` (listByTypeIds). All four would 404 through nginx. Path-suffix endpoints (`GET /api/documents/{id}`, `GET /api/documents/{id}/content`, `DELETE /api/documents/{id}`, `GET /api/documents/types/non-template`) are unaffected because they have content after `/api/documents/` and match the existing block.

Note: Grails-side `DocumentClient.groovy` bypasses nginx — it uses `baseUrl = http://document-service:8081` (Task 8b Step 1, plan line 1039) and talks to the service directly inside the docker network. So callers migrated in Task 8b work correctly. Only requests entering through nginx are affected — which is exactly the Playwright tests in Task 11 (Playwright `baseURL = http://localhost`, verified at `e2e/playwright.config.ts:7`).

**Evidence:**
- plan line 1200 (Task 9 Step 1 `location /api/documents/` with trailing slash)
- plan line 1201 (`proxy_pass http://document-service:8081/api/documents/;`)
- plan line 1295 (Task 11 Step 1 Playwright: `request.post('/api/documents', ...)`)
- plan line 686-687 (Task 5 controller `@RequestMapping("/api/documents")` — no trailing slash; class-level)
- `docker/nginx/conf.d/app.conf:7` (existing `location /api/` block that catches the fall-through)
- `e2e/playwright.config.ts:7` (`baseURL: process.env.BASE_URL || 'http://localhost'`)
- Spring Boot 3.x release notes: `setUseTrailingSlashMatch` deprecated; default `false` since 6.0

**Proposed fix:** Change the Task 9 Step 1 nginx block from a path-suffix-replacing prefix match to a pass-through prefix match (without trailing slash on both `location` and `proxy_pass`):

```nginx
location /api/documents {
    proxy_pass http://document-service:8081;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $remote_addr;
    proxy_set_header Cookie $http_cookie;
}
```

Behavior table after fix:
- `POST /api/documents` → upstream gets `POST /api/documents` ✅
- `GET /api/documents?code=X` → upstream gets `GET /api/documents?code=X` ✅
- `GET /api/documents/{id}` → upstream gets `GET /api/documents/{id}` ✅
- `GET /api/documents/types/non-template` → upstream gets `GET /api/documents/types/non-template` ✅

Note: `proxy_pass http://document-service:8081;` (no URI suffix) tells nginx to pass the original request URI verbatim. `proxy_pass http://document-service:8081/api/documents/;` (with URI suffix) tells nginx to substitute the matched location prefix with the proxy_pass URI — which is what created the trailing-slash dependency.

Also update Task 9 Step 2's verification curl URL (currently `/api/documents/types/non-template`; that path-suffix endpoint works with either form, but adding a smoke probe for the bare collection root catches future regressions):

```bash
# Probe bare collection root (this was 404'd before the fix)
curl -s -o /dev/null -w "POST /api/documents (multipart) HTTP %{http_code}\n" \
  -X POST -F "file=@/etc/hostname" -F "name=smoke-probe" http://localhost/api/documents
# Expect 401 (auth required) — confirms nginx routes the bare path to document-service
```

## 3. Forced decisions

No forced decisions found.

## 4. Previously addressed

Round 1 findings and forced decisions, all resolved by `587d12c` (UIP Round 1):

- **Round 1 §2 Finding 1** (`DocumentClient.create()` stub throws `UnsupportedOperationException`) → resolved: Task 8b Step 1 now embeds the working Spring `RestTemplate` impl with cookie-forwarding (plan lines 1094-1113).
- **Round 1 §2 Finding 2** (`findByName` / `findByTypeInList` undefined in client + missing endpoints in `DocumentController`) → resolved: 2 endpoints added to Task 5 Step 1 (plan lines 724-734), 2 methods added to Task 4 Step 1 (plan lines 633-637), 2 repository methods added to Task 3 Step 4 (plan lines 559-560), 2 client methods added to Task 8b Step 1 (plan lines 1075-1086); Step 8 and Step 10 prose tightened (plan lines 1149, 1154-1155); renamed client method to `findByTypeIds` to match the new endpoint's `typeIds` param.
- **Round 1 §2 Finding 3** (Task 6 Liquibase relocation will fail; A17 misread) → resolved: Task 6 Step 5 rewritten with the corrected A17 reading, per-changeset-type `<preConditions onFail="MARK_RAN">` snippets, and corrected verification SQL expectation (~2 × PRE_COUNT) (plan lines 806-852).
- **Round 1 §3 Forced Decision 1** (`DocumentClient.create()` multipart strategy) → resolved by user pick (Spring `RestTemplate`); decision rationale captured in Task 8b Step 1 Note (plan line 1121).

## 5. Recommendation

⚠️ **Approve with literal-wrongness fixes**

- §1: all 46 P-assumptions reconfirmed.
- §2: 1 literal-wrongness finding (nginx routing trailing-slash mismatch breaks Phase 1 done-gate).
- §3: no forced decisions.

Recommended path: run `update-implementation-plan` against this review file. The §2 fix is mechanical (one block in Task 9 Step 1 + an optional smoke probe addition in Task 9 Step 2). No user input needed.
