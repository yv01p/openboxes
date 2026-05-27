# Phase 2 Identity Service Scope Audit

**Date:** 2026-05-27  
**Phase:** Phase 2 - Identity Service Extraction  
**Purpose:** Baseline scope confirmation and live-state capture before Task 2+ code changes. Verify §15 carve-out list matches actual codebase reality; capture current Grails identity flows for Task 18 done-gate comparison.

---

## Step 1: Grails-Side Identity Write Surface

Grep findings for `.save()`, `.delete()`, `new User()`, `new Person()`, `new Role()`, `new LocationRole()`, `addToRoles`, `removeFromRoles`, `addToLocationRoles`, `removeFromLocationRoles` patterns touching user/person/role/location_role tables:

### Classification Against §15 Carve-Out

**✅ IN CARVE-OUT (approved hybrid-state writes):**

1. **PersonController** (lines 50, 56, 57, 101) - `new Person()`, `personInstance.save()` → §15: admin person CRUD via GSP
2. **UserController** (lines 118, 129, 221, 429, 448) - `new User()`, `userInstance.save()`, `new LocationRole()` → §15: admin user CRUD via GSP
3. **AuthController** (lines 126, 179, 203) - `new User()`, `userInstance.save()` → §15: registration/signup path (treated as admin user CRUD extension)
4. **CreateShipmentWorkflowController** (lines 171, 1080, 1097) - `new Person()`, `personInstance.save()` → §15: workflow person creation
5. **ShipmentController** (line 1083) - `new Person()` → §15: shipment workflow person creation
6. **LoadDataService** (lines 386, 397, 407, 99) - `new User()`, `user.save()`, `new Person()`, `organization.addToRoles()` → §15: bootstrap data-loading
7. **UserImportDataService** (lines 58, 65, 68, 75) - `user.removeFromRoles()`, `user.addToRoles()`, `user.save()`, `new User()` → §15: bulk import
8. **PersonImportDataService** (lines 37, 45) - `person.save()`, `new Person()` → §15: bulk import
9. **UserLocationImportDataService** (lines 62, 63, 69) - `new LocationRole()`, `user.addToLocationRoles()`, `user.save()` → §15: bulk import
10. **UserService** SQL.execute (lines 399, 408) - `sql.execute('insert into user...')`, `sql.execute('delete from user...')` → §15: bootstrap seed path

**✅ IN CARVE-OUT (non-identity PartyRole writes - organization roles, not user roles):**

11. **OrganizationService** (line 76) - `organization.addToRoles(new PartyRole())` → PartyRole for organizations, not identity User roles
12. **MigrationService** (line 867) - `organization.addToRoles(new PartyRole())` → PartyRole for organizations, not identity User roles
13. **PartyRoleController** (lines 36, 77) - `partyRoleInstance.save()` → Generic PartyRole controller (used for organizations primarily)

**⚠️ SCOPE EXPANSION CANDIDATES (NOT explicitly in §15 carve-out):**

14. **JsonController** (lines 742, 744, 750) - `new Person()`, `person.save()` → NOT in §15 carve-out. This is a generic API JSON controller creating Person records during some workflow. **FLAG TO USER** for classification.

15. **UserService** (lines 37, 88, 103, 114, 120, 136, 137, 145, 537, 544, 547, 551) - Multiple `user.save()`, `userInstance.save()`, `new LocationRole()`, `user.addToLocationRoles()`, `user.removeFromLocationRoles()`, `userInstance.addToRoles()` → These are service-layer writes called by various paths. Some are invoked by UserController (covered in §15), but UserService is also called from other contexts. **Requires deeper analysis** - some calls may be from API endpoints NOT in §15 carve-out.

16. **LocationRoleDataService** (line 19) - `user?.removeFromLocationRoles(locationRole)` → Data service removing location roles. Called from where? **FLAG TO USER** for classification.

17. **PersonService** (lines 86, 87, 106, 107) - `new Person()`, `person.save()` → Service-layer person creation. Called from where? **FLAG TO USER** for classification.

18. **DashboardController** (lines 137, 144, 226) - `tag.save()`, `productCatalog.save()`, `user.save()` → Lines 137/144 are Tag/ProductCatalog (not identity). Line 226 is `user.save()` - dashboard updating user preferences? **FLAG line 226 TO USER** for classification (preferences update may be in-scope for Phase 2 or deferred).

### Summary

- **Controllers/Services in §15 carve-out:** UserController, PersonController, AuthController, CreateShipmentWorkflowController, ShipmentController, LoadDataService, UserImportDataService, PersonImportDataService, UserLocationImportDataService, UserService (SQL.execute bootstrap only)
- **Scope expansion flags requiring user review:**
  - **JsonController** (Person creation in JSON API workflow)
  - **UserService** (service-layer writes - need path analysis to determine if all callers are §15-covered)
  - **LocationRoleDataService** (removeFromLocationRoles - need caller analysis)
  - **PersonService** (Person creation - need caller analysis)
  - **DashboardController line 226** (`user.save()` for preferences?)

