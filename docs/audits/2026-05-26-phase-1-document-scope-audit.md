# Phase 1 Document slice — cross-context scope audit

**Date:** 2026-05-26
**Branch / HEAD:** `main` @ `5a19b4e` (plan SHA at write time)
**Scope:** Pre-implementation audit for the Document slice migration to `document-service` (Spring Boot).
**Author / Operator:** Task 1 of the Phase 1 implementation plan (`docs/plans/2026-05-26-phase-1-document-slice-implementation-plan.md`).
**Status:** DONE — within scope, no plan revision triggered.

---

## Step 1 — §8 four-grep cross-context audit

All greps run from repo root against `grails-app/`. Findings classified per spec §4.3 policy
(M:N link write on owning side → local; atomic-across-2-contexts → 2-step saga deferred to
Phase 7; read-only cross-context → HTTP call).

### (a) `addToDocuments` / `removeFromDocuments`

```
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:471:      command.product.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:517:      command.product.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:553:      productInstance.removeFromDocuments(documentInstance)
grails-app/controllers/org/pih/warehouse/order/OrderController.groovy:551:        orderInstance.removeFromDocuments(documentInstance)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:236:     shipmentInstance.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:239:     orderInstance.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:242:     requestInstance.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:245:     productInstance.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:248:     invoiceInstance.addToDocuments(documentInstance).save(flush: true)
grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy:166:    invoiceInstance.removeFromDocuments(documentInstance)
grails-app/controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy:20: shipment.addToDocuments(command.document)
grails-app/controllers/org/pih/warehouse/shipping/ShipmentController.groovy:929:   shipment.removeFromDocuments(document)
grails-app/services/org/pih/warehouse/inventory/StockMovementService.groovy:3461:  shipment.addToDocuments(document)
```

**Classification:** All 13 hits are **M:N link writes on the owning side** (the entity OWNING the
join table — `Product`, `Order`, `Shipment`, `Invoice`, `Request` — is the side issuing
`addToDocuments` / `removeFromDocuments`). Per §4.3 policy these are **local-to-owner** writes.
After the migration, the owning service writes the join row locally (the join tables stay
co-resident with the owner — see Step 4 / §4.3 join-table ownership), and `document-service`
only owns the `document` row itself. The migration of these callers (Task 8b) replaces
construction + ownership of the Document instance with an HTTP call to `document-service`
that returns a DTO containing the new `id`; the local `addToDocuments(...)` invocation then
attaches a thin reference (or, post-migration, a row insert via the still-Grails-side join).
**No saga required for Phase 1.**

### (b) `Document.delete` / `documentInstance.delete` / `document?.delete`

The plan's literal grep pattern (`Document\.delete\|documentInstance\.delete\|document\?\.delete`)
does NOT match `documentInstance?.delete()` (the regex needs `documentInstance\??\.delete`).
A permissive grep — `grep -rnE "(Document|documentInstance|document)\??\.delete\(" grails-app/` —
surfaces a 3rd site that the plan's literal pattern missed:

```
grails-app/controllers/org/pih/warehouse/shipping/ShipmentController.groovy:930:   document.delete()
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:168:       documentInstance.delete(flush: true)
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:554:     documentInstance?.delete()
```

**Classification:** All 3 calls become HTTP `DELETE /api/documents/{id}` to `document-service`
in Task 8b. They are **read-only-from-owner-context cross-context writes** — neither hits the
owning entity's tables, only the `document` table — so a plain sync HTTP call (with
forwarded cookie auth, per spec §6) is correct. The `ShipmentController` case (line 929-930)
and the `ProductController` case (line 553-554) both combine a `removeFromDocuments` (local
M:N) with a `document.delete()` / `documentInstance?.delete()` (HTTP) — these are two distinct
operations and the audit confirms they are not atomic-across-contexts in a way that requires a
saga (failure of the HTTP delete leaves an orphaned join row pointing to a deleted document id
— same failure mode as today's network partition, acceptable for admin-scale Document deletes
per the §4.3 deferral to Phase 7). Phase 1 uses sync HTTP with cookie forwarding for both
the `removeFromDocuments` (local-to-owner) and `DELETE /api/documents/{id}` calls in each
remove-then-delete pair. Task 8b's ProductController migration recipe MUST include the
`documentInstance?.delete()` translation alongside the `removeFromDocuments` translation —
otherwise the row in `document` is orphaned after the join is stripped.

