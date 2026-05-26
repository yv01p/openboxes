import { test, expect } from '@playwright/test';

test('GET /api/documents/{id} returns metadata, GET /{id}/content streams bytes', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
      location: process.env.E2E_LOCATION_ID || '1',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);

  const payload = Buffer.from('download-roundtrip-payload');
  const upload = await request.post('/api/documents', {
    multipart: {
      file: { name: 'download.txt', mimeType: 'text/plain', buffer: payload },
      name: 'phase-1-download-test',
    },
  });
  expect(upload.status()).toBe(201);
  const { id } = await upload.json();

  // Metadata fetch.
  const meta = await request.get(`/api/documents/${id}`);
  expect(meta.status()).toBe(200);
  const metaJson = await meta.json();
  expect(metaJson.id).toBe(id);
  expect(metaJson.name).toBe('phase-1-download-test');

  // Content stream — assert bytes round-trip identically and the Content-Disposition is set.
  const content = await request.get(`/api/documents/${id}/content`);
  expect(content.status()).toBe(200);
  expect(content.headers()['content-type']).toContain('text/plain');
  expect(content.headers()['content-disposition']).toContain('attachment');
  expect(content.headers()['content-disposition']).toContain('download.txt');
  const body = await content.body();
  expect(body.equals(payload)).toBe(true);
});
