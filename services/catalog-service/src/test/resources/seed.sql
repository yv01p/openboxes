-- 2 UnitOfMeasureClass (mass, count)
INSERT INTO unit_of_measure_class (id, name, code, type, active) VALUES
    ('uomc-mass', 'Mass', 'M', 'METRIC', 1),
    ('uomc-count', 'Count', 'C', 'METRIC', 1);

-- 4 UnitOfMeasure (kg, g, pc, dozen) + bidirectional base_uom
INSERT INTO unit_of_measure (id, name, code, uom_class_id) VALUES
    ('uom-kg', 'Kilogram', 'kg', 'uomc-mass'),
    ('uom-g', 'Gram', 'g', 'uomc-mass'),
    ('uom-pc', 'Piece', 'pc', 'uomc-count'),
    ('uom-dozen', 'Dozen', 'dz', 'uomc-count');
UPDATE unit_of_measure_class SET base_uom_id = 'uom-kg' WHERE id = 'uomc-mass';
UPDATE unit_of_measure_class SET base_uom_id = 'uom-pc' WHERE id = 'uomc-count';

-- Category tree: root + 2 children
INSERT INTO category (id, name, sort_order, is_root) VALUES
    ('cat-root', 'Root', 0, 1);
INSERT INTO category (id, name, parent_category_id, sort_order, is_root) VALUES
    ('cat-medical', 'Medical', 'cat-root', 1, 0),
    ('cat-supplies', 'Supplies', 'cat-root', 2, 0);

-- 2 ProductType
INSERT INTO product_type (id, name, code, product_type_code) VALUES
    ('pt-good', 'Good', 'GOOD', 'GOOD'),
    ('pt-service', 'Service', 'SVC', 'SERVICE');

-- 2 Attribute
INSERT INTO attribute (id, code, name, active, exportable, required, allow_multiple) VALUES
    ('attr-color', 'COL', 'Color', 1, 1, 0, 0),
    ('attr-size', 'SZ', 'Size', 1, 1, 0, 1);

-- 1 ProductGroup
INSERT INTO product_group (id, name) VALUES
    ('pg-medical', 'Medical Products');

-- 3 Products
INSERT INTO product (id, name, product_code, product_type_id, category_id, unit_of_measure_id, active) VALUES
    ('p-bandage', 'Bandage', 'BND001', 'pt-good', 'cat-medical', 'uom-pc', 1),
    ('p-syringe', 'Syringe', 'SYR001', 'pt-good', 'cat-medical', 'uom-pc', 1),
    ('p-iv-drip', 'IV Drip', 'IVD001', 'pt-good', 'cat-supplies', 'uom-pc', 1);

-- 2 Tags
INSERT INTO tag (id, tag, is_active) VALUES
    ('tag-essential', 'essential', 1),
    ('tag-trauma', 'trauma', 1);

