import { test, expect } from '@playwright/test';

test('DELETE /api/documents/{id} returns 204 and the row 404s afterward', async ({ request }) => {
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
      file: { name: 'to-delete.txt', mimeType: 'text/plain', buffer: Buffer.from('please remove me') },
      name: 'phase-1-delete-test',
    },
  });
  expect(upload.status()).toBe(201);
  const { id } = await upload.json();

  // First DELETE — 204 No Content per DocumentController.
  const del = await request.delete(`/api/documents/${id}`);
  expect(del.status()).toBe(204);

  // Subsequent GET — DocumentController returns 404 once the row is gone.
  const after = await request.get(`/api/documents/${id}`);
  expect(after.status()).toBe(404);

  // Second DELETE — the existsById guard surfaces EmptyResultDataAccessException,
  // which the controller's @ExceptionHandler maps to 404 (T4-M1).
  const delAgain = await request.delete(`/api/documents/${id}`);
  expect(delAgain.status()).toBe(404);
});
