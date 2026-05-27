import { test, expect } from '@playwright/test';
import { execSync } from 'node:child_process';

// Task 17 Step 7: identity-service password-reset flow.
//
// Uses a dedicated fixture user (e2e-reset) so concurrent password-change spec cannot
// race with this one on the SAME admin row (Hibernate optimistic-lock collision).
//
// Flow:
//   1. POST /openboxes/auth/forgotPassword with the fixture user's email — Grails shim
//      forwards to identity-service which creates a password_reset_token row (mail is
//      delivered to a no-op JavaMailSender in dev/test).
//   2. Query password_reset_token via SQL for the newest token for our user.
//   3. POST /openboxes/auth/resetPassword with the token + a complex newPassword.
//   4. Verify login with the new password returns 200.
//   5. Restore the fixture row to its SHA-1 baseline so the spec is idempotent.
//
// Requires passwordless `sudo docker exec` — verified in the dev env.
// We use maxRedirects:0 on the form POSTs because Grails redirects them to /auth/login
// (a GET-only action) — auto-following with POST yields a spurious 405.

const SHA1_PASSWORD_HASH = 'W6ph5Mm5Pz8GgiULbPgzG37mj9g=';  // SHA-1(base64) of "password"
const USER = 'e2e-reset';
const USER_ID = 'e2e-reset-user';
const EMAIL = 'e2e-reset@e2e.test';
const NEW_PWD = 'ResetPass1!';

function dbExec(sql: string): string {
  return execSync(
    `sudo -n docker exec openboxes-db mariadb -u root -proot openboxes -N -e ${JSON.stringify(sql)}`,
    { encoding: 'utf8' }
  ).trim();
}

function seedFixtureUser(): void {
  dbExec(
    `INSERT IGNORE INTO person (id, version, date_created, last_updated, email, first_name, last_name, active) ` +
    `VALUES ('${USER_ID}','0',NOW(),NOW(),'${EMAIL}','E2E','ResetUser',1);` +
    `INSERT IGNORE INTO user (id, username, password) VALUES ('${USER_ID}','${USER}','${SHA1_PASSWORD_HASH}');` +
    `UPDATE person SET active=1, email='${EMAIL}' WHERE id='${USER_ID}';` +
    `UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`
  );
}

test.describe.serial('identity-service password reset', () => {
  test.beforeAll(() => seedFixtureUser());
  // beforeEach (not afterAll) so each test starts from a known SHA-1 baseline even after
  // worker crashes or --repeat-each cycles that bypass afterAll.
  test.beforeEach(() => {
    dbExec(`UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`);
  });

  test('forgotPassword issues token; resetPassword changes password; new-pwd login succeeds', async ({ request }) => {
    const requestRes = await request.post('/openboxes/auth/forgotPassword', {
      form: { email: EMAIL },
      maxRedirects: 0,
    });
    // Grails returns 302 → /auth/login (with flash message); 200 (in-page render) also OK.
    expect([200, 302]).toContain(requestRes.status());

    const token = dbExec(
      `SELECT token FROM password_reset_token WHERE user_id='${USER_ID}' ` +
      `AND used_at IS NULL ORDER BY created_at DESC LIMIT 1`
    );
    expect(token).toBeTruthy();
    expect(token.length).toBeGreaterThan(20);

    // T15 GSP coverage: reset form renders for a valid token.
    const formRes = await request.get(`/openboxes/auth/resetPassword?token=${token}`);
    expect(formRes.status()).toBe(200);
    expect(await formRes.text()).toMatch(/<html/i);

    const resetRes = await request.post('/openboxes/auth/resetPassword', {
      form: { token, newPassword: NEW_PWD },
      maxRedirects: 0,
    });
    expect([200, 302]).toContain(resetRes.status());

    const loginRes = await request.post('/api/identity/login', {
      data: { username: USER, password: NEW_PWD },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(loginRes.status()).toBe(200);
  });
});
