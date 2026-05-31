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
