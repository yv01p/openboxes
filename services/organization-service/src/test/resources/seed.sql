-- PartyType reference data (mirrors Grails seed at changelog-2018-05-30-2315-insert-party-type-data.xml)
INSERT INTO party_type (id, version, code, name, party_type_code, date_created, last_updated) VALUES
    ('pt-org-001', 0, 'ORG', 'Organization', 'ORGANIZATION', NOW(), NOW()),
    ('pt-prs-001', 0, 'PERSON', 'Person', 'PERSON', NOW(), NOW());

-- 3 Organization rows (class value from A28 verification — FQCN per T1 verification)
INSERT INTO party (id, version, class, party_type_id, code, name, description, active, date_created, last_updated) VALUES
    ('org-acme', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'ACM', 'Acme Inc', 'Acme test org', 1, NOW(), NOW()),
    ('org-beta', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'BET', 'Beta Corp', 'Beta test org', 1, NOW(), NOW()),
    ('org-inactive', 0, 'org.pih.warehouse.core.Organization', 'pt-org-001', 'INA', 'Inactive Co', 'Inactive test org', 0, NOW(), NOW());

-- 1 bare Party row for polymorphic test (FQCN per A28)
-- WORKAROUND: Organization fields have `nullable = false` in entity but apply to subclass only
-- SINGLE_TABLE DDL generates NOT NULL constraints, so we provide dummy values for base Party row
INSERT INTO party (id, version, class, party_type_id, code, name, active, date_created, last_updated) VALUES
    ('party-bare', 0, 'org.pih.warehouse.core.Party', 'pt-prs-001', '', '', 0, NOW(), NOW());

-- PartyRole rows (raw string roleType per CDR R1 §2.1)
INSERT INTO party_role (id, version, party_id, role_type) VALUES
    ('pr-acme-supplier', 0, 'org-acme', 'ROLE_SUPPLIER'),
    ('pr-acme-buyer', 0, 'org-acme', 'ROLE_BUYER'),
    ('pr-beta-buyer', 0, 'org-beta', 'ROLE_BUYER'),
    ('pr-bare-arbitrary', 0, 'party-bare', 'ROLE_RANDOM_NEW_VALUE');  -- verifies String tolerance (vs enum)

-- Address (defined for ddl-auto:validate; no endpoint accesses it)
INSERT INTO address (id, address, city, country) VALUES
    ('addr-001', '123 Main St', 'Boston', 'USA');
