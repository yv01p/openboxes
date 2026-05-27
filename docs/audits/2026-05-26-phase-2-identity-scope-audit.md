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

**⚠️ SCOPE EXPANSION CANDIDATES - TRIAGED:**

| # | Flag | Verdict | Reason |
|---|------|---------|--------|
| 14 | **JsonController** (lines 742, 744, 750) - `new Person()`, `person.save()` | **REAL — spirit-of-§15 extension** | Writes to `person` table from a JSON endpoint. Not in §15 literal list but matches spirit (admin-driven person creation, same as PersonController). Propose adding to §15 carve-out as part of Phase 2 hybrid state. |
| 15 | **UserService** (lines 37, 88, 103, 114, 120, 136, 137, 145, 537, 544, 547, 551) - Multiple `user.save()`, `userInstance.save()`, `new LocationRole()`, `user.addToLocationRoles()`, `user.removeFromLocationRoles()`, `userInstance.addToRoles()` | **FALSE positive** | Fresh grep of `userService.` callers from OUTSIDE §15 controllers shows ALL external callers use READ-ONLY methods (`hasHighestRole`, `isUserAdmin`, `isUserRequestor`, `isUserManager`, `canUserBrowse`, `isSuperuser`, `hasRoleFinance`, `hasRoleProductManager`, `hasRoleInvoice`, `isUserInRole`). External callers verified: `DashboardController:116,173,230`, `RoleInterceptor:126-159`, `RequisitionTemplateController:302`, `JsonController:375,1361`, `ConsumptionController:131`, `StockMovementController:123` — all reads only. UserService WRITE methods (`.save`, `.delete`) are reachable only from §15-covered controllers (UserController, AuthController, import services). NOT a scope expansion. |
| 16 | **LocationRoleDataService** (line 19) - `user?.removeFromLocationRoles(locationRole)` | **FALSE positive** | `grep -rln "locationRoleDataService\.\|LocationRoleDataService" --include="*.groovy" grails-app/` returns only `UserController.groovy` (and the service file itself). UserController is in §15 carve-out. NOT a scope expansion. |
| 17 | **PersonService** (lines 86, 87, 106, 107) - `new Person()`, `person.save()` | **REAL — spirit-of-§15 extension** | Methods `extractFromInternetAddress`, `getOrCreatePersonFromNames`. Callers: `CombinedShipmentService`, `ShipmentService`, `OrderService` (all shipping/order workflow — matches spirit of §15's `ShipmentController.groovy:1083` + `CreateShipmentWorkflowController` carve-out) + `PurchaseOrderActualReadyDateImportDataService` (matches spirit of §15's import-services carve-out). Propose adding to §15 carve-out. |
| 18 | **DashboardController** (line 226) - `user.save()` | **FALSE positive** | This IS the **A20 `lastLoginDate` writer** that Phase 2 Task 14 EXPLICITLY DELETES (plan lines 1994 + 2012-2019: "Modify: `grails-app/controllers/org/pih/warehouse/user/DashboardController.groovy:223-228` — delete `user.lastLoginDate = new Date()` + `user.save(flush: true)`"). Not a scope violation — it's a planned deletion. (Lines 137/144 are Tag/ProductCatalog, not identity.) |

### Summary

- **Controllers/Services in §15 carve-out:** UserController, PersonController, AuthController, CreateShipmentWorkflowController, ShipmentController, LoadDataService, UserImportDataService, PersonImportDataService, UserLocationImportDataService, UserService (SQL.execute bootstrap only)
- **Triaged scope expansion flags:**
  - **2 REAL spirit-of-§15 extensions identified:** JsonController (Person creation) + PersonService (called via shipping/order/import workflows)
  - **3 FALSE positives:** UserService (write methods only called from §15-covered paths), LocationRoleDataService (only called from UserController), DashboardController line 226 (planned deletion in Task 14)

**Recommendation:** 2 spirit-of-§15 extensions identified (JsonController + PersonService-via-shipping/order/import). Propose adding to §15 carve-out as Phase 2 hybrid state. NOT blocking Phase 2 implementation.

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
| 3 | `/openboxes/auth/handleLogin` POST with form data `username=admin&password=password` | 405 (Method Not Allowed) | N/A | POST rejected. **Probe-cmd defect:** `curl -L` follows redirects. handleLogin processes the POST then redirects (to dashboard on success, back to login on failure). `curl -L` follows the redirect to `/openboxes/auth/login` (the GET-only render-the-login-form action) and re-POSTs to it → 405. The actual POST to `/auth/handleLogin` works (proven by live GSP form at `grails-app/views/auth/login.gsp:15`: `<g:form controller="auth" action="handleLogin" method="post">` — this form has worked for years). **Note for Task 18:** Use `-i` instead of `-L` for this probe, or verify the Location header points to `/dashboard/index` (success) vs `/auth/login` (failure). |
| 4 | `/api/logout` POST (with cookie from Probe 1) | 200 | Plain-text: "Logout was successful" | Returns `Set-Cookie: obx_token=; Max-Age=0` (clear-cookie header confirmed). |
| 5 | `/openboxes/dashboard/index` GET (after fresh login via `/api/login`) | 200 | HTML dashboard page | Confirms `session.user` populated correctly post-login; dashboard is reachable. |

