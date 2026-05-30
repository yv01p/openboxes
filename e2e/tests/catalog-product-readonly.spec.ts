import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/product (R/O per FD#1)', () => {
    test('GET /api/product returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/product/{id} returns flat DTO with tagIds shape (FD#3)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No products');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/product/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: flat DTO — tagIds is an array of strings, not nested objects
        expect(Array.isArray(body.data.tagIds)).toBeTruthy();
        if (body.data.tagIds.length > 0) expect(typeof body.data.tagIds[0]).toBe('string');
        // FD#3 flatness: no inflated nested entity bags
        expect(body.data.productType).toBeUndefined();
        expect(body.data.category).toBeUndefined();
        expect(body.data.tags).toBeUndefined();
        expect(body.data.synonyms).toBeUndefined();
        expect(body.data.productGroups).toBeUndefined();
    });

    test('GET /api/product/{id} exposes flat FK ids + sibling id arrays', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No products');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/product/${id}`, { headers: { Cookie: cookie } });
        const body = await res.json();
        // FD#3 flat FK strings (may be null but never nested objects)
        for (const fk of ['productTypeId', 'categoryId', 'unitOfMeasureId', 'productFamilyId']) {
            const v = body.data[fk];
            expect(v === null || typeof v === 'string').toBeTruthy();
        }
        // sibling id arrays (may be empty but always arrays)
        for (const arr of ['tagIds', 'synonymIds', 'productGroupIds']) {
            expect(Array.isArray(body.data[arr])).toBeTruthy();
        }
    });

    test('smoke: Phase 2 /api/identity/me + Phase 3 /api/location/type + Phase 4 /api/organization still respond 200', async ({ request }) => {
        const cookie = await login(request);
        const me = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        expect(me.status()).toBe(200);
        const locType = await request.get(`${BASE}/api/location/type`, { headers: { Cookie: cookie } });
        expect(locType.status()).toBe(200);
        const org = await request.get(`${BASE}/api/organization`, { headers: { Cookie: cookie } });
        expect(org.status()).toBe(200);
    });
});
