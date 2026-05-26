import { test, expect } from '@playwright/test';

// `code` lives on DocumentType (the seeded INVOICE_TEMPLATE type, id below). Documents
// inherit their code via the type association — so we POST with that documentTypeId and
// then assert ?code=INVOICE_TEMPLATE returns the new row.
const INVOICE_TEMPLATE_TYPE_ID = '66762f6c61e34cfd9297ecb0fcee2df2';

test('GET /api/documents?code=INVOICE_TEMPLATE returns docs of that type', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
      location: process.env.E2E_LOCATION_ID || '1',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);

  const upload = await request.post('/api/documents', {
    multipart: {
      file: { name: 'invoice-template.html', mimeType: 'text/html', buffer: Buffer.from('<html>tmpl</html>') },
      name: 'phase-1-list-by-code-test',
      documentTypeId: INVOICE_TEMPLATE_TYPE_ID,
    },
  });
  expect(upload.status()).toBe(201);
  const { id } = await upload.json();

  const list = await request.get('/api/documents?code=INVOICE_TEMPLATE');
  expect(list.status()).toBe(200);
  const docs = await list.json();
  expect(Array.isArray(docs)).toBe(true);
  // At least our row must be in the response; other tests may have added more.
  const ours = docs.find((d: { id: string }) => d.id === id);
  expect(ours).toBeTruthy();
  expect(ours.name).toBe('phase-1-list-by-code-test');
});
