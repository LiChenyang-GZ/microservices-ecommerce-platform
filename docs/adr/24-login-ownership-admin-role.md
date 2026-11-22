# ADR-24 · Store requires a login, users touch only their own orders, admin endpoints need an admin role

**English** | [中文](24-login-ownership-admin-role.zh-CN.md)

**Status:** Accepted

## Context
After ADR-23, services could no longer call each other without a secret, but store still ended its security rules with `anyRequest().permitAll()`. Its JWT filter put the email on the request when a token was valid and let the request through when it was not. Only Pay Now checked who owned the order. So, with no token or with a stranger's token, anyone could:

- cancel another user's paid order (store then asked delivery to stop and wrote a refund);
- read another user's orders, payments and address;
- place an order in another user's name (the body's `userId` was trusted), charged to that user's bank account;
- ask for a refund on any paid order, including one already delivered.

A second group of endpoints had no caller at all and checked nothing: `payment-success` set an order to PAID without bank, others set any status, created a payment of any amount, handed held stock back, or read any order or payment by id. The entry's allowlist (ADR-15) kept most of them from the outside; inside the network they were open.

There was no idea of an admin. Accounts had no role and the JWT carried only `userId` and the email, so the admin dashboard, warehouse and price changes, refunds and delivery's dead-letter endpoints were open to any logged-in user.

