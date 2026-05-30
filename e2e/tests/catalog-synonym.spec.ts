import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

test.describe('catalog-service /api/synonym (R/O per T1; FD#10 validator deferred)', () => {
    test('GET /api/synonym returns list (may be empty)', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/synonym`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        // Synonym has no React surface — list may be empty; assert shape only, not count
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('GET /api/synonym/{id} returns flat DTO with productId + locale + synonymTypeCode', async ({ request }) => {
        const cookie = await login(request);
        const listRes = await request.get(`${BASE}/api/synonym`, { headers: { Cookie: cookie } });
        const list = await listRes.json();
        if (list.data.length === 0) test.skip(true, 'No synonyms in DB');
        const id = list.data[0].id;
        const res = await request.get(`${BASE}/api/synonym/${id}`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(body.data.id).toBe(id);
        // FD#3 flat: product entity not inflated; productId is the FK string (or null)
        expect(body.data.product).toBeUndefined();
        const pid = body.data.productId;
        expect(pid === null || pid === undefined || typeof pid === 'string').toBeTruthy();
        // Locale serializes (Hibernate 6 LocaleJavaType + Jackson default) as a string like "fr" or "en_US"
        const locale = body.data.locale;
        expect(locale === null || locale === undefined || typeof locale === 'string').toBeTruthy();
        // synonymTypeCode may be null but if present is a string
        const stc = body.data.synonymTypeCode;
        expect(stc === null || stc === undefined || typeof stc === 'string').toBeTruthy();
    });
});
