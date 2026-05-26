import { test, expect } from '@playwright/test';

test('POST /api/login returns 200 and sets obx_token cookie', async ({ request }) => {
  const response = await request.post('/api/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(response.status()).toBe(200);
  const setCookies = response.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const hasObxToken = setCookies.some(h => h.value.includes('obx_token='));
  expect(hasObxToken).toBe(true);
});
