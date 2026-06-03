-- RC-16 (Phase 6) e2e fixture: seed abc_class data so the product-classifications read-through returns a
-- NON-EMPTY union (the dev/CI DB is otherwise empty of product/inventory_level rows). Applied after
-- init-baseline.sql in the e2e CI job (and locally via `docker compose exec -T db mariadb ... < this`).
--
-- Behavior under test (matches Grails ProductClassificationService.list + the inventory-service port):
--   result(facility) = sorted, deduped, non-empty union of
--       global  Product.abc_class               (served by catalog-service /api/products/abcClasses)
--     ∪ facility InventoryLevel.abc_class        (inventory_level where inventory_id = location.inventory_id)
--
-- Fixture is SORT-DISTINGUISHING (mirrors the T6 InventoryServiceIntegrationTest):
--   global products -> {B, D};  facility-1 inventory_levels -> {A, D, ''};  other-inventory -> {Z}.
--   => facility 1 (Main Warehouse, location id '1', inventory_id '1') yields the sorted union [A, B, D]
--      ('' filtered out; 'D' overlaps global to prove dedup; 'Z' is on inventory '2' which no facility
--      points at, proving facility-scoping). [A,B,D] differs from any insertion order (catalog-first
--      [B,D,A]) so it also proves the alphabetical sort, not just membership.
--
-- Idempotent: all rows carry the 'rc16-' id prefix and are deleted first, so re-applying is safe.

DELETE FROM inventory_level WHERE id LIKE 'rc16-%';
DELETE FROM product WHERE id LIKE 'rc16-%';

-- Global abc classes (catalog-service reads DISTINCT product.abc_class). Minimal product row: the only
-- NOT-NULL-without-default columns are id, version, name, date_created, last_updated (verified via
-- information_schema against the live Grails schema).
INSERT INTO product (id, version, name, abc_class, date_created, last_updated) VALUES
    ('rc16-prod-B', 0, 'RC16 Global Product B', 'B', NOW(), NOW()),
    ('rc16-prod-D', 0, 'RC16 Global Product D', 'D', NOW(), NOW());

-- Facility-scoped abc classes. inventory_id '1' is Main Warehouse's inventory (location id '1'); inventory
-- '2' has no location pointing at it, so its 'Z' must NOT appear in facility 1's result (scoping proof).
-- '' must be filtered by the <> '' guard. Minimal inventory_level row: NOT-NULL-without-default = id, version.
-- PRECONDITION: inventory rows '1' and '2' (+ location '1' "Main Warehouse" inventory_id='1') are created by
-- the Grails BootStrap demo data (install/changelog-insert-data.groovy), so the inventory_level.inventory_id
-- FK resolves on a fresh CI DB. If that demo dataset ever changes, this INSERT FK-fails loudly (intended).
INSERT INTO inventory_level (id, version, inventory_id, abc_class) VALUES
    ('rc16-il-F1-A',     0, '1', 'A'),
    ('rc16-il-F1-D',     0, '1', 'D'),
    ('rc16-il-F1-empty', 0, '1', ''),
    ('rc16-il-other-Z',  0, '2', 'Z');
