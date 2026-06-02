import { test, expect } from '@playwright/test';
import { execSync } from 'child_process';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

// Phase 5.5 CUT round-trip proof. The existing catalog-product-supplier* specs self-skip their write
// paths because the live openboxes-db is EMPTY (0 products), so they never exercise the populated
// save -> reload contract through nginx. This spec SEEDS the minimum real data (product, uom, supplier
// party, destination party, preference type) and drives the REAL flat catalog payloads the React
// ProductSupplier form now sends, asserting the full round-trip:
//   - flat by-id reload (no nested product/supplier objects; defaultProductPackageId NON-null — the bug
//     the ProductPackageService re-link fix closes),
//   - enriched list row (packageSize / packagePrice / unitPrice derived getters + flat preferences),
//   - package UPSERT by (productSupplier, uom, quantity) — a second POST updates in place (no 409,
//     no duplicate row, one default package).
// Per the C3 "test real payloads" lesson, every request body here is exactly the catalog flat DTO the
// form posts (catalog deserializes with FAIL_ON_UNKNOWN_PROPERTIES — extra fields would 4xx).
//
// Seeding is self-contained (beforeAll shells out to the live openboxes-db via docker exec) and
// idempotent (DELETE the *-e2e-rt rows first, then INSERT). afterAll restores the DB to empty.

// All seeded ids are *-e2e-rt prefixed so cleanup is unambiguous.
const PRODUCT_ID = 'prod-e2e-rt';
const UOM_ID = 'uom-e2e-rt';
const SUPPLIER_ORG_ID = 'org-e2e-rt';
const DEST_ORG_ID = 'org-dest-e2e-rt';
const PREF_TYPE_ID = 'pref-e2e-rt';

// Run a SQL statement against the live openboxes-db container. -N strips column headers so the caller
// gets bare values (used by the post-run empty-DB assertion).
function sql(stmt: string): string {
    return execSync(
        `docker exec openboxes-db mysql -N -uopenboxes -popenboxes openboxes -e ${JSON.stringify(stmt)}`,
        { encoding: 'utf8' },
    );
}

// Idempotent teardown: remove every *-e2e-rt row in FK-safe order. Used by BOTH the pre-seed reset and
// the afterAll cleanup, so a crashed prior run cannot leave junk that breaks reseed. The two
// product_price rows the round-trip materializes (the package's own price + the supplier's contract
// price) are reachable only via FKs ON the package/supplier, so we stage them into a temp table BEFORE
// nulling/deleting those owners, then delete the orphaned prices last. Order:
//   1. capture the materialized product_price ids (package price_id + supplier contract_price_id),
//   2. delete preferences (child of product_supplier),
//   3. null the supplier's FK refs (so the supplier + its price/package can be deleted in any order),
//   4. delete packages, then suppliers,
//   5. delete the captured prices, then product/uom/party/preference_type reference rows.
const CLEANUP_SQL = `
DROP TEMPORARY TABLE IF EXISTS e2e_rt_price_ids;
CREATE TEMPORARY TABLE e2e_rt_price_ids (id CHAR(38));
INSERT INTO e2e_rt_price_ids (id)
  SELECT product_price_id FROM product_package
   WHERE product_price_id IS NOT NULL
     AND (product_id='${PRODUCT_ID}' OR product_supplier_id IN (SELECT id FROM product_supplier WHERE product_id='${PRODUCT_ID}' OR supplier_id='${SUPPLIER_ORG_ID}'));
INSERT INTO e2e_rt_price_ids (id)
  SELECT contract_price_id FROM product_supplier
   WHERE contract_price_id IS NOT NULL AND (product_id='${PRODUCT_ID}' OR supplier_id='${SUPPLIER_ORG_ID}');
DELETE FROM product_supplier_preference WHERE product_supplier_id IN (SELECT id FROM product_supplier WHERE product_id LIKE '%e2e-rt%');
UPDATE product_supplier SET default_product_package_id=NULL, contract_price_id=NULL WHERE product_id='${PRODUCT_ID}' OR supplier_id='${SUPPLIER_ORG_ID}';
DELETE FROM product_package WHERE product_id='${PRODUCT_ID}' OR product_supplier_id IN (SELECT id FROM product_supplier WHERE product_id='${PRODUCT_ID}' OR supplier_id='${SUPPLIER_ORG_ID}');
DELETE FROM product_supplier WHERE product_id='${PRODUCT_ID}' OR supplier_id='${SUPPLIER_ORG_ID}';
DELETE FROM product_price WHERE id IN (SELECT id FROM e2e_rt_price_ids);
DROP TEMPORARY TABLE IF EXISTS e2e_rt_price_ids;
DELETE FROM product WHERE id='${PRODUCT_ID}';
DELETE FROM unit_of_measure WHERE id='${UOM_ID}';
DELETE FROM party WHERE id IN ('${SUPPLIER_ORG_ID}','${DEST_ORG_ID}');
DELETE FROM preference_type WHERE id='${PREF_TYPE_ID}';
`;

