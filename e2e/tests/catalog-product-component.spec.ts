import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/productComponents (R/O per T1; GET-only)', () => {
    test('GET /api/productComponents returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productComponents`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/productComponents/{id} returns flat DTO', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/productComponents`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No product components');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/productComponents/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#2/FD#3: flat FK id strings only — entity siblings must not be inflated.
        expect(body.data.assemblyProduct).toBeUndefined();
        expect(body.data.componentProduct).toBeUndefined();
    });
});
