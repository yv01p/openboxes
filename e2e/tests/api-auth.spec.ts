import { test, expect } from '@playwright/test';

test('Authenticated API call returns 200 (cookie-based auth)', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const response = await request.get('/openboxes/api/users');
  expect(response.status()).toBe(200);
});
