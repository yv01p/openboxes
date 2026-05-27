-- Test fixtures for IdentityServiceIntegrationTest.
-- Schema is created by Hibernate (spring.jpa.hibernate.ddl-auto=create-drop), so column types
-- come from the JPA entity annotations under org.openboxes.identity.entity.*. This file just
-- supplies rows. Run via spring.sql.init.data-locations after schema generation
-- (spring.jpa.defer-datasource-initialization=true).
--
-- Password fixtures (precomputed; see HashGen recipe in plan Task 16 implementer notes):
--   admin / Admin123!  -> BCrypt $2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2
--   legacy / Legacy123! -> SHA1+Base64 ck0GD63Js4/id2KBLG/rQ8GbbZw=
--   cleartext / cleartext (literal, no hash; tests sha1AutoMigrate cleartext-rejection)
--
-- IDs are exactly 38 chars (CHAR(38) primary keys) for predictability across the test
-- methods that reference them directly.

-- Roles
INSERT INTO role (id, version, role_type, name, description) VALUES
    ('role-admin0000000000000000000000000000',   0, 'ROLE_ADMIN',   'ROLE_ADMIN',   'admin role'),
    ('role-browser00000000000000000000000000',   0, 'ROLE_BROWSER', 'ROLE_BROWSER', 'browser role');

-- Locations
INSERT INTO location (id, name, active) VALUES
    ('loc-warehouse0000000000000000000000000', 'Test Warehouse',      1),
    ('loc-disabled00000000000000000000000000', 'Disabled Warehouse',  0),
    ('loc-noaccess00000000000000000000000000', 'No-Access Warehouse', 1);

-- ============================================================================
-- Persons (parent table for JOINED inheritance with user)
-- ============================================================================

-- Admin (active=true, BCrypt password, ROLE_ADMIN + ROLE_BROWSER, location access)
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-admin00000000000000000000000000', 0, 'Admin', 'User', 'admin@example.com', NULL, NOW(), NOW(), 1);

-- Legacy SHA-1 password user
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-legacy0000000000000000000000000', 0, 'Legacy', 'User', 'legacy@example.com', NULL, NOW(), NOW(), 1);

-- Cleartext password user (will fail to authenticate)
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-cleartext0000000000000000000000', 0, 'Clear', 'Text', 'cleartext@example.com', NULL, NOW(), NOW(), 1);

-- Disabled (active=false)
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-disabled00000000000000000000000', 0, 'Disabled', 'User', 'disabled@example.com', NULL, NOW(), NOW(), 0);

-- Null-active (active=NULL)
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-nullactive000000000000000000000', 0, 'NullActive', 'User', 'nullactive@example.com', NULL, NOW(), NOW(), NULL);

-- Reset-flow user (active=true, BCrypt password)
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-reset00000000000000000000000000', 0, 'Reset', 'User', 'reset@example.com', NULL, NOW(), NOW(), 1);

-- Non-admin caller (ROLE_BROWSER only) for adminEndpoint_403WhenCallerNotAdmin
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-nonadmin00000000000000000000000', 0, 'NonAdmin', 'User', 'nonadmin@example.com', NULL, NOW(), NOW(), 1);

-- Target user for admin password change
INSERT INTO person (id, version, first_name, last_name, email, phone_number, date_created, last_updated, active)
VALUES ('person-target0000000000000000000000000', 0, 'Target', 'User', 'target@example.com', NULL, NOW(), NOW(), 1);

-- ============================================================================
-- Users (child table; FK on id -> person.id via JOINED inheritance)
-- ============================================================================

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-admin00000000000000000000000000', 'admin',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-legacy0000000000000000000000000', 'legacy',
        'ck0GD63Js4/id2KBLG/rQ8GbbZw=',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-cleartext0000000000000000000000', 'cleartext',
        'cleartext',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-disabled00000000000000000000000', 'disabled',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-nullactive000000000000000000000', 'nullactive',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-reset00000000000000000000000000', 'resetuser',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-nonadmin00000000000000000000000', 'nonadmin',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

INSERT INTO `user` (id, username, password, locale, timezone, last_login_date, warehouse_id, manager_id, remember_last_location, photo, dashboard_config)
VALUES ('person-target0000000000000000000000000', 'target',
        '$2a$10$QxT65nD5fk4px20bN5ZaP.gpW2aV4iRPe4hq6Ba/H.PYeWviMbZV2',
        NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL);

-- ============================================================================
-- user_role: global role bindings
-- ============================================================================

-- Admin user: both ROLE_ADMIN and ROLE_BROWSER
INSERT INTO user_role (user_id, role_id) VALUES
    ('person-admin00000000000000000000000000', 'role-admin0000000000000000000000000000'),
    ('person-admin00000000000000000000000000', 'role-browser00000000000000000000000000');

-- Other users: ROLE_BROWSER only
INSERT INTO user_role (user_id, role_id) VALUES
    ('person-legacy0000000000000000000000000',    'role-browser00000000000000000000000000'),
    ('person-reset00000000000000000000000000',    'role-browser00000000000000000000000000'),
    ('person-nonadmin00000000000000000000000',    'role-browser00000000000000000000000000'),
    ('person-target0000000000000000000000000',    'role-browser00000000000000000000000000');

-- ============================================================================
-- location_role: admin user has ROLE_ADMIN at the test warehouse and at the
-- disabled warehouse (so chooseLocation_403OnDisabledLocation hits the disabled
-- check rather than the access check).
-- ============================================================================
INSERT INTO location_role (id, version, user_id, role_id, location_id, location_roles_idx) VALUES
    ('locrole-admin-warehouse000000000000000', 0,
        'person-admin00000000000000000000000000',
        'role-admin0000000000000000000000000000',
        'loc-warehouse0000000000000000000000000',
        0),
    ('locrole-admin-disabled0000000000000000', 0,
        'person-admin00000000000000000000000000',
        'role-admin0000000000000000000000000000',
        'loc-disabled00000000000000000000000000',
        1);