// Seed the minimum real rows the catalog reads against the live schema. The catalog ProductSupplier
// FKs product_id -> product and supplier_id -> party (DB-level FK, even though the catalog entity maps
// supplierId as a flat String); the package uom_id -> unit_of_measure; the preference
// destination_party_id -> party and preference_type_id -> preference_type. party_type_id '1' is the
// pre-existing "Organization" reference row (verified present in the live DB). version=0 satisfies the
// GORM @Version columns (no DB default); class is NOT NULL on party.
const SEED_SQL = `
INSERT INTO unit_of_measure (id, version, code, name, date_created, last_updated)
  VALUES ('${UOM_ID}', 0, 'BX-RT', 'Box-RT', NOW(), NOW());
INSERT INTO product (id, version, product_code, name, active, date_created, last_updated)
  VALUES ('${PRODUCT_ID}', 0, 'PC-E2E-RT', 'E2E RoundTrip Product', 1, NOW(), NOW());
INSERT INTO party (id, version, party_type_id, class, name, code, active, date_created, last_updated)
  VALUES ('${SUPPLIER_ORG_ID}', 0, '1', 'org.pih.warehouse.core.Organization', 'E2E RT Supplier Org', 'ORG-E2E-RT', 1, NOW(), NOW());
INSERT INTO party (id, version, party_type_id, class, name, code, active, date_created, last_updated)
  VALUES ('${DEST_ORG_ID}', 0, '1', 'org.pih.warehouse.core.Organization', 'E2E RT Dest Org', 'ORG-DEST-E2E-RT', 1, NOW(), NOW());
INSERT INTO preference_type (id, version, name, validation_code, date_created, last_updated)
  VALUES ('${PREF_TYPE_ID}', 0, 'E2E RT Preference', 'DEFAULT', NOW(), NOW());
`;

