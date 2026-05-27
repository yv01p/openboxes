import { test, expect } from '@playwright/test';
import { execSync } from 'node:child_process';

// Task 17 Step 6: identity-service password-change flow + SHA-1 → BCrypt migration.
//
// Uses a dedicated fixture user (e2e-pwch) so concurrent specs that touch admin's row
// (caller-regression, password-reset) cannot trigger Hibernate optimistic-lock conflicts
// on the User#1 row when Playwright parallelises across workers. The fixture row is
// (re-)seeded with a SHA-1 password and active=1 at test start and restored to SHA-1
// in afterAll so this spec is idempotent across runs.
//
// Test A — Grails GSP shim path:
//   1. Login via /api/identity/login + chooseLocation (JWT now carries `loc` claim,
//      required by the Grails SecurityInterceptor for /openboxes/user/changePassword).
//   2. POST /openboxes/user/changePassword (form-encoded, _action_changePassword=Save)
//      with currentPassword + new strong password + passwordConfirm.
//   3. Login with the new password — expect 200.
//
// Test B — SHA-1 → BCrypt migration verification:
//   1. Re-seed SHA-1 hash directly.
//   2. Login — OpenboxesPasswordEncoder.matches() recognises SHA-1, verifies, and
//      migrates the column to BCrypt in the same transaction.
//   3. Query the row via SQL; assert prefix is `$2a$` / `$2b$` / `$2y$` (BCrypt).
//
// Requires passwordless `sudo docker exec` — verified in the dev env.

const SHA1_PASSWORD_HASH = 'W6ph5Mm5Pz8GgiULbPgzG37mj9g=';  // SHA-1(base64) of "password"
const USER = 'e2e-pwch';
const USER_ID = 'e2e-pwch-user';
const PWD = 'password';
const NEW_PWD = 'NewPass1!';

function dbExec(sql: string): string {
  return execSync(
    `sudo -n docker exec openboxes-db mariadb -u root -proot openboxes -N -e ${JSON.stringify(sql)}`,
    { encoding: 'utf8' }
  ).trim();
}

function seedFixtureUser(): void {
  // Idempotent: INSERT IGNORE re-creates the rows if the DB was rebuilt. Then reset password
  // + active to a clean baseline so this spec is repeatable.
  dbExec(
    `INSERT IGNORE INTO person (id, version, date_created, last_updated, email, first_name, last_name, active) ` +
    `VALUES ('${USER_ID}','0',NOW(),NOW(),'e2e-pwch@e2e.test','E2E','ChangeUser',1);` +
    `INSERT IGNORE INTO user (id, username, password) VALUES ('${USER_ID}','${USER}','${SHA1_PASSWORD_HASH}');` +
    `INSERT IGNORE INTO location_role (id, version, user_id, location_id, role_id, location_roles_idx) ` +
    `VALUES ('e2e-pwch-loc','0','${USER_ID}','1','1',0);` +
    `UPDATE person SET active=1 WHERE id='${USER_ID}';` +
    `UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`
  );
}

test.describe.serial('identity-service password change', () => {
  test.beforeAll(() => seedFixtureUser());
  test.afterAll(() => {
    dbExec(`UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`);
  });

  test('GSP changePassword form + login with new password succeeds', async ({ request }) => {
    dbExec(`UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`);

    const loginRes = await request.post('/api/identity/login', {
      data: { username: USER, password: PWD },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(loginRes.status()).toBe(200);
    const chooseRes = await request.put(`/api/identity/chooseLocation/${process.env.E2E_LOCATION_ID || '1'}`);
    expect(chooseRes.status()).toBe(200);

    const changeRes = await request.post('/openboxes/user/changePassword', {
      form: {
        id: USER_ID,
        currentPassword: PWD,
        password: NEW_PWD,
        passwordConfirm: NEW_PWD,
        _action_changePassword: 'Save',
      },
    });
    // Grails redirects on success — Playwright follows; landing page returns 200.
    expect(changeRes.status()).toBe(200);

    const loginNew = await request.post('/api/identity/login', {
      data: { username: USER, password: NEW_PWD },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(loginNew.status()).toBe(200);
  });

  test('SHA-1 row is migrated to BCrypt on successful login', async ({ request }) => {
    dbExec(`UPDATE user SET password='${SHA1_PASSWORD_HASH}' WHERE username='${USER}'`);
    expect(dbExec(`SELECT password FROM user WHERE username='${USER}'`)).toBe(SHA1_PASSWORD_HASH);

    const loginRes = await request.post('/api/identity/login', {
      data: { username: USER, password: PWD },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(loginRes.status()).toBe(200);

    const afterHash = dbExec(`SELECT password FROM user WHERE username='${USER}'`);
    expect(
      afterHash.startsWith('$2a$') || afterHash.startsWith('$2b$') || afterHash.startsWith('$2y$')
    ).toBe(true);
  });
});
