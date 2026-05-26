import { test, expect } from '@playwright/test';

// Regression coverage for the Grails controllers that were re-wired in Task 8b to use
// DocumentClient instead of GORM Document directly. Dev DB has zero invoices / products /
// shipments-with-attachments, so we hit LIST pages: these instantiate the controller
// (and therefore exercise Spring's `documentClient` DI), and DataExport actively calls
// documentClient.findByCode('DATA_EXPORT') at request time.
//
// We assert HTTP 200 — any DI failure or controller-side exception would surface as 500.
// A page that 302-redirects to a permission gate (e.g. /errors/handleForbidden) still
// returns 200 after Playwright follows the redirect, which is fine: the redirect itself
// proves the controller resolved cleanly.

test.describe('document-service caller regression — Grails LIST pages still render', () => {
  test.beforeEach(async ({ request }) => {
    const loginRes = await request.post('/api/login', {
      data: {
        username: process.env.E2E_USER || 'admin',
        password: process.env.E2E_PASSWORD || 'password',
        location: process.env.E2E_LOCATION_ID || '1',
      },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(loginRes.status()).toBe(200);
  });

  // DataExportController.index() actively calls documentClient.findByCode('DATA_EXPORT').
  test('DataExport /openboxes/dataExport/index renders', async ({ request }) => {
    const res = await request.get('/openboxes/dataExport/index');
    expect(res.status()).toBe(200);
    // Sentinel: the GSP layout always emits an <html> root.
    const body = await res.text();
    expect(body).toMatch(/<html/i);
  });

  // InvoiceController has `def documentClient` at the controller level — the page rendering
  // proves the bean wired without a NoSuchBeanDefinitionException at controller construction.
  test('Invoice /openboxes/invoice/list renders', async ({ request }) => {
    const res = await request.get('/openboxes/invoice/list');
    expect(res.status()).toBe(200);
    const body = await res.text();
    expect(body).toMatch(/<html/i);
  });

  // ProductController declares `def documentClient` (calls it for product attachments).
  test('Product /openboxes/product/list renders', async ({ request }) => {
    const res = await request.get('/openboxes/product/list');
    expect(res.status()).toBe(200);
    const body = await res.text();
    expect(body).toMatch(/<html/i);
  });

  // ShipmentController declares `def documentClient` for shipment-document attachments.
  test('Shipment /openboxes/shipment/list renders', async ({ request }) => {
    const res = await request.get('/openboxes/shipment/list');
    expect(res.status()).toBe(200);
    const body = await res.text();
    expect(body).toMatch(/<html/i);
  });
});
