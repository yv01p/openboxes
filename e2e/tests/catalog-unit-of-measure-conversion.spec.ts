import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/unitOfMeasureConversions (R/O per T1; GET-only)', () => {
    test('GET /api/unitOfMeasureConversions returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/unitOfMeasureConversions`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/unitOfMeasureConversions/{id} returns flat DTO', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/unitOfMeasureConversions`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No unit of measure conversions');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/unitOfMeasureConversions/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#2/FD#3: flat FK id strings only — entity siblings must not be inflated.
        expect(body.data.fromUnitOfMeasure).toBeUndefined();
        expect(body.data.toUnitOfMeasure).toBeUndefined();
    });
});
