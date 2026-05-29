import { APIRequestContext, expect } from '@playwright/test';

const BASE = process.env.BASE_URL ?? 'http://localhost';
const USER = process.env.E2E_USER ?? 'admin';
const PASS = process.env.E2E_PASSWORD ?? 'password';

/**
 * Authenticate the standard E2E admin user against /api/identity/login
 * and return the set-cookie header for subsequent requests.
 *
 * Uses E2E_USER / E2E_PASSWORD env vars (defaults: admin / password).
 * For specs that need non-default credentials (e.g. post-password-reset
 * login), POST to /api/identity/login inline rather than using this helper.
 */
export async function login(request: APIRequestContext): Promise<string | string[]> {
    const res = await request.post(`${BASE}/api/identity/login`, {
        data: { username: USER, password: PASS },
    });
    expect(res.ok()).toBeTruthy();
    return res.headers()['set-cookie'];
}
