import { test, expect } from '@playwright/test';
import { login } from '../fixtures/auth';

// RC-16 read-through regression guard (RC-43/45). The inventory-service serves
// GET /api/facilities/{id}/products/classifications through nginx (T5 route), returning the
// sorted, deduped, non-empty UNION of global Product.abc_class (via catalog-service) ∪ the
// facility's InventoryLevel.abc_class. The asserted union [A,B,D] simultaneously proves:
//   - global ∪ facility membership (global {B,D} ∪ facility-1 {A,D})
//   - dedup (D overlaps both sides, appears once)
//   - alphabetical SORT (insertion/catalog-first order would be [B,D,A], not [A,B,D])
//   - empty-string filtering ('' is in the fixture but must NOT appear)
//   - facility-scoping ('Z' lives on inventory '2', which no facility points at, so it must NOT appear)
//
// The seed `docker/seed-rc16-abc-class.sql` (applied AFTER init-baseline in CI) is REQUIRED: the
// dev/CI DB is otherwise empty of product/inventory_level rows. A `[]` result means the seed did
// not run — this spec deliberately FAILS LOUDLY (no test.skip) rather than passing a hollow green.
//
// No Grails-direct (:8080) comparison is committed here: the Grails endpoint is deleted in T8, so a
// committed cross-call would break. The Grails ground-truth (byte-identical) was captured during T7
// verification.

const BASE = process.env.BASE_URL ?? 'http://localhost';
const FACILITY = process.env.E2E_LOCATION_ID ?? '1';

test.describe('inventory-service /api/facilities/{id}/products/classifications (RC-16 read-through)', () => {
    test('returns the seeded sorted/deduped union [A,B,D]', async ({ request }) => {
        const cookie = await login(request);
        const res = await request.get(
            `${BASE}/api/facilities/${FACILITY}/products/classifications`,
            { headers: { Cookie: cookie } },
        );
        expect(res.status()).toBe(200);
        const body = await res.json();

        expect(Array.isArray(body.data)).toBeTruthy();
        expect(body.data.every((d: any) => typeof d.name === 'string')).toBeTruthy();

        const names = body.data.map((d: any) => d.name);
        // [A,B,D] proves union + dedup + alphabetical SORT (insertion order would be [B,D,A]).
        expect(names).toEqual(['A', 'B', 'D']);
        // '' is in the fixture but must be filtered out.
        expect(names).not.toContain('');
        // 'Z' is on inventory '2' (no facility points at it) — facility-scoping must exclude it.
        expect(names).not.toContain('Z');
    });

    test('requires authentication (401 without cookie)', async ({ request }) => {
        const res = await request.get(
            `${BASE}/api/facilities/${FACILITY}/products/classifications`,
        );
        expect(res.status()).toBe(401);
    });
});