This record covers two pull requests: login and ownership (#28), then the admin role (#29).

## Decision

### Login
- Every store path needs a valid user token except: sign-up, log-in, activate, forgot / reset password, `validate-token` (delivery and tracking call it), the catalogue (`GET /api/products`, `/api/products/*`, `/api/products/search/price`) and `/actuator/health/**`. Anything else without a valid token → **401** (`HttpStatusEntryPoint`; without it Spring answers 403 to anonymous requests).
- A bad or expired token is **not rejected by the filter**: the request stays anonymous. Protected paths answer 401 anyway, and the catalogue still works when the browser holds an old token. Otherwise the frontend's 401 handler would send a visitor to the log-in page for looking at products.
- The filter parses the token once, inside one `try`. Parsing checks the signature and the expiry together.

### Ownership
- Checked in the **service layer**, in one place: `OrderProductService#ownOrder` loads the order and compares its `userId` with the logged-in account. View, cancel and Pay Now go through it.
- **Someone else's order answers 404, the same as a missing one.** Order ids are sequential; a 403 would tell a stranger "this id exists, it is just not yours". Another user's order **list** answers 403: the caller typed that `userId` themselves, so saying "not yours" reveals nothing. An admin is no exception here; admins see all orders through `GET /api/orders`.
- `create-with-payment` takes the order's `userId` from the token and **ignores** the body's value instead of refusing it, so the frontend did not have to change.
- `OrderService#cancelOrder` re-reads the order after taking the row lock (`entityManager.refresh`). The owner check had already loaded the order into the same transaction; Hibernate then returns that loaded copy from the lock query, and a second click or a delivery event in between was decided on a stale status. The owner never changes, so checking it before the lock is safe; the status does change, so it is read after.

### Admin role
- `Account.role` is `USER` or `ADMIN`. Hibernate adds the column as nullable; `AccountRoleUpgrade` sets old rows to `USER` at startup (ADR-21's approach) and `Account#getRole` reads NULL as USER.
- The JWT carries a `role` claim; the login and `validate-token` replies include it. **A missing or unknown role is USER, never ADMIN**: tokens signed before roles existed have no claim. A `role: ADMIN` written by the user fails the signature check, so the request is anonymous.
- **The role is read from the token, not the database**, on every request. See Consequences for the cost.
- Admin only: `GET /api/orders`, everything under `/api/warehouses`, product create / update / assign, `/api/products/{id}/inventory-records` (any HTTP method: HEAD runs the GET handler), and refunds.
- **Refunds are for admins only (decision 4)**, on any order. A user gets money back by cancelling before pickup. An owner refunding after delivery would get the goods for free. A refund on a payment that is not SUCCESS answers 409 with the payment's status instead of "Refund requested". The order page has no refund button and the entry no longer routes the endpoint.
- delivery's dead-letter endpoints are admin only. The controller is marked `@AdminOnly` and a `HandlerInterceptor` checks the role from store's `validate-token` reply **after Spring has chosen the handler**. A filter comparing the raw URI with `/api/admin/` was bypassed by `/api/admin;x/...` and `/api/%61dmin/...`: Spring MVC routes on the decoded path with `;params` removed, the filter saw the raw text.
- **The first admin** is created at startup from `ADMIN_EMAIL` / `ADMIN_PASSWORD` (BCrypt, verified, active). Not configured: no admin, a WARN, store still starts. An ordinary account that already has that email is **not promoted**: activation does not check the verification code yet, so anyone could register that email first. If the email is already someone's username, the admin gets `admin-xxxxxxxx` instead of a unique-constraint failure that would stop store from starting.

### Endpoints nobody called
Removed, not protected (decision 3): `payment-success`, `payment-failure`, `PUT /api/orders/{id}/status`, `PUT /api/orders/{id}/cancel`, `POST /api/orders`, `POST /api/payments`, `GET /api/orders/{id}`, `/api/orders/user/{userId}`, `/api/orders/status/{status}`, `/api/orders/{id}/inventory-records`, `GET /api/payments/order/{orderId}`, `/api/payments/{paymentId}`, `PUT /api/warehouses/unhold`, a `GET /api/warehouses/available/...` that held stock, two order-scoped stock-record reads, `check-email`, `check-verified`, and delivery's `GET /api/deliveries/all/{email}`.

### Small things decided on the way
- `BankAdapter#getAccountByOwnerEmail` puts the email in the URL as a strictly encoded URI variable (`/` → `%2F`, `;` → `%3B`, `+` → `%2B`). Pasted in raw, `x/../victim@…` or `victim@…;x` reached bank as the victim's lookup, and the attacker's orders were then charged to the victim's account. Sign-up also rejects emails with characters outside `A-Z a-z 0-9 . _ % + -`.

## Consequences
- **A role change applies at the next log-in.** Promoting, demoting or disabling someone in the database does nothing to a token already issued, which stays valid for up to 24 hours (`jwt.expiration`). The upside: no database lookup per request, and delivery can trust `validate-token` without its own copy of the accounts. Checking the role in the database on every request, or a token denylist, would close the gap.
- **"Logged in" is still weak.** `POST /api/user/activate` takes only an email and does not check the verification code (scenario A11), so anyone can register and activate an account under any address. Ownership still holds (a user sees only what they created), but an email address proves nothing. This is the next fix, together with the scripts that activate directly.
- **An admin refund does not cancel the order or the delivery.** The order can stay PAID or in transit while the payment is REFUNDED. An after-sales flow (request after delivery, admin approves) is future work. No page calls the refund endpoint today.
- **The admin dashboard depends on configuration.** Without `ADMIN_EMAIL` / `ADMIN_PASSWORD` there is no admin. Changing `ADMIN_PASSWORD` later does not change an existing admin's password; the variables only seed the first one.
- The frontend hides the admin dashboard from non-admins; that is convenience only. A user who edits the saved role sees an empty dashboard whose every request gets 403.
- The 404-for-someone-else's-order rule applies to single orders. Lists and admin endpoints answer 403, so the two codes mean different things on purpose.
- Store's path rules and delivery's `@AdminOnly` are two mechanisms. A new admin endpoint in store must be added to `SecurityConfig` and to `PathAccessRegressionTest`; one in delivery needs the annotation.

## In the code
- store [SecurityConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java), [JwtAuthenticationFilter.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java), [JwtUtil.java](../../backend/storeService/src/main/java/comp5348/storeservice/utils/JwtUtil.java), [Role.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java), [AccountRoleUpgrade.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AccountRoleUpgrade.java), [AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java)
- store ownership: [OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) (`ownOrder`, `cancelOwnOrder`, `createOrderWithPaymentFor`), [OrderService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java) (`cancelOrder`), [OrderNotFoundException.java](../../backend/storeService/src/main/java/comp5348/storeservice/exception/OrderNotFoundException.java), [NotYourOrdersException.java](../../backend/storeService/src/main/java/comp5348/storeservice/exception/NotYourOrdersException.java), [PaymentController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/PaymentController.java)
- store email: [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java), [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)
- delivery [AdminOnly.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnly.java), [AdminOnlyInterceptor.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java), [TokenAuthenticationFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/TokenAuthenticationFilter.java), [TokenValidationClient.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/utils/TokenValidationClient.java)
- frontend [AuthContext.js](../../frontend/src/contexts/AuthContext.js), [AdminDashboard.jsx](../../frontend/src/pages/admin/AdminDashboard.jsx), [adminService.js](../../frontend/src/services/adminService.js)
- Deployment: [compose.yml](../../backend/compose.yml), [.env.example](../../backend/.env.example), [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1), [check-entry.ps1](../../k8s/scripts/check-entry.ps1)
- Tests: store `StoreAuthorizationIntegrationTest`, `OldStoreEndpointsRemovedTest`, `CancelReadsLockedRowIntegrationTest`, `AdminRoleIntegrationTest`, `PathAccessRegressionTest`, `AdminBootstrapStartupTest`, `BankOwnerLookupEncodingIntegrationTest`, `RegisterEmailFormatIntegrationTest`; delivery `DlqAdminRoleIntegrationTest`, `OldDeliveryEndpointsRemovedTest`; frontend `OrderDetailPage.test.jsx`, `AdminDashboard.test.jsx`
- [docs/learning/11-login-ownership-admin-role.md](../learning/11-login-ownership-admin-role.md)