**Recommendation:** HOLD on Task 2+ until user classifies the 5 flagged items above. If any are Phase 2 in-scope (not covered by §15 carve-out), spec/plan revision required.

---

## Step 2: session.user / authService.currentUser Readers

**Count:** 54 files

**First 20 files:**
1. `grails-app/controllers/org/pih/warehouse/InitializationInterceptor.groovy`
2. `grails-app/controllers/org/pih/warehouse/JsonController.groovy`
3. `grails-app/controllers/org/pih/warehouse/MobileController.groovy`
4. `grails-app/controllers/org/pih/warehouse/RoleInterceptor.groovy`
5. `grails-app/controllers/org/pih/warehouse/SecurityInterceptor.groovy`
6. `grails-app/controllers/org/pih/warehouse/SentryInterceptor.groovy`
7. `grails-app/controllers/org/pih/warehouse/api/ApiController.groovy`
8. `grails-app/controllers/org/pih/warehouse/api/DashboardApiController.groovy`
9. `grails-app/controllers/org/pih/warehouse/api/PutawayApiController.groovy`
10. `grails-app/controllers/org/pih/warehouse/api/ReplenishmentApiController.groovy`
11. `grails-app/controllers/org/pih/warehouse/api/StockTransferApiController.groovy`
12. `grails-app/controllers/org/pih/warehouse/api/StocklistApiController.groovy`
13. `grails-app/controllers/org/pih/warehouse/core/LocalizationController.groovy`
14. `grails-app/controllers/org/pih/warehouse/inventory/InventoryController.groovy`
15. `grails-app/controllers/org/pih/warehouse/inventory/InventoryItemController.groovy`
16. `grails-app/controllers/org/pih/warehouse/inventory/StockMovementController.groovy`
17. `grails-app/controllers/org/pih/warehouse/order/OrderController.groovy`
18. `grails-app/controllers/org/pih/warehouse/order/PurchaseOrderController.groovy`
19. `grails-app/controllers/org/pih/warehouse/order/ReceiveOrderWorkflowController.groovy`
20. `grails-app/controllers/org/pih/warehouse/product/ProductController.groovy`

**Note:** These readers do NOT migrate in Phase 2. Per spec, SecurityInterceptor continues to populate `session.user` from JWT claims; these 54 files read from session unchanged.

---

## Step 3: Live-Smoke-Probe Current Grails Identity Flows

| Probe | Command Summary | HTTP Status | Response Body Shape | Notes |
|-------|----------------|-------------|---------------------|-------|
| 1 | `/api/login` POST with `{"username":"admin","password":"password","location":"1"}` | 200 | Plain-text: "Authentication was successful" | Sets `obx_token` cookie (JWT: `eyJhbGc...`). Cookie expiry: 1779910758 (8 hours from login). |
| 2 | `/api/chooseLocation/1` POST (with cookie from Probe 1) | 200 | Plain-text: "User Miss Administrator is now logged into Main Warehouse" | Location-switch confirmation. |
| 3 | `/openboxes/auth/handleLogin` POST with form data `username=admin&password=password` | 405 (Method Not Allowed) | N/A | POST rejected. Retried as GET → 200, but renders login form (no actual login processed). **GSP login flow NOT tested via handleLogin POST** - appears handleLogin expects GET, not POST, or form submission path is different. |
| 4 | `/api/logout` POST (with cookie from Probe 1) | 200 | Plain-text: "Logout was successful" | Returns `Set-Cookie: obx_token=; Max-Age=0` (clear-cookie header confirmed). |
| 5 | `/openboxes/dashboard/index` GET (after fresh login via `/api/login`) | 200 | HTML dashboard page | Confirms `session.user` populated correctly post-login; dashboard is reachable. |

**Probe 3 Caveat:** The plan's `handleLogin` POST probe returned 405. GSP form-based login may use a different endpoint or method. API login (Probe 1) + dashboard reachability (Probe 5) confirm the API path works. GSP login path not fully exercised.

**Task 18 Done-Gate:** Re-run Probes 1, 2, 4, 5 against new identity-service shim endpoints to verify parity.

---

## Step 4: Database State for Auth Tables

### Table Schemas

**`user` table:**
```
Field                    Type          Null  Key  Default  Extra
id                       char(38)      NO    PRI  NULL     
last_login_date          datetime      YES        NULL     
manager_id               char(38)      YES   MUL  NULL     
password                 varchar(255)  YES        NULL     
username                 varchar(255)  YES   UNI  NULL     
warehouse_id             char(38)      YES   MUL  NULL     
photo                    mediumblob    YES        NULL     
locale                   varchar(255)  YES        NULL     
remember_last_location   bit(1)        YES        NULL     
timezone                 varchar(255)  YES        NULL     
dashboard_config         longblob      YES        NULL     
```

**`person` table:**
```
Field         Type          Null  Key  Default  Extra
id            char(38)      NO    PRI  NULL     
version       bigint(20)    NO         NULL     
date_created  datetime      NO         NULL     
email         varchar(255)  YES        NULL     
first_name    varchar(255)  YES        NULL     
last_name     varchar(255)  YES        NULL     
last_updated  datetime      NO         NULL     
phone_number  varchar(255)  YES        NULL     
active        bit(1)        YES        b'1'     
```