test.describe('catalog-service ProductSupplier flat round-trip (seeded, through nginx)', () => {
    // The two ProductPrice rows the round-trip materializes (the package's own price + the supplier's
    // contract price) have NO *-e2e-rt namespaced id and are reachable only via FKs ON the package/
    // supplier. Step 7 deletes those owners, orphaning the prices, so the FK-walk in CLEANUP_SQL can no
    // longer reach them at afterAll time. We capture their concrete ids DURING the test (while the owners
    // still reference them) and delete them explicitly in afterAll — deterministic and shared-DB-safe
    // (no time-window sweep that could touch unrelated rows).
    const createdPriceIds: string[] = [];

    test.beforeAll(() => {
        sql(CLEANUP_SQL); // reset any leftovers from a crashed prior run
        sql(SEED_SQL);
    });

    test.afterAll(() => {
        // Delete the captured materialized prices first (children of nothing now), then the FK-walk
        // CLEANUP_SQL removes the supplier/package/preference/reference rows. Order is independent since
        // the prices are no longer referenced, but doing them first keeps the sweep simple.
        const ids = createdPriceIds.filter(Boolean);
        if (ids.length > 0) {
            sql(`DELETE FROM product_price WHERE id IN (${ids.map((id) => `'${id}'`).join(',')});`);
        }
        sql(CLEANUP_SQL); // leave the DB as we found it (empty)
    });

    test('create -> reload (flat, defaultProductPackageId set) -> list (derived pricing + prefs) -> upsert', async ({ request }) => {
        const cookie = await login(request);
        const headers = { Cookie: cookie };

        // ---- Step 1: create the supplier (real flat ProductSupplierDto payload) ----
        const createSupplierRes = await request.post(`${BASE}/api/productSuppliers`, {
            headers,
            data: {
                name: 'E2E RT Supplier',
                code: 'PS-E2E-RT',
                productId: PRODUCT_ID,
                supplierId: SUPPLIER_ORG_ID,
                minOrderQuantity: 5,
                active: true,
            },
        });
        expect(createSupplierRes.status(), await createSupplierRes.text()).toBe(200);
        const supplier = (await createSupplierRes.json()).data;
        expect(typeof supplier.id).toBe('string');
        const supplierId = supplier.id;

        // ---- Step 2: create the package (carries embedded package + contract prices) ----
        const validUntil = '2030-12-31T00:00:00Z';
        const createPkgRes = await request.post(`${BASE}/api/productPackages`, {
            headers,
            data: {
                productId: PRODUCT_ID,
                productSupplierId: supplierId,
                uomId: UOM_ID,
                quantity: 12,
                productPackagePrice: 24.0,
                contractPricePrice: 30.0,
                contractPriceValidUntil: validUntil,
            },
        });
        expect(createPkgRes.status(), await createPkgRes.text()).toBe(200);

        // Capture the two materialized ProductPrice ids NOW (owners still reference them) so afterAll can
        // delete them deterministically — step 7's owner-deletes will orphan them out of FK reach later.
        // The package's own price (product_package.product_price_id) and the supplier's contract price
        // (product_supplier.contract_price_id). The upsert in step 6 updates the package price IN PLACE
        // (same row id), so capturing here is sufficient.
        const pkgPriceId = sql(
            `SELECT product_price_id FROM product_package WHERE product_supplier_id='${supplierId}' AND product_price_id IS NOT NULL;`,
        ).trim();
        const contractPriceId = sql(
            `SELECT contract_price_id FROM product_supplier WHERE id='${supplierId}' AND contract_price_id IS NOT NULL;`,
        ).trim();
        createdPriceIds.push(pkgPriceId, contractPriceId);

        // ---- Step 3: create the preferences (RAW ARRAY batch payload) ----
        const createPrefRes = await request.post(`${BASE}/api/productSupplierPreferences/batch`, {
            headers,
            data: [
                {
                    productSupplierId: supplierId,
                    destinationPartyId: DEST_ORG_ID,
                    preferenceTypeId: PREF_TYPE_ID,
                    comments: 'rt',
                },
            ],
        });
        expect(createPrefRes.status(), await createPrefRes.text()).toBe(200);

        // ---- Step 4: reload by id — flat shape, defaultProductPackageId NON-null (the fix's proof) ----
        const byIdRes = await request.get(`${BASE}/api/productSuppliers/${supplierId}`, { headers });
        expect(byIdRes.status()).toBe(200);
        const dto = (await byIdRes.json()).data;
        // FD#3 flatness: no nested entity objects.
        expect(dto.product).toBeUndefined();
        expect(dto.supplier).toBeUndefined();
        // The bug the ProductPackageService re-link fix closes: defaultProductPackageId is set on reload.
        expect(dto.defaultProductPackageId).toBeTruthy();
        // The embedded contract price was materialized and linked.
        expect(dto.contractPriceId).toBeTruthy();
        expect(Number(dto.minOrderQuantity)).toBe(5);

        // ---- Step 5: reload the list filtered by product — derived pricing + flat preferences ----
        const listRes = await request.get(`${BASE}/api/productSuppliers?product=${PRODUCT_ID}`, { headers });
        expect(listRes.status()).toBe(200);
        const list = await listRes.json();
        const row = list.data.find((r: { id: string }) => r.id === supplierId);
        expect(row, 'seeded supplier must appear in the product-filtered list').toBeTruthy();
        // Derived getters (mirror Grails ProductSupplier transient getters).
        expect(row.packageSize).toBe('BX-RT/12');
        expect(Number(row.packagePrice)).toBeCloseTo(24.0, 2); // tolerant of 24 / 24.0 / 24.00
        expect(Number(row.unitPrice)).toBeCloseTo(2.0, 2); // 24 / 12
        // Flat preference refs (the list page's Preference Type column).
        expect(Array.isArray(row.preferences)).toBeTruthy();
        expect(row.preferences.length).toBe(1);
        expect(row.preferences[0].preferenceTypeId).toBe(PREF_TYPE_ID);
        expect(row.preferences[0].destinationPartyId).toBe(DEST_ORG_ID);

        // ---- Step 6: UPSERT proof — re-POST the SAME (productSupplier, uom, quantity), new price ----
        const upsertRes = await request.post(`${BASE}/api/productPackages`, {
            headers,
            data: {
                productId: PRODUCT_ID,
                productSupplierId: supplierId,
                uomId: UOM_ID,
                quantity: 12,
                productPackagePrice: 26.0,
            },
        });
        // 200 (NOT 409): upsert updates the existing package in place — the DB unique index is never hit.
        expect(upsertRes.status(), await upsertRes.text()).toBe(200);

        const listAfterRes = await request.get(`${BASE}/api/productSuppliers?product=${PRODUCT_ID}`, { headers });
        expect(listAfterRes.status()).toBe(200);
        const listAfter = await listAfterRes.json();
        const rowsForSupplier = listAfter.data.filter((r: { id: string }) => r.id === supplierId);
        // Still exactly one supplier row (no duplicate from the second POST).
        expect(rowsForSupplier.length).toBe(1);
        const updatedRow = rowsForSupplier[0];
        // Package price updated in place; size unchanged (same package, no new row).
        expect(Number(updatedRow.packagePrice)).toBeCloseTo(26.0, 2);
        expect(updatedRow.packageSize).toBe('BX-RT/12');
        // No duplicate package row in the DB for this supplier (the unique index held).
        const pkgCount = sql(
            `SELECT COUNT(*) FROM product_package WHERE product_supplier_id='${supplierId}';`,
        ).trim();
        expect(pkgCount).toBe('1');

        // ---- Step 7: supplier DELETE while a package still references it -> 409 (real catalog contract) ----
        // The catalog ProductSupplierService.delete is non-cascading by design (T1 verified no cascade):
        // product_package.product_supplier_id is a DB FK back to product_supplier, and the catalog exposes
        // NO package-delete API (ProductPackageController is POST/GET-only per YAGNI), so a bare supplier
        // DELETE while a package is attached is REJECTED by the DB FK and surfaces as 409 via the C2
        // DataIntegrityViolation advice. We assert that real behavior here; full teardown of the
        // supplier + its package/prices + the seeded reference rows is done by the afterAll SQL (the only
        // path that can sever the package FK, since there is no package-delete endpoint).
        const delWithPkgRes = await request.delete(`${BASE}/api/productSuppliers/${supplierId}`, { headers });
        expect(delWithPkgRes.status(), await delWithPkgRes.text()).toBe(409);
        // The supplier is still present (the failed delete rolled back) — confirms 409 was a true reject.
        const stillThere = await request.get(`${BASE}/api/productSuppliers/${supplierId}`, { headers });
        expect(stillThere.status()).toBe(200);

        // Sever the child FK web in the DB (mirrors what a cascading delete / the Grails flow would do):
        // BOTH product_package AND product_supplier_preference reference product_supplier, so both must go
        // before the bare API DELETE can succeed. Then prove the API DELETE returns 204 and the row is
        // gone (404) — the clean-delete half of the contract, once no child references the supplier.
        sql(
            `UPDATE product_supplier SET default_product_package_id=NULL WHERE id='${supplierId}';` +
            `DELETE FROM product_supplier_preference WHERE product_supplier_id='${supplierId}';` +
            `DELETE FROM product_package WHERE product_supplier_id='${supplierId}';`,
        );
        const delCleanRes = await request.delete(`${BASE}/api/productSuppliers/${supplierId}`, { headers });
        expect(delCleanRes.status(), await delCleanRes.text()).toBe(204);
        const gone = await request.get(`${BASE}/api/productSuppliers/${supplierId}`, { headers });
        expect(gone.status()).toBe(404);
    });
});
