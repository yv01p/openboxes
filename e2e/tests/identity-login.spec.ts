import { test, expect } from '@playwright/test';

// Task 17 Step 2: identity-service login flow.
// React LoginModal posts to /api/identity/login; we hit the endpoint directly via
// Playwright's request context (the cookie set on the POST is auto-propagated to
// subsequent requests in the same context). Asserts:
//   (a) POST returns 200 + sets obx_token cookie
//   (b) GET /api/identity/me with that cookie returns the logged-in user JSON

test('POST /api/identity/login returns 200 + sets obx_token; /api/identity/me returns user JSON', async ({ request }) => {
  const loginRes = await request.post('/api/identity/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);
  const setCookies = loginRes.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const hasObxToken = setCookies.some(h => h.value.includes('obx_token='));
  expect(hasObxToken).toBe(true);

  const meRes = await request.get('/api/identity/me');
  expect(meRes.status()).toBe(200);
  const body = await meRes.json();
  expect(body.user).toBeTruthy();
  expect(body.user.username).toBe(process.env.E2E_USER || 'admin');
});
