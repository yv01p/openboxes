import { test, expect } from '@playwright/test';

test('React-hosted route /openboxes/invoice/list is reachable after login', async ({ request }) => {
  const loginRes = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
      location: process.env.E2E_LOCATION_ID,
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const navRes = await request.get('/openboxes/invoice/list');
  expect(navRes.status()).toBe(200);
  expect(navRes.url()).toContain('/invoice/list');  // catches silent redirect to chooseLocation when warehouse unset
});