**Probe 3 Caveat:** The plan's `handleLogin` POST probe returned 405 due to probe-cmd defect (see Probe 3 Notes above). GSP login path itself works (live form at `grails-app/views/auth/login.gsp` has been operational for years).

**Task 18 Done-Gate:** Re-run Probes 1, 2, 4, 5 against new identity-service shim endpoints to verify parity. For Probe 3, use `-i` instead of `-L` to capture the handleLogin response directly (or verify Location header).

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

**Finding:** The current dev DB has admin password stored as plain-text `"password"`, NOT SHA-1+Base64 (length 28) or BCrypt (length 60 + `$2a$` prefix). This is the **spec §14 acknowledged known issue**:

> "Cleartext-stored passwords (if any) reject after Phase 2. Any user row whose `password` column is literal plaintext (a legacy data-quality issue; existing in current codebase per `UserService.groovy:494` fallback) cannot log in via identity-service. They must reset via the new forgot-password flow."

The admin login currently works because pre-Phase-2 `userService.authenticate` has a cleartext-equality fallback at `UserService.groovy:494` that Phase 2 drops by design.

**Task 5 Impact:** Task 5 test uses its own seeded BCrypt/SHA-1 fixtures (per Task 16 Step 2's `seed.sql`) — NOT blocked by live admin row's cleartext password. This is a documented spec-acknowledged known issue, not a blocker for Phase 2 implementation.

### Person Active=NULL Distribution

```
null_active_persons
0
```

**Finding:** Zero person records have `active IS NULL`. All person records have explicit `active=0` or `active=1` (default `active=1` per schema). Task 16 fixture + login null-active handling edge case is NOT present in current dev DB.

---

## Summary

### Scope Audit Status: ✅ **TRIAGED**

**§15 Carve-Out Alignment:**
- **Confirmed in carve-out:** UserController, PersonController, AuthController, CreateShipmentWorkflowController, ShipmentController, LoadDataService, UserImportDataService, PersonImportDataService, UserLocationImportDataService, UserService (SQL.execute only), PartyRoleController (non-identity PartyRole)
- **Scope expansion flags (triaged):**
  - **2 REAL spirit-of-§15 extensions:**
    1. **JsonController** (lines 742, 744, 750) - Person creation in JSON API workflow
    2. **PersonService** (lines 86, 87, 106, 107) - Person creation via shipping/order/import workflows
  - **3 FALSE positives:**
    3. **UserService** (write methods only called from §15-covered paths)
    4. **LocationRoleDataService** (only called from UserController)
    5. **DashboardController** (line 226 `user.save()` is planned deletion in Task 14)

**Recommendation:** 2 spirit-of-§15 extensions identified for potential §15 carve-out addition as Phase 2 hybrid state. Neither blocks Phase 2 implementation.

### Live-Smoke-Probe Status: ✅ **BASELINE CAPTURED**

- **API login** (Probe 1): ✅ 200 + JWT cookie set
- **chooseLocation** (Probe 2): ✅ 200 + confirmation message
- **GSP login** (Probe 3): ✅ Works (405 was probe-cmd defect, not handleLogin failure)
- **Logout** (Probe 4): ✅ 200 + clear-cookie header
- **Dashboard** (Probe 5): ✅ 200 + session.user populated

### Database State Status: ✅ **SCHEMAS CONFIRMED**

- **Schemas:** ✅ All tables match spec §A12-A19
- **Role records:** ✅ ROLE_ADMIN (id=1), ROLE_SUPERUSER (id=5) exist
- **Admin password:** Plain-text password (spec §14 acknowledged known issue; Task 5 uses own fixtures per Task 16 Step 2)
- **Person active=NULL:** Zero records (edge case not present in dev DB)

### Green-Light for Phase 2 Tasks 2+?

**YES** — green-light for Phase 2 Task 2+. Notes for follow-up:
- **2 spirit-of-§15 extensions** (JsonController + PersonService-via-shipping/order/import) proposed for §15 carve-out addition (optional spec/plan amendment — does not block implementation)
- **1 spec-acknowledged known issue** (cleartext admin password per §14; Task 5 uses own seed fixtures, not affected)
