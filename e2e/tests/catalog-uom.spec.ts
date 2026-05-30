import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/unitOfMeasure + /api/unitOfMeasureClass (R/O per T1)', () => {
    test('GET /api/unitOfMeasure returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/unitOfMeasure`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/unitOfMeasure/{id} returns flat DTO with uomClassId FK', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/unitOfMeasure`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No units of measure');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/unitOfMeasure/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: uomClassId is FK string (or null); no nested uomClass entity
        expect(body.data.uomClass).toBeUndefined();
        const uomClassId = body.data.uomClassId;
        expect(uomClassId === null || uomClassId === undefined || typeof uomClassId === 'string').toBeTruthy();
    });

    test('GET /api/unitOfMeasureClass returns list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/unitOfMeasureClass`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/unitOfMeasureClass/{id} returns flat DTO with baseUomId FK (sibling of UoM)', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/unitOfMeasureClass`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No UoM classes');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/unitOfMeasureClass/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3: baseUomId is FK string (or null); no nested baseUom entity or uoms collection
        expect(body.data.baseUom).toBeUndefined();
        expect(body.data.uoms).toBeUndefined();
        const baseUomId = body.data.baseUomId;
        expect(baseUomId === null || baseUomId === undefined || typeof baseUomId === 'string').toBeTruthy();
    });
});
