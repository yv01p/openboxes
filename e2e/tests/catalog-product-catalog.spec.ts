import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/productCatalogs (R/O per T1; zero React callers, GET-only per FD#1)', () => {
    test('GET /api/productCatalogs returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productCatalogs`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/productCatalogs/{id} returns flat DTO (no nested collection inflated)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productCatalogs`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No product catalogs');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productCatalogs/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // T7 forward-decl: the productCatalogItems inverse collection is not mapped/inflated.
        expect(body.data.productCatalogItems).toBeUndefined();
    });
});

test.describe('catalog-service /api/productCatalogItems (R/O per T1; zero React callers, GET-only per FD#1)', () => {
    test('GET /api/productCatalogItems returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productCatalogItems`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/productCatalogItems/{id} returns flat DTO (no nested FK entities)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productCatalogItems`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No product catalog items');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productCatalogItems/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#2/FD#3: flat ids only — no nested FK entities inflated.
        expect(body.data.product).toBeUndefined();
        expect(body.data.productCatalog).toBeUndefined();
    });
});
