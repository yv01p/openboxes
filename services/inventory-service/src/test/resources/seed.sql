-- inventory-service integration-test fixture (T6). Seed-and-read only: the entities are getters-only
-- (protected ctor, no setters) so the test cannot save() a constructed entity — it seeds here and reads
-- back via repos / EntityManager.find. ddl-auto=create generates the 8 mapped entity tables; this script
-- runs AFTER schema creation (spring.jpa.defer-datasource-initialization=true). NOW() supplies the
-- NOT-NULL Instant audit columns (catalog seed precedent).

-- `location` is NOT a JPA entity (FD#10 cross-context read via native SQL on location.inventory_id), so
-- ddl-auto will NOT create it — create it here. Only the two columns the native queries read are needed.
CREATE TABLE location (id CHAR(38) PRIMARY KEY, inventory_id CHAR(38));

-- Facilities: F1/F2 have an inventory; F3-noinv has NULL inventory_id (global-only path, NOT an error).
INSERT INTO location (id, inventory_id) VALUES
    ('F1', 'inv-F1'),
    ('F2', 'inv-F2'),
    ('F3-noinv', NULL);

-- inventory (id-only required; all other columns nullable per Inventory.java).
INSERT INTO inventory (id) VALUES
    ('inv-F1'),
    ('inv-F2');

-- inventory_level (id PK; inventory_id + abc_class nullable). F1 -> {A, C, ''}; the '' row must be filtered
-- by the JPQL (abcClass <> ''). F2 -> {D} which must NOT bleed into F1's result (facility scoping).
INSERT INTO inventory_level (id, inventory_id, abc_class) VALUES
    ('il-F1-A', 'inv-F1', 'A'),
    ('il-F1-C', 'inv-F1', 'C'),
    ('il-F1-empty', 'inv-F1', ''),
    ('il-F2-D', 'inv-F2', 'D');

-- One row each for the 6 repo-less entities so the round-trip exercises every JPA mapping.

-- inventory_item: date_created, last_updated NOT NULL.
INSERT INTO inventory_item (id, date_created, last_updated) VALUES
    ('ii-1', NOW(), NOW());

-- product_availability: NOT NULL = inventory_item_id, location_id, product_id, product_code, lot_number,
-- bin_location_name, quantity_on_hand, date_created, last_updated. quantity_not_picked is @Formula
-- (quantity_on_hand - quantity_allocated) — NOT a column, so it is NOT inserted; 100 - 30 = 70 is asserted.
INSERT INTO product_availability
    (id, inventory_item_id, location_id, product_id, product_code, lot_number, bin_location_name,
     quantity_on_hand, quantity_allocated, date_created, last_updated) VALUES
    ('pa-1', 'ii-1', 'loc-1', 'p-1', 'PC1', 'L1', 'Bin1', 100, 30, NOW(), NOW());

-- `transaction` is a SQL reserved word -> backtick-quoted. NOT NULL = date_created, last_updated, transaction_date.
INSERT INTO `transaction` (id, date_created, last_updated, transaction_date) VALUES
    ('tx-1', NOW(), NOW(), NOW());

-- transaction_entry: quantity NOT NULL.
INSERT INTO transaction_entry (id, quantity) VALUES
    ('te-1', 5);

-- transaction_source: NOT NULL = transaction_action, date_created, last_updated, created_by_id, updated_by_id.
INSERT INTO transaction_source
    (id, transaction_action, created_by_id, updated_by_id, date_created, last_updated) VALUES
    ('ts-1', 'DEBIT', 'u-1', 'u-1', NOW(), NOW());

-- transaction_type: NOT NULL = date_created, last_updated, name, transaction_code.
INSERT INTO transaction_type (id, name, transaction_code, date_created, last_updated) VALUES
    ('tt-1', 'Adjustment', 'ADJ', NOW(), NOW());
