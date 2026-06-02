import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

// CUT (frontend cutover) API-shape proof. After the nginx repoint, /api/productSuppliers is served
// by catalog-service with the FLAT LQ2 list contract (per the C3 lesson: assert real payload shapes,
// not assumptions). The live openboxes-db may be empty, so these assert shape + routing (which hold
// even with zero rows) and skip the data-dependent by-id check when there are no product suppliers.
test.describe('catalog-service /api/productSuppliers (CUT flat-contract cutover)', () => {
    test('GET /api/productSuppliers returns the catalog flat LQ2 list shape', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productSuppliers`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();

        // LQ2 envelope: { data: [...], totalCount }. totalCount being a number proves the cutover
        // routing reached catalog-service's list endpoint (even with an empty DB).
        expect(Array.isArray(body.data)).toBeTruthy();
        expect(typeof body.totalCount).toBe('number');

        // If any rows exist, assert the FLAT LQ2 row shape (flat fields present, NO nested objects).
        if (body.data.length > 0) {
            const row = body.data[0];
            // Flat fields the list table now reads directly.
            expect(row).toHaveProperty('productCode');
            expect(row).toHaveProperty('productName');
            expect(row).toHaveProperty('supplierName');
            expect(row).toHaveProperty('packageSize');
            expect(row).toHaveProperty('packagePrice');
            expect(row).toHaveProperty('unitPrice');
            // preferences is the flat ref array the PreferenceTypeColumn consumes.
            expect(Array.isArray(row.preferences)).toBeTruthy();
            // FD#3 flatness: no nested product/supplier objects.
            expect(row.product).toBeUndefined();
            expect(row.supplier).toBeUndefined();
            expect(row.productSupplierPreferences).toBeUndefined();
        }
    });

    test('GET /api/productSuppliers/export is NOT a 404 (Rule-3 exemption -> Grails)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productSuppliers/export`, { headers: { Cookie: cookie } });
        // catalog-service has no /export — the exact-match nginx block proxies this to Grails. The point
        // is the catalog prefix block did NOT swallow it into a 404.
        expect(res.status()).not.toBe(404);
    });

    test('GET /api/productSuppliers/{id} returns the flat by-id contract (no nested objects)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productSuppliers`, { headers: { Cookie: cookie } });
        expect(listRes.status()).toBe(200);
        const list = await listRes.json();
        if (!Array.isArray(list.data) || list.data.length === 0) {
            test.skip(true, 'No product suppliers in DB — cannot exercise the flat by-id contract');
        }

        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productSuppliers/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const dto = (await res.json()).data;

        // Flat FK ids + read-only label fields are populated on by-id (so the edit path needs no extra
        // product/supplier/manufacturer label fetch).
        expect(dto).toHaveProperty('productId');
        expect(dto).toHaveProperty('productName');
        expect(dto).toHaveProperty('supplierName');
        // FD#3 flatness: NO nested entity objects.
        expect(dto.product).toBeUndefined();
        expect(dto.supplier).toBeUndefined();
        expect(dto.manufacturer).toBeUndefined();
        expect(dto.defaultProductPackage).toBeUndefined();
        expect(dto.contractPrice).toBeUndefined();
        expect(dto.productSupplierPreferences).toBeUndefined();
    });
});
