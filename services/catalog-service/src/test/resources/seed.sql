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
-- T12: category gained version (bigint NOT NULL, no DB default) + NOT NULL date_created/last_updated
-- (timestamp-only audit). ddl-auto=create now generates these NOT NULL, so the seed must supply them
-- (mirrors the product_supplier seed rows). version=0, NOW() for the audit timestamps.
INSERT INTO category (id, name, sort_order, is_root, version, date_created, last_updated) VALUES
    ('cat-root', 'Root', 0, 1, 0, NOW(), NOW());
INSERT INTO category (id, name, parent_category_id, sort_order, is_root, version, date_created, last_updated) VALUES
    ('cat-medical', 'Medical', 'cat-root', 1, 0, 0, NOW(), NOW()),
    ('cat-supplies', 'Supplies', 'cat-root', 2, 0, 0, NOW(), NOW());

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
-- RC-16 (T4): abc_class added. p-bandage='A', p-syringe='B' (2 distinct non-empty),
-- p-iv-drip=NULL (proves the distinct-abcClass query excludes null). The distinct-set
-- assertion (productAbcClasses_returnsDistinctNonEmpty) expects exactly [A, B].
INSERT INTO product (id, name, product_code, product_type_id, category_id, unit_of_measure_id, active, abc_class) VALUES
    ('p-bandage', 'Bandage', 'BND001', 'pt-good', 'cat-medical', 'uom-pc', 1, 'A'),
    ('p-syringe', 'Syringe', 'SYR001', 'pt-good', 'cat-medical', 'uom-pc', 1, 'B'),
    ('p-iv-drip', 'IV Drip', 'IVD001', 'pt-good', 'cat-supplies', 'uom-pc', 1, NULL);

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
INSERT INTO product_package (id, name, description, gtin, quantity, product_id, uom_id, product_supplier_id, version, date_created, last_updated) VALUES
    ('pp-bandage-box', 'Bandage Box', 'Box of 12 bandages', 'GTIN-BND-BOX', 12, 'p-bandage', 'uom-pc', 'ps-bandage-acme', 0, NOW(), NOW());

-- 1 ProductPrice (T5). type and price are NOT NULL (price decimal(19,4), type stored as enum-name String).
-- currency_id references seeded uom-pc; from_date/to_date and audit columns are nullable.
INSERT INTO product_price (id, version, type, price, currency_id, from_date, to_date, date_created, last_updated) VALUES
    ('pp-price-acme', 0, 'DEFAULT_PRICE', 9.9900, 'uom-pc', NULL, NULL, NOW(), NOW());

-- Task LQ2 derived-field fixture. Give the ps-lq-syringe-globex list-query supplier a DEFAULT package
-- (1:1 default_product_package_id) that carries a uom (uom-pc, code "pc") AND a product price, so the
-- enriched list response exercises packageSize / packagePrice / unitPrice with KNOWN values:
--   packageSize = "pc/12"  (uom.code "/" quantity)
--   packagePrice = 12.0000 -> setScale(2,HALF_UP) = 12.00
--   unitPrice    = 12.00 / 12 = 1.00
-- ps-lq-iv-multi / ps-lq-iv-extra are LEFT WITHOUT a default package — they exercise the no-package path
-- (packageSize null, packagePrice/unitPrice 0.00). product_supplier.default_product_package_id and
-- product_package.product_supplier_id form a circular FK pair, so we INSERT the package referencing the
-- supplier first, then UPDATE the supplier's default_product_package_id (mirrors the base_uom_id pattern
-- at lines 12-13). price is decimal(19,4); a price that divides evenly by quantity keeps unitPrice exact.
INSERT INTO product_price (id, version, type, price, currency_id, from_date, to_date, date_created, last_updated) VALUES
    ('pp-price-lq-syringe', 0, 'DEFAULT_PRICE', 12.0000, 'uom-pc', NULL, NULL, NOW(), NOW());
INSERT INTO product_package (id, name, description, gtin, quantity, product_id, uom_id, product_supplier_id, product_price_id, version, date_created, last_updated) VALUES
    ('pp-lq-syringe-box', 'Syringe Box', 'Box of 12 syringes', 'GTIN-SYR-BOX', 12, 'p-syringe', 'uom-pc', 'ps-lq-syringe-globex', 'pp-price-lq-syringe', 0, NOW(), NOW());
UPDATE product_supplier SET default_product_package_id = 'pp-lq-syringe-box' WHERE id = 'ps-lq-syringe-globex';

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

