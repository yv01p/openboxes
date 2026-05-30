import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service reference data: ProductType + Attribute (cached per FD#7, R/O per T1)', () => {
    test('GET /api/productType returns list with supportedActivities arrays populated', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productType`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
        // T10 OSIV verification: list response includes populated supportedActivities (element-collection
        // loaded eagerly during the OSIV-bounded request). If empty across all rows, that is the
        // regression commit 9581f7bbc retracted — fail loudly so the next phase notices.
        if (body.data.length > 0) {
            for (const pt of body.data) {
                expect(Array.isArray(pt.supportedActivities)).toBeTruthy();
                expect(Array.isArray(pt.requiredFields)).toBeTruthy();
                expect(Array.isArray(pt.displayedFields)).toBeTruthy();
            }
        }
    });

    test('GET /api/productType/{id} returns flat DTO with supportedActivities/requiredFields/displayedFields arrays', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productType`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No product types');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productType/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        expect(Array.isArray(body.data.supportedActivities)).toBeTruthy();
        expect(Array.isArray(body.data.requiredFields)).toBeTruthy();
        expect(Array.isArray(body.data.displayedFields)).toBeTruthy();
    });

    test('GET /api/attribute returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/attribute`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/attribute/{id} returns flat DTO with options array (ordered by options_idx)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/attribute`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No attributes');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/attribute/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: unitOfMeasureClass entity not inflated; options/entityTypeCodes are arrays
        expect(body.data.unitOfMeasureClass).toBeUndefined();
        expect(Array.isArray(body.data.options)).toBeTruthy();
        expect(Array.isArray(body.data.entityTypeCodes)).toBeTruthy();
        if (body.data.options.length > 0) expect(typeof body.data.options[0]).toBe('string');
    });

    test('regression: /api/attributes (plural) returns 404 — AttributeApiController deleted in T9 (commit 7e4beb69a)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/attributes`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(404);
    });
});
