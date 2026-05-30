import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

// Post-T9 (commit 7e4beb69a) + T9-follow-up (commit c505e5842) sanity check.
// Option endpoints + /api/unitOfMeasure/currencies STAY on Grails (not migrated to catalog-service).
// Each MUST return 200 against the shared obx_token cookie (Grails auth filter accepts JWT and JSESSIONID).
// If any returns 404 or 502, T9's React URL migration silently broke a dropdown OR the T9-follow-up
// nginx exact-match regressed — this spec is the canary.
test.describe('catalog options endpoints — Grails-served regression (post-T9 sanity)', () => {
    test('GET /api/categoryOptions returns 200 (Grails SelectOptionsApiController)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/categoryOptions`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });

    test('GET /api/tagOptions returns 200 (Grails)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/tagOptions`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });

    test('GET /api/productGroupOptions returns 200 (Grails)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productGroupOptions`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });

    test('GET /api/catalogOptions returns 200 (Grails per T1 §3.2)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/catalogOptions`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });

    test('GET /api/unitOfMeasure/currencies returns 200 (Grails UoMApiController.currencies via nginx exact-match per T9-follow-up)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/unitOfMeasure/currencies`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
    });
});
