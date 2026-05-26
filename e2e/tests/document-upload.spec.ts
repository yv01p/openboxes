import { test, expect } from '@playwright/test';

// Per-test login (matches existing api-auth.spec.ts / react-nav.spec.ts pattern — no shared
// cookie fixture). Playwright's `request` fixture preserves cookies within a single test.
test('POST /api/documents uploads a multipart file and returns 201', async ({ request }) => {
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
      file: { name: 'test.txt', mimeType: 'text/plain', buffer: Buffer.from('hello phase 1') },
      name: 'phase-1-upload-test',
    },
  });
  // DocumentController.create() returns 201 Created + Location header (Task 5.5).
  expect(upload.status()).toBe(201);
  expect(upload.headers()['location']).toMatch(/\/api\/documents\/[a-f0-9]{32}$/);
  const doc = await upload.json();
  expect(doc.id).toBeTruthy();
  expect(doc.name).toBe('phase-1-upload-test');
  expect(doc.filename).toBe('test.txt');
  expect(doc.contentType).toContain('text/plain');
  // size is the derived getter on Document; fileContents/version are @JsonIgnore.
  expect(doc.size).toBe(13);
  expect(doc).not.toHaveProperty('fileContents');
  expect(doc).not.toHaveProperty('version');
});