-- product_tag M:N (FD#9 — schema empirically has NO unique constraint)
INSERT INTO product_tag (product_id, tag_id) VALUES
    ('p-bandage', 'tag-essential'),
    ('p-bandage', 'tag-trauma'),
    ('p-syringe', 'tag-essential');

-- product_group_product M:N
INSERT INTO product_group_product (product_id, product_group_id) VALUES
    ('p-bandage', 'pg-medical'),
    ('p-syringe', 'pg-medical');

-- 2 Synonyms (1 DISPLAY_NAME per product per locale per FD#10)
INSERT INTO synonym (id, name, locale, synonym_type_code, product_id) VALUES
    ('syn-bandage-fr', 'pansement', 'fr', 'DISPLAY_NAME', 'p-bandage'),
    ('syn-syringe-fr', 'seringue', 'fr', 'DISPLAY_NAME', 'p-syringe');

-- 1 ProductSupplier (T2). supplier_id is a free String in create-mode (no FK to org-service);
-- product_id references the seeded p-bandage. All NOT NULL columns the entity maps are populated:
-- name, product_id, tiered_pricing, version, date_created, last_updated.
INSERT INTO product_supplier (id, code, name, product_id, supplier_id, active, tiered_pricing, version, date_created, last_updated) VALUES
    ('ps-bandage-acme', 'PS-BND-ACME', 'Bandage from Acme', 'p-bandage', 'org-acme-placeholder', 1, 0, 0, NOW(), NOW());

-- 1 ProductSupplierPreference (T3). Fixture row references ps-bandage-acme.
-- destination_party_id and preference_type_id are free Strings in create-mode (no FK to org-service/core entities).
-- All NOT NULL columns the entity maps are populated: product_supplier_id, version, date_created, last_updated.
INSERT INTO product_supplier_preference (id, product_supplier_id, destination_party_id, preference_type_id, comments, version, date_created, last_updated) VALUES
    ('psp-bandage-acme-boston', 'ps-bandage-acme', 'org-boston-placeholder', 'pref-type-default', 'Preferred for Boston', 0, NOW(), NOW());

-- Task LQ list-page fixtures. Isolated to NEW supplier ids (ps-lq-*) so the T2–T5 tests (which key on
-- ps-bandage-acme + its single preference) are untouched. Deliberately use a DEDICATED supplier org
-- (org-lq-globex) and products (p-syringe / p-iv-drip) that NO sibling POST test writes a
-- product_supplier row against — because these tests COMMIT and run order-independently, a filter value
-- shared with a sibling POST (e.g. org-globex-placeholder, which productSupplierPost_createsRow_* posts)
-- would give a non-deterministic count. Explicit, staggered date_created values (NOT NOW()) so the
-- sort-order tests are deterministic.
--   - ps-lq-syringe-globex: product p-syringe — exercises filter-by-product and filter-by-supplier.
--   - ps-lq-iv-multi: product p-iv-drip, with TWO preferences of TWO different types (pref-type-default
--     AND pref-type-backup) — proves the EXISTS filter counts this supplier ONCE (totalCount = 1),
--     guarding the EXISTS-not-JOIN choice.
--   - ps-lq-iv-extra: a 3rd org-lq-globex row so a small max (max=2) on the org-lq-globex filter yields
--     data.length(2) < totalCount(3) — the pagination assertion.
-- All NOT NULL mapped columns populated: name, product_id, tiered_pricing, version, date_created, last_updated.
INSERT INTO product_supplier (id, code, name, product_id, supplier_id, active, tiered_pricing, version, date_created, last_updated) VALUES
    ('ps-lq-syringe-globex', 'PS-SYR-GLX', 'Syringe from Globex', 'p-syringe', 'org-lq-globex', 1, 0, 0, '2024-01-01 10:00:00', '2024-01-01 10:00:00'),
    ('ps-lq-iv-multi', 'PS-IVD-GLX', 'IV Drip from Globex', 'p-iv-drip', 'org-lq-globex', 1, 0, 0, '2024-01-02 10:00:00', '2024-01-02 10:00:00'),
    ('ps-lq-iv-extra', 'PS-IVD-GLX2', 'IV Drip Extra from Globex', 'p-iv-drip', 'org-lq-globex', 1, 0, 0, '2024-01-03 10:00:00', '2024-01-03 10:00:00');

-- Two preferences of two DIFFERENT types on the SAME supplier (ps-lq-iv-multi) — the EXISTS-counts-once fixture.
INSERT INTO product_supplier_preference (id, product_supplier_id, destination_party_id, preference_type_id, comments, version, date_created, last_updated) VALUES
    ('psp-lq-iv-default', 'ps-lq-iv-multi', 'org-globex-placeholder', 'pref-type-default', 'IV default pref', 0, NOW(), NOW()),
    ('psp-lq-iv-backup', 'ps-lq-iv-multi', 'org-globex-placeholder', 'pref-type-backup', 'IV backup pref', 0, NOW(), NOW());

-- 1 ProductPackage (T4). Fixture references p-bandage, ps-bandage-acme, uom-pc.
-- All NOT NULL columns the entity maps are populated: quantity, version, date_created, last_updated.
-- product_price_id is NOT referenced here (the productPrice association is unmapped at T4, so the
-- create-mode schema won't have that column).
INSERT INTO product_package (id, name, description, gtin, quantity, product_id, uom_id, product_supplier_id, version, date_created, last_updated) VALUES
    ('pp-bandage-box', 'Bandage Box', 'Box of 12 bandages', 'GTIN-BND-BOX', 12, 'p-bandage', 'uom-pc', 'ps-bandage-acme', 0, NOW(), NOW());

-- 1 ProductPrice (T5). type and price are NOT NULL (price decimal(19,4), type stored as enum-name String).
-- currency_id references seeded uom-pc; from_date/to_date and audit columns are nullable.
INSERT INTO product_price (id, version, type, price, currency_id, from_date, to_date, date_created, last_updated) VALUES
    ('pp-price-acme', 0, 'DEFAULT_PRICE', 9.9900, 'uom-pc', NULL, NULL, NOW(), NOW());

-- 2 ProductCatalog (T6). code is NOT NULL UNIQUE; version/date_created/last_updated NOT NULL.
-- GET-only entity (zero React callers); no sibling writers, so the count assertion of 2 is safe.
INSERT INTO product_catalog (id, version, code, name, description, active, color, date_created, last_updated) VALUES
    ('pc-essential', 0, 'ESSENTIAL', 'Essential Medicines', 'Core essential list', 1, '#ff0000', NOW(), NOW()),
    ('pc-trauma', 0, 'TRAUMA', 'Trauma Kit', NULL, 0, NULL, NOW(), NOW());

-- 3 ProductCatalogItem (T7). active bit(1) NOT NULL; product_id + product_catalog_id NOT NULL FKs.
-- References the seeded products (p-bandage, p-syringe) + the T6 catalogs (pc-essential, pc-trauma).
-- GET-only entity (zero React callers); no sibling writers, so the count assertion of 3 is safe.
-- pci-trauma-bandage seeds active=0 for the bit(1)->Boolean false round-trip.
INSERT INTO product_catalog_item (id, version, active, product_id, product_catalog_id, date_created, last_updated) VALUES
    ('pci-ess-bandage', 0, 1, 'p-bandage', 'pc-essential', NOW(), NOW()),
    ('pci-ess-syringe', 0, 1, 'p-syringe', 'pc-essential', NOW(), NOW()),
    ('pci-trauma-bandage', 0, 0, 'p-bandage', 'pc-trauma', NOW(), NOW());