### (c) `new Document(` + `documentInstance.save` / `document.save`

```
=== new Document( ===
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:462: documentInstance = new Document(
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:498: Document documentInstance = new Document(
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:1122: documentInstance = new Document()
grails-app/controllers/org/pih/warehouse/shipping/ShipmentController.groovy:820:  documentInstance = new Document()
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:82:    def documentInstance = new Document()
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:90:    def documentInstance = new Document(params)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:214:  Document documentInstance = new Document(
grails-app/controllers/org/pih/warehouse/inventory/StockMovementController.groovy:507: documentInstance = new Document()
grails-app/services/org/pih/warehouse/data/MigrationService.groovy:1192:   Document migrationReport = new Document()
grails-app/services/org/pih/warehouse/inventory/StockMovementService.groovy:3454: Document document = new Document()

=== documentInstance.save / document.save (in DocumentController only) ===
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:107: documentInstance.save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:152: documentInstance.save(flush: true)
grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy:361: documentInstance.save(flush: true)
```

**Classification:** Each `new Document(...)` becomes a `POST /api/documents` HTTP call in
Task 8b. All `.save(flush: true)` calls outside of `DocumentController.groovy` are chained
onto `addToDocuments(...)` (covered in (a)) — they save the OWNING entity (product/order/etc.),
not the Document, and remain local to the owning service. The three `.save(...)` calls
inside `DocumentController.groovy` itself migrate to `document-service` as part of porting
the controller (Task 5). **No saga required.**

### (d) `documentService` cross-context injections

Full grep output (43 hits across 22 files); the relevant breakdown is:

**Entity-facing calls (`getNonTemplateDocumentTypes`, `getAllDocumentsBySupplierOrganization`,
`scaleImage`) — these reach into the Document/DocumentType tables:**

```
grails-app/controllers/org/pih/warehouse/invoice/InvoiceController.groovy:124,136: documentService.getNonTemplateDocumentTypes()
grails-app/controllers/org/pih/warehouse/order/OrderController.groovy:508,520:    documentService.getNonTemplateDocumentTypes()
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:1119:    documentService.getNonTemplateDocumentTypes()
grails-app/controllers/org/pih/warehouse/shipping/ShipmentController.groovy:817,836: documentService.getNonTemplateDocumentTypes()
grails-app/controllers/org/pih/warehouse/inventory/StockMovementController.groovy:502: documentService.getNonTemplateDocumentTypes()
grails-app/controllers/org/pih/warehouse/product/ProductController.groovy:631:      documentService.scaleImage(documentInstance, ...)
grails-app/controllers/org/pih/warehouse/core/SupplierController.groovy:33:         documentService.getAllDocumentsBySupplierOrganization(supplier)
```

**File / Excel / PDF / template / image *utility* calls — DO NOT TOUCH the Document table,
remain on the Grails side per plan-assumption P20 (DocumentService.groovy is NOT deleted in
Phase 1; only the 6 entity-facing methods migrate):**

```
generateExcel / generateInventoryTemplate / generateChecklistAsDocx / generatePackingList /
generateCertificateOfDonation / generatePartialPackingList / generateStocklistCsv / findFile
```

These stay on the Grails side; their `documentService.` calls do NOT need a DocumentClient.

**Classification (entity-facing only):** All 9 entity-facing `documentService.` call sites
are **read-only cross-context** queries (`getNonTemplate...`, `scaleImage` reads the document
file, `getAllDocumentsBySupplierOrganization` reads documents by supplier). Per §4.3 → plain
sync HTTP call (`GET /api/document-types?excludeTemplate=true`, `GET /api/documents?...`,
etc.). No saga required.

### Cross-context risk summary

