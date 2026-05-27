import { test, expect } from '@playwright/test';

// Task 17 Step 5: Grails AuthController.handleLogin form-encoded POST shim regression.
// Proves the Grails shim controller (T13) still accepts form-encoded login, forwards
// to identity-service, sets the obx_token cookie, and a subsequent Grails-served page
// (dashboard) renders cleanly under the resulting session.
//
// The shim returns 302 → /openboxes/. Playwright follows redirects and we then hit
// /openboxes/dashboard/index explicitly to assert the cookie carries downstream.

test('POST /openboxes/auth/handleLogin sets obx_token; /openboxes/dashboard/index returns 200', async ({ request }) => {
  const loginRes = await request.post('/openboxes/auth/handleLogin', {
    form: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    maxRedirects: 0,
  });
  // 302 redirect to dashboard is the success path.
  expect(loginRes.status()).toBe(302);
  const setCookies = loginRes.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const hasObxToken = setCookies.some(h => h.value.includes('obx_token='));
  expect(hasObxToken).toBe(true);

  const dashRes = await request.get('/openboxes/dashboard/index');
  expect(dashRes.status()).toBe(200);
  const body = await dashRes.text();
  expect(body).toMatch(/<html/i);
});
