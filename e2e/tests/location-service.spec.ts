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

test.describe('location-service via nginx', () => {
    test('GET /api/location/{id} returns 200 with valid obx_token', async ({ request }) => {
        const cookie = await login(request);
        // Fetch a real location ID (e.g., via /api/identity/me or /api/locations)
        const meRes = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        const me = await meRes.json();
        const locId = me.location?.id || process.env.E2E_LOCATION_ID || '1';
        const res = await request.get(`${BASE}/api/location/${locId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.id).toBe(locId);
        expect(body).toHaveProperty('locationTypeCode');
    });

    test('GET /api/location/type returns reference data', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/location/type`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const types = await res.json();
        expect(Array.isArray(types)).toBeTruthy();
        expect(types.length).toBeGreaterThan(0);
    });

    test('regression: /api/locations/{id} (plural) still routes to Grails', async ({ request }) => {
        const cookie = await login(request);
        const meRes = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        const me = await meRes.json();
        const locId = me.location?.id || process.env.E2E_LOCATION_ID || '1';
        const res = await request.get(`${BASE}/api/locations/${locId}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        // Grails returns {data: {...}} wrapper; location-service returns flat
        expect(body.data).toBeTruthy();
    });

    test('regression: /api/internalLocations/* still works via Grails', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/internalLocations`, { headers: { Cookie: cookie } });
        // Either 200 or method-specific status; not 502 (would mean nginx misroute)
        expect(res.status()).not.toBe(502);
    });

    test('regression: existing Phase 1+2 baseline (login + /api/identity/me) unchanged', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/identity/me`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });
});