| Pattern | Count | Classification | Phase 1 strategy |
|---|---|---|---|
| M:N link write on owning side | 13 | Local-to-owner | Owning service writes join row locally; HTTP call only for the Document row |
| Document deletes | 3 | Cross-context write (admin-scale) | Sync HTTP `DELETE /api/documents/{id}` with cookie forwarding |
| `new Document(...)` constructions | 10 | Cross-context write (admin-scale) | Sync HTTP `POST /api/documents` |
| Entity-facing `documentService.` reads | 9 | Cross-context read | Sync HTTP `GET /api/documents...` |
| Utility `documentService.` calls (file/Excel/PDF/etc.) | ~34 | Stays local (Grails-side) | No change |

**2 sites are remove-join-then-delete-document pairs** (`ShipmentController:929-930` and
`ProductController:553-554`); both are classified per spec §4.3 as **acceptable without saga**
— the failure mode of an orphan `document` row on a connection drop between the two HTTP
calls matches today's network-partition failure mode and is acceptable for admin-scale
Document operations. No 2-context atomic write patterns require a saga for Phase 1. This
confirms the spec's §4.3 deferral of sagas to Phase 7 and the plan's "sync HTTP with cookie
forwarding for admin-scale Document writes" decision.

---

## Step 2 — Caller-file list reconciliation

Expected (from plan "Modify" section / P38): 11 controller/service files + 3 GSPs.

| Expected file | Surfaced by grep | Status |
|---|---|---|
| `controllers/org/pih/warehouse/data/DataExportController.groovy` | `Document.findAllByDocumentCode` / `Document.get` (static reads) | confirmed |
| `services/org/pih/warehouse/core/TemplateService.groovy` | takes `Document` as method parameter (signature only, no entity ops) | confirmed |
| `services/org/pih/warehouse/inventory/StockMovementService.groovy` | `new Document()` + `shipment.addToDocuments(document)` | confirmed |
| `controllers/org/pih/warehouse/invoice/InvoiceController.groovy` | `Document.get`, `removeFromDocuments`, `documentService.getNonTemplate...` | confirmed |
| `controllers/org/pih/warehouse/product/ProductController.groovy` | `new Document()`, `addToDocuments`, `removeFromDocuments`, `documentService.scaleImage`, etc. | confirmed |
| `controllers/org/pih/warehouse/inventory/StockMovementController.groovy` | `new Document()`, `Document.get`, `documentService.getNonTemplate...` | confirmed |
| `controllers/org/pih/warehouse/order/OrderController.groovy` | `Document.get`, `Document.findByName`, `removeFromDocuments`, `documentService.getNonTemplate...` | confirmed |
| `controllers/org/pih/warehouse/shipping/ShipmentController.groovy` | `new Document()`, `Document.get`, `removeFromDocuments`, `document.delete()`, `documentService.getNonTemplate...` | confirmed |
| `controllers/org/pih/warehouse/shipping/ShipmentWorkflowController.groovy` | `Document.findAllByDocumentTypeInList` | confirmed |
| `services/org/pih/warehouse/data/MigrationService.groovy` | `new Document()` | confirmed |
| `controllers/org/pih/warehouse/shipping/DocumentUploadController.groovy` | `shipment.addToDocuments(command.document)` | confirmed |
| `views/inventoryItem/_actionsCurrentStock.gsp` | `Document.findAllByDocumentCode` | confirmed |
| `views/order/_summary.gsp` | `Document.findAllByDocumentCode` | confirmed |
| `views/order/_orderDocuments.gsp` | `Document.findAllByDocumentCode` | confirmed |

**Surprise files surfaced beyond the expected list:** **ZERO.**

Source-of-migration files (NOT in "Modify" — they migrate themselves):
- `grails-app/domain/org/pih/warehouse/core/Document.groovy` → ported to JPA in Task 3 (entity)
- `grails-app/controllers/org/pih/warehouse/core/DocumentController.groovy` → ported to Spring REST in Task 5
- `grails-app/services/org/pih/warehouse/core/DocumentService.groovy` → 6 entity-facing methods port to document-service in Task 4; 20+ utility methods stay (P20)

Scope-creep guardrail: **NOT triggered.** Plan revision NOT required. Continue with plan
as written.

