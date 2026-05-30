import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/category (R/O per T1)', () => {
    test('GET /api/category returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/category`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/category/{id} returns flat DTO with parentCategoryId (self-FK per T6)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/category`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No categories');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/category/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: parentCategoryId is null (root) or a string (FK), never a nested object
        const parent = body.data.parentCategoryId;
        expect(parent === null || parent === undefined || typeof parent === 'string').toBeTruthy();
        // FD#3 flatness: no nested parentCategory entity
        expect(body.data.parentCategory).toBeUndefined();
    });
});
