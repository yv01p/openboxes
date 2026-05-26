import { test, expect } from '@playwright/test';

test('GSP /openboxes/admin/index loads after GSP-style login', async ({ request }) => {
  await request.post('/openboxes/auth/handleLogin', {
    form: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
  });
  const response = await request.get('/openboxes/admin/index');
  expect(response.status()).toBe(200);
  expect(response.url()).toContain('/admin/index');  // fails loudly if seed admin lacks rememberLastLocation and request was redirected to chooseLocation
});