-- product_attribute (T8). GET-only; NO audit columns. version NOT NULL. value is the scalar payload.
-- All 4 FKs are DB-nullable (live schema) — pa-*-color rows leave uom/supplier NULL to prove null-FK-id mapping;
-- pa-bandage-size populates all 4 FKs. attributes_idx left NULL (unmapped Grails list-index artifact).
-- GET-only, no sibling writers → count assertion of 3 is deterministic.
INSERT INTO product_attribute (id, version, attribute_id, product_id, value, unit_of_measure_id, product_supplier_id) VALUES
    ('pa-bandage-color', 0, 'attr-color', 'p-bandage', 'Blue', NULL, NULL),
    ('pa-bandage-size', 0, 'attr-size', 'p-bandage', 'Large', 'uom-pc', 'ps-bandage-acme'),
    ('pa-syringe-color', 0, 'attr-color', 'p-syringe', 'Clear', NULL, NULL);

-- product_association (T9). GET-only; timestamp-only Instant audit (date_created/last_updated NOT NULL).
-- code stored as the ProductAssociationTypeCode enum name (String). quantity NOT NULL (live schema).
-- Self-FK mutual_association_id: pa-band-syr <-> pa-syr-band are mutual (set via UPDATE to break the
-- INSERT cycle); pa-band-iv has a NULL mutual to exercise the null self-FK -> null id mapping.
-- GET-only, no sibling writers -> count assertion of 3 is deterministic.
INSERT INTO product_association (id, code, product_id, associated_product_id, quantity, comments, version, date_created, last_updated, mutual_association_id) VALUES
    ('pa-band-syr', 'SUBSTITUTE', 'p-bandage', 'p-syringe', 1.00, 'Bandage substitutes Syringe', 0, NOW(), NOW(), NULL),
    ('pa-syr-band', 'SUBSTITUTE', 'p-syringe', 'p-bandage', 1.00, 'Syringe substitutes Bandage', 0, NOW(), NOW(), 'pa-band-syr'),
    ('pa-band-iv', 'ACCESSORY', 'p-bandage', 'p-iv-drip', 2.50, NULL, 0, NOW(), NOW(), NULL);
UPDATE product_association SET mutual_association_id = 'pa-syr-band' WHERE id = 'pa-band-syr';

-- product_component (T10). GET-only; timestamp-only Instant audit (date_created/last_updated NOT NULL).
-- BOM lines: an assembly product made of component products (qty + uom). All 3 FKs NOT NULL.
-- GET-only, no sibling writers -> count assertion of 3 is deterministic.
INSERT INTO product_component (id, assembly_product_id, component_product_id, quantity, unit_of_measure_id, version, date_created, last_updated) VALUES
    ('pcomp-band-syr', 'p-bandage', 'p-syringe', 2.00, 'uom-pc', 0, NOW(), NOW()),
    ('pcomp-band-iv', 'p-bandage', 'p-iv-drip', 1.00, 'uom-pc', 0, NOW(), NOW()),
    ('pcomp-syr-iv', 'p-syringe', 'p-iv-drip', 3.00, 'uom-pc', 0, NOW(), NOW());

-- unit_of_measure_conversion (T11). GET-only; cache-backed (heuristic); Instant timestamp-only audit.
-- active bit(1) NOT NULL; conversion_rate decimal(19,8) NOT NULL; from/to UoM both NOT NULL FKs.
-- Two ACTIVE kg->g rows with different last_updated prove findConversionRate takes the MOST RECENT active
-- (order by last_updated desc). uconv-kg-g-inactive is active=0 with the LATEST last_updated — proves the
-- active filter excludes it (findConversionRate('kg','g') must return 1000.50, NOT 999). uconv-g-kg is the
-- reverse direction. GET-only, no sibling writers -> count assertion of 4 is deterministic.
INSERT INTO unit_of_measure_conversion (id, version, active, from_unit_of_measure_id, to_unit_of_measure_id, conversion_rate, date_created, last_updated) VALUES
    ('uconv-kg-g-old', 0, 1, 'uom-kg', 'uom-g', 1000.00000000, '2024-01-01 10:00:00', '2024-01-01 10:00:00'),
    ('uconv-kg-g-new', 0, 1, 'uom-kg', 'uom-g', 1000.50000000, '2024-02-01 10:00:00', '2024-02-01 10:00:00'),
    ('uconv-g-kg', 0, 1, 'uom-g', 'uom-kg', 0.00100000, NOW(), NOW()),
    ('uconv-kg-g-inactive', 0, 0, 'uom-kg', 'uom-g', 999.00000000, '2024-03-01 10:00:00', '2024-03-01 10:00:00');
