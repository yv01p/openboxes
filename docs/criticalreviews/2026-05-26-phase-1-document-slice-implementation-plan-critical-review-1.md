# Critical Implementation Review: 2026-05-26-phase-1-document-slice-implementation-plan (Round 1)

**Plan:** `/home/yv01p/openboxes/docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md`
**Verified plan-level assumptions section:** present

⚠️ 10 commits since plan-write time (SHA `34283a7`); cited file:line references re-checked under §1.

## 1. Verified-plan-assumptions cross-check

All 46 verified plan-level assumptions (P1–P46) reconfirmed under fresh reads:

- **P1** (`services/` doesn't exist) — `ls services` → `No such file or directory` ✅
- **P12** (DocumentUploadController at `shipping/`) — `find` returned `grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy` ✅
- **P36** (no React Document API) — `grep -rnE "/document|/api/documents|DocumentApi" src/js` returned empty ✅
- **P39** (3 GSPs ref `Document.findAllByDocumentCode`) — re-grep returned the same three: `views/inventoryItem/_actionsCurrentStock.gsp:45`, `views/order/_summary.gsp:242`, `views/order/_orderDocuments.gsp:2` ✅
- **P42** (nginx ordering: `/api/`, `/openboxes/`, `/`) — `grep "location " docker/nginx/conf.d/app.conf` returned the same three blocks at lines 7, 14, 22 ✅
- **P2–P11, P13–P14, P15–P35, P37–P38, P40–P41, P43–P46** — re-checked via the same Bash sweep that produced the plan's original evidence. The 10 commits between SHA `34283a7` and HEAD touch `grails-app/{controllers,services}` JWT plumbing (Phase 0), `docker/{nginx,compose}` (Phase 0), `e2e/` (Phase 0), and `docs/`. None touch Document-related code; none invalidate any P-assumption.

All verified plan-level assumptions reconfirmed.

## 2. Literal-wrongness findings

### Finding 1: `DocumentClient.create()` is a stub that throws; multiple Task 8b steps invoke it

**Description:** Task 8b Step 1's `DocumentClient.groovy` code block defines `create(...)` with `throw new UnsupportedOperationException("TODO: implement multipart upload in Task 8b Step 1 detail")`. Task 8b Steps 5 (StockMovementService upload), 12 (DocumentUploadController:20), and 13 (MigrationService:1192) call `documentClient.create(...)`. At SDD execution, those steps' code paths throw the exception unconditionally — the spec's stated Phase 1 outcome ("7+ Grails callers migrated to HTTP") is broken at every caller site that uses `create`.

The plan acknowledges the gap in prose at Task 8b Step 1 ("Recommended: Spring RestTemplate; document the choice in the commit") but does not embed the working code. SDD's per-task fresh-subagent context will copy the stub literally unless the implementer recognizes it as a deferred decision.

**Evidence:** plan lines ~1023-1029 (DocumentClient.create stub); plan lines ~1058 (Step 5: `documentClient.create(...)`), ~1070 (Step 12: DocumentUploadController), ~1073 (Step 13: MigrationService).

**Proposed fix:** Replace the stub body in Task 8b Step 1's code block with a working multipart implementation. Recommended (Spring RestTemplate — Grails already wires Spring; zero new dependency):

```groovy
Map create(String name, String filename, String contentType, byte[] fileContents, String documentTypeId = null) {
    def headers = new org.springframework.http.HttpHeaders()
    headers.setContentType(org.springframework.http.MediaType.MULTIPART_FORM_DATA)
    String token = currentObxToken()
    if (token) headers.add('Cookie', "obx_token=${token}")
    def body = new org.springframework.util.LinkedMultiValueMap<String, Object>()
    body.add('file', new org.springframework.core.io.ByteArrayResource(fileContents) {
        @Override String getFilename() { filename }
    })
    body.add('name', name)
    if (documentTypeId) body.add('documentTypeId', documentTypeId)
    def rest = new org.springframework.web.client.RestTemplate()
    def resp = rest.exchange(
        "${baseUrl}/api/documents",
        org.springframework.http.HttpMethod.POST,
        new org.springframework.http.HttpEntity<>(body, headers),
        Map
    )
    return resp.body
}
```

Tightly coupled to §3 Forced Decision 1 below — surface the multipart-strategy choice to the user before committing this fix.

### Finding 2: `DocumentClient` methods called by Task 8b Steps 8 and 10 are not defined in Step 1, and the corresponding service endpoints are absent from `DocumentController`

**Description:** Task 8b Step 8 says: *"line 943 uses `Document.findByName(...)` — add `findByName(String name)` to `DocumentClient` if needed."* Task 8b Step 10 says: *"`Document.findAllByDocumentTypeInList(...)` → `documentClient.findByTypeInList(...)`."* Neither method appears in `DocumentClient`'s Step 1 code block. The plan also does not expose corresponding endpoints on document-service: Task 5's `DocumentController.java` only defines `GET /{id}`, `GET /{id}/content`, `GET ?code=`, `POST` (multipart), `DELETE /{id}`, and `GET /types/non-template`. There is no `GET ?name=` or `GET ?typeIds=`.

At SDD execution, Step 8's `documentClient.findByName(params.documentTemplate?.name)` substitution and Step 10's `documentClient.findByTypeInList(documentTypes)` substitution refer to undefined methods. Even if the implementer adds the client methods inline, the document-service-side endpoints don't exist; the calls will 404.

**Evidence:** plan lines ~957-1029 (DocumentClient class definition omits `findByName`/`findByTypeInList`); plan lines ~690-711 (DocumentController.java endpoints don't include name-filter or typeIds-filter queries); plan line ~1066 (Step 8: "add `findByName(String name)` to `DocumentClient` if needed"); plan lines ~1078-1080 (Step 10: "OR adjust: most callers can use `findByCode` instead").

**Proposed fix:** Add the two missing endpoints to `DocumentController.java` (Task 5 Step 1) and the matching methods to `DocumentClient.groovy` (Task 8b Step 1):

DocumentController additions:
```java
@Operation(summary = "Find document by name")
@GetMapping(params = "name")
public List<Document> listByName(@RequestParam String name) {
    return docService.findByName(name);
}

@Operation(summary = "List documents whose document_type is in the given set")
@GetMapping(params = "typeIds")
public List<Document> listByTypeIds(@RequestParam List<String> typeIds) {
    return docService.findByTypeIds(typeIds);
}
```
(Add corresponding `findByName` / `findByTypeIds` methods to `DocumentService.java` Task 4 and repository methods.)

DocumentClient additions:
```groovy
List<Map> findByName(String name) {
    def conn = openConn("/api/documents?name=${URLEncoder.encode(name, 'UTF-8')}")
    if (conn.responseCode != 200) throw new RuntimeException("document-service GET ?name=${name} returned ${conn.responseCode}")
    return (List<Map>) new JsonSlurper().parse(conn.inputStream)
}

List<Map> findByTypeIds(List<String> typeIds) {
    String csv = typeIds.collect { URLEncoder.encode(it, 'UTF-8') }.join(',')
    def conn = openConn("/api/documents?typeIds=${csv}")
    if (conn.responseCode != 200) throw new RuntimeException("document-service GET ?typeIds returned ${conn.responseCode}")
    return (List<Map>) new JsonSlurper().parse(conn.inputStream)
}
```

Alternative: rewrite Steps 8 and 10 to use existing endpoints (e.g., fetch all documents via `findByCode` and filter client-side; for typeIds, fetch each type's documents individually and merge). Adds caller-side logic but keeps document-service's API surface smaller.

### Finding 3: Task 6 Liquibase relocation will fail at document-service startup; the plan's verification step misreads spec assumption A17

**Description:** Task 6 Step 5 claims *"document-service Liquibase startup will see 'already applied' too (same row in DATABASECHANGELOG). No double-execution."* and the verification SQL `SELECT COUNT(*) FROM DATABASECHANGELOG WHERE FILENAME LIKE '%document%'` is expected to *"return same count as before the move."* This relies on spec A17's "filename SUBSTRING" wording carrying over to document-service. It does not.

A17 cites `LiquibaseUtil.groovy` (verified at `src/main/groovy/util/LiquibaseUtil.groovy:108`). That single SUBSTRING usage is in `getCurrentVersionsByFolderName()` — it extracts the **version-folder name** (the part of the FILENAME before the first `/`) to determine which version-folders have any applied changelogs, for backward-compat upgrade-path logic. It is NOT a per-changeset dedup mechanism. Per-changeset dedup is via standard Liquibase's `(ID, AUTHOR, FILENAME)` tuple — exact FILENAME match.

After Task 6 Step 2's `git mv grails-app/migrations/0.8.x/changelog-…-document-…xml services/document-service/src/main/resources/db/changelog/`, the changeset is loaded into document-service Liquibase under FILENAME `db/changelog/changelog-…-document-…xml` (classpath-relative). The historical Grails-loaded row in DATABASECHANGELOG has FILENAME `grails-app/migrations/0.8.x/changelog-…-document-…xml`. Standard Spring Boot Liquibase (document-service's runner) does exact FILENAME match → sees the relocated changeset as **new** → tries to re-execute it against tables that already exist → fails (e.g., `CREATE TABLE document` errors with "table already exists"; `ALTER TABLE document ADD COLUMN` errors with "duplicate column"; `MODIFY COLUMN` may succeed silently and corrupt schema).

The plan's Task 6 Step 5 verification SQL will show the count **higher** than before the move (the failed/re-executed changesets get a new DATABASECHANGELOG row OR document-service fails to start before reaching the SELECT). Either symptom blocks the done-gate.

**Evidence:** plan lines ~786-799 (Task 6 Step 5 claims no double-execution); spec line 301 (A17 wording); `src/main/groovy/util/LiquibaseUtil.groovy:108` (the SUBSTRING is for version-folder grouping in `getCurrentVersionsByFolderName()`, not per-changeset dedup); standard Liquibase behavior (FILENAME exact match in DATABASECHANGELOG dedup).

**Proposed fix:** Add `<preConditions onFail="MARK_RAN">` to each relocated changeset before document-service first loads them. Per changeset type:

- **`<createTable>`** changesets:
```xml
<preConditions onFail="MARK_RAN">
    <not><tableExists tableName="document"/></not>
</preConditions>
```
- **`<addColumn>`** changesets:
```xml
<preConditions onFail="MARK_RAN">
    <not><columnExists tableName="document" columnName="file_uri"/></not>
</preConditions>
```
- **`<modifyDataType>` / `<alterColumn>`** changesets — check via `<sqlCheck>`:
```xml
<preConditions onFail="MARK_RAN">
    <not><sqlCheck expectedResult="VARCHAR(2000)">
        SELECT DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS
        WHERE TABLE_NAME='document' AND COLUMN_NAME='file_uri'
    </sqlCheck></not>
</preConditions>
```
- **`<insert>`** seed-data changesets — check via `<sqlCheck>` on row presence.

`onFail="MARK_RAN"` records the changeset as ran in DATABASECHANGELOG (under the new FILENAME) without executing the body. document-service Liquibase startup completes; future additive migrations apply normally.

Alternative (less invasive to changeset content, but more invasive to release process): pre-populate `DATABASECHANGELOG` with rows for the new FILENAMEs as a one-shot manual migration before first deploy:
```sql
INSERT INTO DATABASECHANGELOG (ID, AUTHOR, FILENAME, DATEEXECUTED, ORDEREXECUTED, EXECTYPE, MD5SUM, DESCRIPTION, LIQUIBASE)
SELECT ID, AUTHOR,
       REPLACE(FILENAME, 'grails-app/migrations/0.8.x/', 'db/changelog/'),
       DATEEXECUTED, ORDEREXECUTED + 10000, 'MARK_RAN', MD5SUM, DESCRIPTION, LIQUIBASE
FROM DATABASECHANGELOG
WHERE FILENAME LIKE 'grails-app/migrations/0.%/changelog-%-document-%.xml';
```
This requires running the SQL exactly once at the right moment (after Task 6's git-mv, before first `docker-compose up` of document-service post-Task-6). Preconditions are more idempotent.

Update Task 6 Step 5's verification SQL expected-result accordingly: count should be **double** the pre-move count (each relocated changeset now has both old-FILENAME row from Grails history and new-FILENAME row from document-service `MARK_RAN`).

## 3. Forced decisions

### Forced decision 1: `DocumentClient.create()` multipart upload implementation strategy

**The choice:** Which HTTP-multipart approach to use in `DocumentClient.groovy`'s `create()` method.

**Why it's forced:** Finding 1 above needs a concrete implementation in the plan's code block. Grails 3.3.16's standard library doesn't include a clean multipart-encoding client. The plan surfaces the choice in prose at Task 8b Step 1 ("Recommended: Spring RestTemplate") but explicitly does not pick — and the recommendation is in prose only, not embedded in the executable code block. SDD will copy what's in the code block (the stub) unless the implementer notices the prose recommendation and synthesizes Finding 1's fix.

**The options:**
- (a) **Raw `HttpURLConnection` + manual multipart boundary construction.** Zero new dependencies. ~40 lines of Groovy. Most verbose. Consistent with the rest of `DocumentClient.groovy` (which already uses `HttpURLConnection` for GET/DELETE).
- (b) **Apache HttpClient** (already a transitive dependency via Grails). One additional import. ~15 lines. Mid-verbosity.
- (c) **Spring `RestTemplate`** (already in the Grails Spring context). Zero new dependencies. ~10 lines. Cleanest API. Mixes idioms inside `DocumentClient.groovy` (other methods use raw `HttpURLConnection`) — slight style inconsistency.

The reviewer surfaces the choice; the user picks, and the UIP pass resolves Finding 1 with the picked implementation.

## 5. Recommendation

🛑 **Surface forced decisions to user**

- §1 has no failed assumptions.
- §2 has 3 literal-wrongness findings (Finding 3 is plan-shape-breaking; Findings 1 and 2 break specific Task 8b steps and the Task 5 + Task 8b interface).
- §3 has 1 forced decision (tightly coupled to Finding 1's fix).

Recommended path: run `update-implementation-plan` against this review file. UIP will surface §3 to the user first; once the multipart strategy is picked, Finding 1's fix adopts it. Findings 2 and 3 are mechanical (Finding 2: add 2 endpoints + 2 client methods; Finding 3: add `<preConditions onFail="MARK_RAN">` blocks to relocated changesets + update Task 6 Step 5's expected verification result).
