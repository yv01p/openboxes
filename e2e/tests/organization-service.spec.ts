import { test, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost';
const USER = process.env.E2E_USER ?? 'admin';
const PASS = process.env.E2E_PASSWORD ?? 'password';

async function login(request: any) {
    const res = await request.post(`${BASE}/api/identity/login`, {
        data: { username: USER, password: PASS },
    });
    expect(res.ok()).toBeTruthy();
    return res.headers()['set-cookie'];
}

test.describe('organization-service via nginx', () => {
    test('GET /api/organization (list) returns 200 with {data: [...]}', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organization`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data).toBeTruthy();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/organization/{id} returns flat DTO', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/organization`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No organizations in DB');
        const orgId = list.data[0].id;
        const res = await request.get(`${BASE}/api/organization/${orgId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(orgId);
        expect(body.data).toHaveProperty('partyTypeCode');
    });

    test('POST /api/organization creates an organization via AddOrganizationModal flow', async ({ request }) => {
        const cookie = await login(request);
        const name = `E2E-Test-${Date.now()}`;
        const res = await request.post(`${BASE}/api/organization`, {
            headers: { Cookie: cookie, 'Content-Type': 'application/json' },
            data: { name, description: 'E2E test' },
        });
        expect(res.status()).toBe(201);
        const body = await res.json();
        expect(body.data.id).toBeTruthy();
        const listRes = await request.get(`${BASE}/api/organization?q=${encodeURIComponent(name)}`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        expect(list.data.some((o: any) => o.id === body.data.id)).toBeTruthy();
    });

    test('GET /api/organization/partyType returns cached list', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organization/partyType`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.length).toBeGreaterThan(0);
    });

    test('regression: /api/organizations (plural) returns 404 — controller deleted', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/organizations`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(404);
    });

    test('baseline preservation: Phase 1+2+3 endpoints still work', async ({ request }) => {
        const cookie = await login(request);
        const me = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        expect(me.status()).toBe(200);
        const locType = await request.get(`${BASE}/api/location/type`, { headers: { Cookie: cookie } });
        expect(locType.status()).toBe(200);
    });
});
