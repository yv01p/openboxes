import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

// Task 17 Step 8: caller regression — broad smoke through Grails-served pages that read
// `session.user`. Each must return HTTP 200; the SecurityInterceptor would redirect/500
// without a populated session, so a clean 200 proves the JWT cookie path is wiring
// session.user/warehouse correctly (per SecurityInterceptor.before(), lines 46-58).
//
// Dashboard / ProductList / InvoiceList / ShipmentList are React-shelled controllers
// (full GSP layout suppressed; we only assert <html> root rendered). The /openboxes/user/show
// page is fully GSP-rendered and contains the logged-in user's display name — we assert
// that explicitly to cover the spec's "name in rendered HTML" requirement.

test.describe('identity-service caller regression — Grails session.user wiring', () => {
  test.beforeEach(async ({ request }) => {
    await login(request);
    const chooseRes = await request.put(`/api/identity/chooseLocation/${process.env.E2E_LOCATION_ID || '1'}`);
    expect(chooseRes.status()).toBe(200);
  });

  for (const path of [
    '/openboxes/dashboard/index',
    '/openboxes/product/list',
    '/openboxes/invoice/list',
    '/openboxes/shipment/list',
  ]) {
    test(`GET ${path} renders without session.user errors`, async ({ request }) => {
      const res = await request.get(path);
      expect(res.status()).toBe(200);
      const body = await res.text();
      expect(body).toMatch(/<html/i);
    });
  }

  // Full-GSP page that explicitly renders the user's display name — proves session.user
  // is not just non-null but actually carries the logged-in identity.
  test('GET /openboxes/user/show/{id} renders logged-in user name in HTML', async ({ request }) => {
    const meRes = await request.get('/api/identity/me');
    expect(meRes.status()).toBe(200);
    const { user } = await meRes.json();

    const showRes = await request.get(`/openboxes/user/show/${user.id}`);
    expect(showRes.status()).toBe(200);
    const body = await showRes.text();
    expect(body).toContain(`${user.firstName} ${user.lastName}`);
  });
});
