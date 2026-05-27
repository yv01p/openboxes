import { test, expect } from '@playwright/test';

// Task 17 Step 4: identity-service chooseLocation flow.
// Login without a location (admin's initial token has no `loc` claim), then
// PUT /api/identity/chooseLocation/{id} and assert the new obx_token JWT payload
// carries a `loc` claim matching the chosen location id.
//
// We base64-decode the JWT's middle segment (Set-Cookie value) WITHOUT verifying the
// signature — we only need to read the payload claim. Signature verification belongs
// to identity-service's own tests.

test('PUT /api/identity/chooseLocation/{id} issues new obx_token with loc claim', async ({ request }) => {
  const loginRes = await request.post('/api/identity/login', {
    data: {
      username: process.env.E2E_USER || 'admin',
      password: process.env.E2E_PASSWORD || 'password',
    },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(loginRes.status()).toBe(200);

  const locationId = process.env.E2E_LOCATION_ID || '1';
  const chooseRes = await request.put(`/api/identity/chooseLocation/${locationId}`);
  expect(chooseRes.status()).toBe(200);

  const setCookies = chooseRes.headersArray().filter(h => h.name.toLowerCase() === 'set-cookie');
  const obxCookie = setCookies.find(h => h.value.startsWith('obx_token='));
  expect(obxCookie).toBeTruthy();
  const jwt = obxCookie!.value.split(';')[0].substring('obx_token='.length);
  const payloadB64 = jwt.split('.')[1];
  // JWT uses base64url; Node's Buffer accepts it directly.
  const payload = JSON.parse(Buffer.from(payloadB64, 'base64url').toString('utf8'));
  expect(payload.loc).toBe(locationId);
});