**`role` table:**
```
Field        Type          Null  Key  Default  Extra
id           char(38)      NO    PRI  NULL     
version      bigint(20)    NO         NULL     
description  varchar(255)  YES        NULL     
role_type    varchar(255)  NO         NULL     
name         varchar(255)  NO         NULL     
```

**`user_role` table:**
```
Field     Type      Null  Key  Default  Extra
user_id   char(38)  YES   MUL  NULL     
role_id   char(38)  YES   MUL  NULL     
```

**`location_role` table:**
```
Field                Type      Null  Key  Default  Extra
user_id              char(38)  YES   MUL  NULL     
location_id          char(38)  YES   MUL  NULL     
role_id              char(38)  YES   MUL  NULL     
version              int(11)   YES        NULL     
id                   char(38)  YES        NULL     
location_roles_idx   int(11)   YES        NULL     
```

**Schema Alignment with Spec §A12-A19:** ✅ Confirmed. All columns match spec appendix schema definitions.

### Role Records

```
id   role_type         name
1    ROLE_ADMIN        Admin
5    ROLE_SUPERUSER    Superuser
```

**Task 6 RoleCache dependency:** ✅ Confirmed. ROLE_ADMIN (id=1) and ROLE_SUPERUSER (id=5) exist in DB.

### Admin User Password Format

```
id   username   pw_len   prefix
1    admin      8        pass
```

**Password format:** **PLAIN-TEXT** (length 8, prefix "pass" → literal string "password").

**⚠️ CRITICAL FINDING:** The current dev DB has admin password stored as plain-text `"password"`, NOT SHA-1+Base64 (length 28) or BCrypt (length 60 + `$2a$` prefix). This differs from spec assumptions (§10.1 assumes SHA-1 or BCrypt hashes in production). Task 5 auto-migrate test must account for plain-text passwords in dev DB (PasswordEncoder `matches()` will fail unless LegacyPasswordEncoder handles plain-text as a fallback, or DB is seeded with proper hashes).

**Action for Task 5:** Verify if `LegacyPasswordEncoder` supports plain-text matching (unlikely per spec §10.1.2), or seed dev DB with SHA-1/BCrypt hashes before Task 5 test. Alternatively, Task 5 test may need to create a test user with known BCrypt hash.

### Person Active=NULL Distribution

```
null_active_persons
0
```

**Finding:** Zero person records have `active IS NULL`. All person records have explicit `active=0` or `active=1` (default `active=1` per schema). Task 16 fixture + login null-active handling edge case is NOT present in current dev DB.

---

## Summary

### Scope Audit Status: ⚠️ **HOLD - USER REVIEW REQUIRED**

**§15 Carve-Out Alignment:**
- **Confirmed in carve-out:** UserController, PersonController, AuthController, CreateShipmentWorkflowController, ShipmentController, LoadDataService, UserImportDataService, PersonImportDataService, UserLocationImportDataService, UserService (SQL.execute only), PartyRoleController (non-identity PartyRole)
- **Scope expansion flags (NOT in §15 carve-out):**
  1. **JsonController** (lines 742, 744, 750) - Person creation in JSON API
  2. **UserService** (multiple `save()` calls) - service-layer writes; caller path analysis needed
  3. **LocationRoleDataService** (line 19) - `removeFromLocationRoles()`
  4. **PersonService** (lines 86, 87, 106, 107) - Person creation service
  5. **DashboardController** (line 226) - `user.save()` (preferences update?)

**Recommendation:** User must classify the 5 flagged items above BEFORE Task 2+. If any are Phase 2 in-scope (not §15-covered), spec/plan revision required.

### Live-Smoke-Probe Status: ✅ **BASELINE CAPTURED**

- **API login** (Probe 1): ✅ 200 + JWT cookie set
- **chooseLocation** (Probe 2): ✅ 200 + confirmation message
- **GSP login** (Probe 3): ⚠️ handleLogin POST returned 405 (GSP login path not fully verified)
- **Logout** (Probe 4): ✅ 200 + clear-cookie header
- **Dashboard** (Probe 5): ✅ 200 + session.user populated

### Database State Status: ✅ **SCHEMAS CONFIRMED** | ⚠️ **PLAIN-TEXT PASSWORD IN DEV DB**

- **Schemas:** ✅ All tables match spec §A12-A19
- **Role records:** ✅ ROLE_ADMIN (id=1), ROLE_SUPERUSER (id=5) exist
- **Admin password:** ⚠️ **PLAIN-TEXT** (not SHA-1/BCrypt) - Task 5 test must account for this
- **Person active=NULL:** Zero records (edge case not present in dev DB)

### Green-Light for Phase 2 Tasks 2+?

**NO** - BLOCKED pending user classification of 5 scope-expansion flags + resolution of plain-text password concern for Task 5 test.
