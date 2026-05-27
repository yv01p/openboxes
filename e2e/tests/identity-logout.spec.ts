import { test, expect } from '@playwright/test';

// Task 17 Step 3: identity-service logout flow.
// Login, then POST /api/identity/logout; assert the response sets an obx_token cookie
// with Max-Age=0 (cleared), and subsequent GET /api/identity/me returns 401.

test('POST /api/identity/logout clears obx_token + /api/identity/me returns 401', async ({ request }) => {
  const loginRes = await request.post('/api/identity/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);

  const logoutRes = await request.post('/api/identity/logout');
  expect(logoutRes.status()).toBe(200);
  // The clearing cookie has Max-Age=0; that's how Playwright's request context drops it.
  const setCookies = logoutRes.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const clearsObxToken = setCookies.some(h => h.value.includes('obx_token=') && h.value.includes('Max-Age=0'));
  expect(clearsObxToken).toBe(true);

  const meRes = await request.get('/api/identity/me');
  expect(meRes.status()).toBe(401);
});
