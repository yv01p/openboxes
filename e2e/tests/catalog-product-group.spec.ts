import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/productGroup (R/O per T1)', () => {
    test('GET /api/productGroup returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productGroup`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/productGroup/{id} returns flat DTO with productIds + siblingIds arrays (M:N per T6 ProductGroupDto)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productGroup`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No product groups');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productGroup/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3 flat: no nested products/siblings/category entities
        expect(body.data.products).toBeUndefined();
        expect(body.data.siblings).toBeUndefined();
        expect(body.data.category).toBeUndefined();
        // FK and sibling id arrays
        const categoryId = body.data.categoryId;
        expect(categoryId === null || categoryId === undefined || typeof categoryId === 'string').toBeTruthy();
        expect(Array.isArray(body.data.productIds)).toBeTruthy();
        expect(Array.isArray(body.data.siblingIds)).toBeTruthy();
    });
});
