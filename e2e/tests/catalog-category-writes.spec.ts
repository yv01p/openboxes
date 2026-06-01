import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

// T12: Category full CRUD via catalog-service through nginx. This e2e is the production-schema proof —
// it hits the REAL openboxes-db, so it catches the @Version finding (a missing @Version mapping would
// fail the POST with "Field 'version' doesn't have a default value"; the integration test
// (ddl-auto=create) would NOT catch that). Self-cleans (DELETEs what it POSTs).
test.describe('catalog-service /api/category (T12 full CRUD)', () => {
    test('GET /api/category returns list shape', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/category`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('POST creates (proves @Version write path) + GET back, then DELETE removes', async ({ request }) => {
        const cookie = await login(request);

        const name = `E2E Category ${Date.now()}`;
        // POST — the real proof the @Version + timestamp-audit mapping works against the production schema.
        const createRes = await request.post(`${BASE}/api/category`, {
            headers: { Cookie: cookie },
            data: { name },
        });
        expect(createRes.status()).toBe(200);
        const created = (await createRes.json()).data;
        expect(typeof created.id).toBe('string');
        expect(created.name).toBe(name);
        const id = created.id;

        // GET by id — confirm the row persisted.
        const getRes = await request.get(`${BASE}/api/category/${id}`, { headers: { Cookie: cookie } });
        expect(getRes.status()).toBe(200);
        const fetched = (await getRes.json()).data;
        expect(fetched.name).toBe(name);
        // FD#3 flat DTO: no nested entities.
        expect(fetched.parentCategory).toBeUndefined();
        expect(fetched.categories).toBeUndefined();

        // DELETE — 204, then GET 404. Self-clean.
        const delRes = await request.delete(`${BASE}/api/category/${id}`, { headers: { Cookie: cookie } });
        expect(delRes.status()).toBe(204);
        const gone = await request.get(`${BASE}/api/category/${id}`, { headers: { Cookie: cookie } });
        expect(gone.status()).toBe(404);
    });
});
