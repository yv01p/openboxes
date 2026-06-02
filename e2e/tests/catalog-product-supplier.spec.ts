import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

const BASE = process.env.BASE_URL ?? 'http://localhost';

// T2: ProductSupplier full CRUD via catalog-service through nginx. This e2e is the production-schema
// proof — it hits the REAL openboxes-db, so it catches the @Version finding (a missing @Version mapping
// would fail the POST with "Field 'version' doesn't have a default value"; the integration test
// (ddl-auto=create) would NOT catch that).
test.describe('catalog-service /api/productSuppliers (T2 full CRUD)', () => {
    test('GET /api/productSuppliers returns list shape', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productSuppliers`, { headers: { Cookie: cookie } });
        expect(res.status()).toBe(200);
        const body = await res.json();
        expect(Array.isArray(body.data)).toBeTruthy();
    });

    test('POST creates (proves @Version write path), PUT updates, DELETE removes', async ({ request }) => { // Un-parked at CUT: /api/productSuppliers now routes to catalog-service (self-skips when the DB has no products).
        const cookie = await login(request);

        // ProductSupplier POST needs a real product_id FK. Per suite convention (cf.
        // catalog-product-readonly.spec.ts:19), the e2e DB may be empty — skip the write path when no
        // products exist rather than false-fail. When products ARE present (populated/real env), this
        // fully exercises the @Version write path against the production schema.
        const prodRes = await request.get(`${BASE}/api/product`, { headers: { Cookie: cookie } });
        expect(prodRes.status()).toBe(200);
        const prodBody = await prodRes.json();
        // NOTE: dead while this test is parked; re-activates when CUT un-parks the test — guards against empty-DB false-fail.
        if (!Array.isArray(prodBody.data) || prodBody.data.length === 0) {
            test.skip(true, 'No products in DB — cannot exercise ProductSupplier write path (needs a product_id FK)');
        }
        const productId = prodBody.data[0].id;

        // POST — this is the real proof the @Version mapping works against the production schema.
        const createRes = await request.post(`${BASE}/api/productSuppliers`, {
            headers: { Cookie: cookie },
            data: { name: 'E2E Supplier', productId, code: 'PS-E2E-CRUD' },
        });
        expect(createRes.status()).toBe(200);
        const created = (await createRes.json()).data;
        expect(typeof created.id).toBe('string');
        expect(created.name).toBe('E2E Supplier');
        expect(created.productId).toBe(productId);
        // FD#8 audit: created_by_id populated from the JWT subject (the logged-in user id).
        // e2e logs in as a real user, so this is always a non-null string (the rigorous
        // "== test-user" proof lives in the integration test against a known JWT subject).
        expect(typeof created.createdById).toBe('string');
        const id = created.id;

        // PUT — update the row.
        const updateRes = await request.put(`${BASE}/api/productSuppliers/${id}`, {
            headers: { Cookie: cookie },
            data: { name: 'E2E Supplier Updated', productId, active: false },
        });
        expect(updateRes.status()).toBe(200);
        expect((await updateRes.json()).data.name).toBe('E2E Supplier Updated');

        // GET by id — confirm the update persisted.
        const getRes = await request.get(`${BASE}/api/productSuppliers/${id}`, { headers: { Cookie: cookie } });
        expect(getRes.status()).toBe(200);
        const fetched = (await getRes.json()).data;
        expect(fetched.name).toBe('E2E Supplier Updated');
        // FD#3 flat DTO: no nested entities.
        expect(fetched.product).toBeUndefined();
        expect(fetched.supplier).toBeUndefined();

        // DELETE — 204, then GET 404.
        const delRes = await request.delete(`${BASE}/api/productSuppliers/${id}`, { headers: { Cookie: cookie } });
        expect(delRes.status()).toBe(204);
        const gone = await request.get(`${BASE}/api/productSuppliers/${id}`, { headers: { Cookie: cookie } });
        expect(gone.status()).toBe(404);
    });

    test('GET /api/productSuppliers/export stays on Grails (Rule-3 exemption)', async ({ request }) => { // Un-parked at CUT: catalog now owns /api/productSuppliers, /export is the Rule-3 exemption back to Grails.
        const cookie = await login(request);
        const res = await request.get(`${BASE}/api/productSuppliers/export`, { headers: { Cookie: cookie } });
        // The point is the route is NOT served by catalog-service (which has no /export). It is proxied
        // to Grails; be tolerant of whatever Grails returns (export typically 200 with a file/CSV body),
        // just assert it is not a 404 from catalog-service's prefix block swallowing it.
        expect(res.status()).not.toBe(404);
    });
});