The grep also surfaced **23 controller/service files** that inject `documentService` (exact
count from `grep -rnlE "documentService" grails-app/controllers grails-app/services | sort -u
| wc -l`). The non-entity-facing callers among them call only its file/Excel/PDF/image
*utility* methods (`generateExcel`, `generateInventoryTemplate`, `generatePackingList`,
`generateCertificateOfDonation`, `findFile`, etc.). Per P20 these utility-only callers remain
on the Grails side and **do not need to be modified in Phase 1**.

---

## Step 3 — Live-smoke-probe of existing Grails Document flows

Probe procedure: login via `POST /api/login` (admin/password/location=1), then `curl -L` each
target URL with the cookie jar. Both authentication mechanisms verified working — `obx_token`
JWT cookie is set by `/api/login` (Phase 0 plumbing); the Grails session-based controllers
also accept this cookie via the JWT filter wired in Phase 0.

| URL | Final URL | HTTP | `<title>` | Notes |
|---|---|---|---|---|
| `/openboxes/dataExport/index` | (same) | 200 | "Data Exports" | OK |
| `/openboxes/dataExport/render?id=1` | (same) | **500** | n/a | Expected: id=1 is not a valid DATA_EXPORT-coded Document in the seed DB. Data-availability issue, not a controller defect. Calls `Document.get(params.id)` which returns null → NPE in render. See concern below. |
| `/openboxes/document/list` | (same) | 200 | "List Document" | OK |
| `/openboxes/document/show/1` | redirects → `/document/list` | 200 | "List Document" | id=1 not found → controller redirects to list. Route works, ID just doesn't exist in seed. |
| `/openboxes/invoice/list` | redirects → `/errors/handleForbidden` | 200 | "Access Denied" | Known permissions boundary per Phase 0 retrospective. Not a defect. |
| `/openboxes/product/list` | (same) | 200 | "OpenBoxes" | OK |
| `/openboxes/shipment/list` | (same) | 200 | "Outgoing shipments from Main Warehouse" | OK |

**Probe concerns recorded:**

1. **`dataExport/render?id=1` → 500.** The cause is the absence of any Document rows in the
   seed database (verified via `document/show/1` also failing to resolve, and the
   `dataExport/index` page rendering with no items). When Task 11 (Playwright E2E tests)
   adds tests for this flow, the test must first POST a Document via the new
   `/api/documents` endpoint with `documentCode = "DATA_EXPORT"` (the DocumentType code the
   `dataExport` controller looks up via `Document.findAllByDocumentCode(...)`), then exercise
   `dataExport/render`. This is a test-data setup requirement, **not** a Document-flow defect
   for Phase 1.
2. **`invoice/list` → handleForbidden.** Documented in Phase 0 retrospective; admin@MainWarehouse
   lacks ROLE_INVOICE permissions. The InvoiceController still uses the static
   `Document.findByName / removeFromDocuments` patterns elsewhere; those are exercised via
   `invoice/show/{id}` for an Invoice the user CAN access (Phase 0 path), or via JUnit
   integration tests after the migration. Not a Phase 1 blocker.

**No flow returned a 5xx the plan assumed would work**: the only 500 is the data-availability
issue on `dataExport/render?id=1`, which the plan does not assume returns 200 (it's listed as
a *reachability* probe). Task 1 status: live-probe **passed**.

---

## Step 4 — Liquibase changesets to relocate (Task 6 input)

The plan's prescribed `find` command (filename match `*document*` / `*shipment-document*` /
`*invoice-document*`) returns 5 files. A broader grep on `tableName="document"` /
`tableName="document_type"` surfaces 2 additional files whose filenames do **not** contain
the word "document":

```
grails-app/migrations/0.8.x/changelog-2018-10-25-1318-configure-shipping-templates.xml
grails-app/migrations/0.8.x/changelog-2022-01-03-1524-configure-invoice-templates.xml
```

A 3rd, even broader grep on `referencedTableName="document"` (i.e. FKs that *reference*
`document.id` from a non-`document`-owned base table) surfaces 1 more file that NEITHER the
filename `find` NOR the `tableName=` grep caught:

```
grails-app/migrations/0.7.x/changelog-2016-05-04-2329-add-missing-foreign-key-constraints.xml
```

