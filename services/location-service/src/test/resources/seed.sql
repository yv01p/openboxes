-- Location types
INSERT INTO location_type (id, name, location_type_code, sort_order) VALUES
    ('lt-depot-001', 'Depot', 'DEPOT', 10),
    ('lt-bin-001', 'Bin', 'BIN_LOCATION', 20),
    ('lt-zone-001', 'Zone', 'ZONE', 16);

-- Location group
INSERT INTO location_group (id, name) VALUES ('lg-001', 'Test Group');

-- Real locations (DEPOT)
INSERT INTO location (id, name, location_type_id, location_group_id, active) VALUES
    ('loc-depot-a', 'Depot A', 'lt-depot-001', 'lg-001', 1),
    ('loc-depot-b', 'Depot B', 'lt-depot-001', NULL, 1),
    ('loc-depot-inactive', 'Depot Inactive', 'lt-depot-001', NULL, 0);

-- Bin locations (should be filtered out by default)
INSERT INTO location (id, name, location_type_id, parent_location_id, active) VALUES
    ('loc-bin-1', 'Bin 1', 'lt-bin-001', 'loc-depot-a', 1),
    ('loc-bin-2', 'Bin 2', 'lt-bin-001', 'loc-depot-a', 1);

-- Zone location (should NOT be filtered — Grails parity, FD#2 pick a)
INSERT INTO location (id, name, location_type_id, parent_location_id, active) VALUES
    ('loc-zone-1', 'Zone 1', 'lt-zone-001', 'loc-depot-a', 1);

-- Activity grants
INSERT INTO location_supported_activities (location_id, supported_activities_string) VALUES
    ('loc-depot-a', 'RECEIVE_STOCK'),
    ('loc-depot-a', 'SEND_STOCK');
INSERT INTO location_type_supported_activities (location_type_id, supported_activities_string) VALUES
    ('lt-depot-001', 'MANAGE_INVENTORY');
