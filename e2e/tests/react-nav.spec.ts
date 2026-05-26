import { test, expect } from '@playwright/test';

test('React-hosted route /openboxes/stockMovement/list is reachable after login', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
      location: process.env.E2E_LOCATION_ID || '1',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const navRes = await request.get('/openboxes/stockMovement/list?direction=INBOUND');
  expect(navRes.status()).toBe(200);
  expect(navRes.url()).toContain('/stockMovement/list');
});