(In this file, changeset `1462422439127-18` adds `product_document.document_id → document.id`
— a cross-service FK from a join table that is owned by the future product-service to the
`document` table owned by document-service. Same cross-service-FK story as the other
join-table files below.)

**Plan-find gap.** The plan's Task 6 enumeration step uses filename `find` only, which misses
all 3 of the above. Task 6 must additionally sweep with:

```
grep -rnE 'tableName="document"|tableName="document_type"|referencedTableName="document"|referencedTableName="document_type"' grails-app/migrations/
```

Combined enumeration (8 changeset files) with content classification:

### A. Document-table-owned (move to document-service in Task 6)

| File | Changesets | Type | Notes |
|---|---|---|---|
| `0.8.x/changelog-2021-01-30-1530-alter-table-document-change-file-uri-column-type.xml` | 1 (`3001202115300-1`) | `modifyColumn` on `document.file_uri` (→ `longtext`) | Pure `document` table. Move. |
| `0.8.x/changelog-2023-05-22-1800-add-requisition-template-document-type.xml` | 1 (`2205202318000-0`) | `insert` into `document_type` (REQUISITION_TEMPLATE seed) | Pure `document_type` table. Move. |
| `0.8.x/changelog-2018-10-25-1318-configure-shipping-templates.xml` | 1 (`1540492254253-1`) | `insert` into `document_type` (SHIPPING_TEMPLATE seed) | Pure `document_type` table. Move. |
| `0.8.x/changelog-2022-01-03-1524-configure-invoice-templates.xml` | 1 (`0301202215240-0`) | `insert` into `document_type` (INVOICE_TEMPLATE seed) | Pure `document_type` table. Move. |

### B. Join-table changesets (ambiguous / stay-with-owner — flagged for Task 6 decision)

| File | Changesets | Type | Tables touched | Phase 1 disposition |
|---|---|---|---|---|
| `0.8.x/changelog-2021-11-19-1446-create-table-invoice-document.xml` | 3 (`1911202114460-1/2/3`) | `createTable` `invoice_document` + 2 × `addForeignKeyConstraint` (one referencing `invoice`, one referencing `document`) | `invoice_document` join, references `invoice` and `document` | **STAY** with billing-service per spec §4.3 (billing-service owns invoice_document and migrates in Phase 10, NOT Phase 1). The FK to `document(id)` becomes a cross-service FK in the future; for now both tables co-exist in the monolith DB and the FK is preserved by Liquibase replay. **Do NOT move in Task 6.** |
| `0.7.x/changelog-2017-03-01-1644-create-table-shipment-workflow-document-template.xml` | 3 (`1488408290676-1/2/3`) | `createTable` `shipment_workflow_document_template` + 2 × `addForeignKeyConstraint` (one to `document`, one to `product`) | `shipment_workflow_document_template` join, references `document` and `product` | **STAY** with shipment-service (Phase 5+). FK to `document(id)` preserved by monolith-co-residence. **Do NOT move in Task 6.** |
| `0.7.x/changelog-2017-03-06-1953-add-shipping-document-templates.xml` | 6 (`1488850801102-1` … `-6`) | Mixed: `createTable` `shipment_workflow_document` (join) + `addColumn document_type.document_code` (**touches document_type!**) + `addColumn location_group.address_id` + 3 × `addForeignKeyConstraint` (shipment_workflow_document → document, → shipment_workflow; location_group → address) | `shipment_workflow_document` (join), `document_type` (changeset -2), `location_group` (unrelated) | **AMBIGUOUS / SPLIT-NEEDED.** Changeset `-2` adds the `document_code` column to `document_type`. The other 5 changesets are join-table / unrelated. Task 6 must **either (a)** split this file — cherry-pick `-2` into the document-service changelog while leaving the rest behind — **or (b)** keep the whole file on the Grails side and have document-service add `document_code` via a NEW changeset, marked `runOnChange="false"` with a `columnExists` precondition. Recommend option (b) for Task 6 to avoid breaking the historical changelog's integrity. **Flag for Task 6 author.** See "Task 6 prescription for this file" below. |
| `0.7.x/changelog-2016-05-04-2329-add-missing-foreign-key-constraints.xml` | 1 of 50+ in this file (`1462422439127-18`) adds `product_document.document_id → document.id` | `addForeignKeyConstraint` referencing `document(id)` from `product_document` (non-document-owned join) | `product_document` (join), references `document` | **STAY** with product-service (future). The FK lives on a non-document-owned table referencing `document.id`; same cross-service-FK story as the other join-table files. The file itself contains 50+ unrelated FK constraints (inventory_item_snapshot, requisition, etc.) and **must NOT be relocated** — its only document-relevant changeset (`-18`) is preserved by monolith-co-residence and becomes a cross-service FK in the future. **Do NOT move in Task 6.** Surfaced by an additional `referencedTableName="document"` grep, NOT by the plan's filename `find` or the `tableName=` grep. |

