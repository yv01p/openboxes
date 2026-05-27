-- ============================================================================
-- docker/init-baseline.sql
-- ============================================================================
-- Pre-T17 dev-DB baseline fixes for Phase 2 identity-service E2E testing.
--
-- WHY THIS FILE EXISTS
-- --------------------
-- Phase 2 Task 17 (Playwright E2E) discovered the dev DB had been broken since
-- well before T17:
--   * admin had a cleartext password literal ("password") instead of a SHA-1
--     hash, so identity-service's PasswordEncoder rejected ALL admin logins
--     (cleartext format is intentionally not accepted; see
--     services/identity-service/src/main/java/org/openboxes/identity/password/
--     OpenboxesPasswordEncoder.java)
--   * admin's person.active was NULL (not 1), so even with a valid password
--     identity-service's AuthService would have rejected with AccountDisabled
--   * admin had no location_role row, so post-login Hibernate "null index
--     column" 500s on dashboard load
--
-- IMPACT
-- ------
-- Phase 2 Tasks 10-16 reported "end-to-end smoke confirmed" but were silently
-- receiving 401/error responses, not 200 success. The auth pipeline was never
-- actually exercised at the live admin-login level until T17.
--
-- HOW TO APPLY
-- ------------
-- Manual one-shot (current dev pattern):
--   sudo docker exec -i openboxes-db mariadb -u root -proot openboxes < docker/init-baseline.sql
--
-- For an automated init on fresh DB, mount as a MariaDB entrypoint script:
--   volumes:
--     - ./docker/init-baseline.sql:/docker-entrypoint-initdb.d/99-init-baseline.sql:ro
-- (Note: MariaDB only runs /docker-entrypoint-initdb.d scripts on FIRST
-- container start with empty data dir; existing dev DBs need the manual one-shot above.)
--
-- IDEMPOTENT
-- ----------
-- All statements are UPDATEs (safe to re-run) plus an INSERT IGNORE.
-- Re-running this file on an already-good DB is a no-op.
-- ============================================================================

-- 1. Admin person.active=1 (was NULL → AuthService rejected as disabled)
UPDATE person SET active = 1
  WHERE id IN (SELECT id FROM user WHERE username = 'admin');

-- 2. Admin password → SHA-1+Base64 of "password" (was cleartext → rejected by
-- OpenboxesPasswordEncoder). identity-service's PasswordMigrator will
-- automatically upgrade this row to BCrypt on the first successful login.
UPDATE user SET password = 'W6ph5Mm5Pz8GgiULbPgzG37mj9g='
  WHERE username = 'admin' AND password = 'password';

-- 3. Admin location_role for warehouse '1' (was missing → Hibernate
-- "null index column" 500 on dashboard load). location_roles_idx=0 mirrors
-- the GORM List mapping. INSERT IGNORE so re-runs don't duplicate.
INSERT IGNORE INTO location_role (id, version, user_id, location_id, role_id, location_roles_idx)
  SELECT 'admin-locrole-baseline', 0, u.id, '1', '1', 0
  FROM user u
  WHERE u.username = 'admin'
  AND EXISTS (SELECT 1 FROM location WHERE id = '1')
  AND EXISTS (SELECT 1 FROM role WHERE id = '1');
