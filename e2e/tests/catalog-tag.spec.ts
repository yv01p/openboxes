import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/tag (R/O per T1; no writes — FD#9 not forced)', () => {
    test('GET /api/tag returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/tag`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/tag/{id} returns flat DTO with productIds array (M:N)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/tag`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No tags');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/tag/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: productIds is array of string ids; entity sibling 'products' must not be inflated
        expect(Array.isArray(body.data.productIds)).toBeTruthy();
        if (body.data.productIds.length > 0) expect(typeof body.data.productIds[0]).toBe('string');
        expect(body.data.products).toBeUndefined();
    });
});