#### Task 6 prescription for `0.7.x/changelog-2017-03-06-1953-add-shipping-document-templates.xml` (split-state file)

The Task 6 implementer can lift the following verbatim. Original file STAYS on the Grails side
unchanged; document-service adds a NEW `columnExists`-guarded shadow changeset that
marks-ran when the column already exists (which it always will, because Grails Liquibase has
already added it via the original changeset).

```xml
<!-- services/document-service/src/main/resources/db/changelog/changelog-2017-03-06-1953-document-code-shadow.xml -->
<?xml version="1.0" encoding="UTF-8"?>
<databaseChangeLog xmlns="http://www.liquibase.org/xml/ns/dbchangelog"
                   xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                   xsi:schemaLocation="http://www.liquibase.org/xml/ns/dbchangelog
                                       http://www.liquibase.org/xml/ns/dbchangelog/dbchangelog-4.5.xsd">
    <changeSet id="document-code-shadow-2017-03-06" author="phase-1-migration">
        <preConditions onFail="MARK_RAN">
            <columnExists tableName="document_type" columnName="document_code"/>
        </preConditions>
        <comment>
            Shadow of changeset -2 from grails-app/migrations/0.7.x/changelog-2017-03-06-1953-add-shipping-document-templates.xml.
            That file mixes a document_type.document_code addColumn with unrelated location_group + shipment_workflow_document work,
            so it cannot be relocated to document-service via git mv. document-service marks-ran here instead so future additive
            migrations on document_type apply cleanly under document-service's Liquibase scope. The original file stays on the
            Grails side unchanged; Grails Liquibase continues to own it.
        </comment>
        <!-- No body: the column already exists per the precondition. -->
    </changeSet>
</databaseChangeLog>
```

Add the master-changelog `<include>` line to `services/document-service/src/main/resources/db/changelog/db.changelog-master.xml`:

```xml
<include file="db/changelog/changelog-2017-03-06-1953-document-code-shadow.xml"/>
```

### Summary

- **4 files** are pure-Document and **move** to `document-service/src/main/resources/db/changelog/` in Task 6.
- **3 files** are pure join-table / cross-service-FK and **stay** with their future-owning service (`invoice_document`, `shipment_workflow_document_template`, and `add-missing-foreign-key-constraints.xml`'s `product_document → document` FK).
- **1 file is split-state** (mixes a `document_type.document_code` addColumn with unrelated work). Task 6 should add a NEW `columnExists`-guarded shadow changeset on the document-service side rather than relocating any part of the original file — concrete XML stub provided above under "Task 6 prescription for this file".

Changeset-type breakdown (4 to move):
- `insert` × 3 (seed data for DocumentType rows)
- `modifyColumn` × 1 (`document.file_uri` → `longtext`)

This is the input Task 6 needs to write the relocation Liquibase changelog and its
preconditions.

---

## Self-review

- [x] All 4 grep outputs captured (Step 1).
- [x] Caller-list reconciliation explicit (Step 2) — 11/11 expected confirmed, 0 surprises.
- [x] Live-probe HTTP codes recorded (Step 3) with concerns documented.
- [x] Liquibase enumeration classified by changeset type (Step 4).
- [x] No source code modified.
- [x] One concern recorded for Task 6 author: changeset
      `0.7.x/changelog-2017-03-06-1953-add-shipping-document-templates.xml` mixes
      `document_type` work with join-table work — needs the column-add to be re-issued in
      document-service with a `columnExists` precondition rather than relocating the file.
