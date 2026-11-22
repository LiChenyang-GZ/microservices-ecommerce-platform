# 11 · Login, order ownership and the admin role (auth PRs 2 and 3)

**English** | [中文](11-login-ownership-admin-role.zh-CN.md)

> Commits. PR #28 (login and ownership): red `89ff488` → green `8971b27`, `91d73c6`; after the review red `ca8281a` → green `f034e49`. PR #29 (admin role): red `5676dbb` → green `cf39a3d`, `322d228`; after the review red `1198016` → green `20a0cf2`.
> How it was found: the wave 1 review backlog and the auth failure table. In 2026-10, store's refund endpoint really refunded someone else's delivered order during a fix.
> Replay time: **50 minutes**
> Before this: [10 · Service credentials](10-service-credentials.md) (services prove who they are to each other; this note is about users).

---

## 1. Problem

**store let anyone do anything to anyone's orders.** Its security rules ended with `anyRequest().permitAll()`, its JWT filter let a request through when the token was bad, and only Pay Now checked who owned the order. With no token, an expired one, a forged one, or another user's token, anyone could cancel someone else's paid order, read their orders, place an order in their name (the body's `userId` was trusted) and ask for a refund on any paid order. Endpoints with no caller could set an order to PAID without bank, create a payment of any amount or hand back held stock.

There was no admin. The admin dashboard, price and stock changes, refunds and delivery's dead-letter endpoints worked for anyone logged in.

Tests that showed the request **really succeeded** before the fix (red):
- [StoreAuthorizationIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/StoreAuthorizationIntegrationTest.java): no / expired / forged token cancels the order (200, status CANCELLED); a stranger cancels a paid order and delivery is asked; an order placed with someone else's `userId` lands in the victim's name
- [OldStoreEndpointsRemovedTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/OldStoreEndpointsRemovedTest.java): an unpaid order really becomes PROCESSING; a 0.01 payment is really created; held stock really comes back
- [AdminRoleIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/AdminRoleIntegrationTest.java): an ordinary user really changes a price, stock, deletes a warehouse; an owner's refund really reaches REFUND_PENDING
- [DlqAdminRoleIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DlqAdminRoleIntegrationTest.java): an ordinary user resolves a dead letter (it is really marked resolved)
- [CancelReadsLockedRowIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/CancelReadsLockedRowIntegrationTest.java) (from the review): a DELIVERED order is cancelled because the cancel used a stale read

---

## 2. What it is (framework level)

### 2.1 Spring Security: who decides 401 and 403
Every request passes the `SecurityFilterChain` before any controller. Three pieces matter here:
- **Our filter** ([JwtAuthenticationFilter](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java)) only says *who* the caller is: if the token parses, it puts an `Authentication` with authority `ROLE_USER` or `ROLE_ADMIN` in the `SecurityContext`. If not, it does nothing.
- **The rules** ([SecurityConfig#filterChain](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java)) say *what* each path needs: `permitAll()`, `authenticated()` or `hasRole("ADMIN")`. They are checked top to bottom; the first match wins, so admin rules come first.
- **The answer when a rule fails**: anonymous caller → the `AuthenticationEntryPoint` (we set `HttpStatusEntryPoint(401)`; Spring's default for a stateless API without form login is 403, which would confuse the frontend's "log in again" handling). Logged-in caller without the role → `AccessDeniedHandler` → 403.

Why the filter does not reject a bad token itself: the catalogue is public. If the filter answered 401 to an expired token, a visitor with an old token in the browser would be sent to the log-in page for looking at phones. Leaving the request anonymous lets the *rules* decide: public paths work, protected ones get 401.

### 2.2 Ownership belongs in the service layer
The security chain only knows the path and the role. "Is order 42 yours?" needs the database, so it lives where the order is loaded: [OrderProductService#ownOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java). Any new endpoint that reads or changes one order calls it, and a controller cannot forget it by accident, because the service method takes the user's email.

**404, not 403, for someone else's order.** Order ids count up. A 403 says "order 1043 exists but is not yours", which lets anyone count orders and probe ids. Both "missing" and "not yours" throw [OrderNotFoundException](../../backend/storeService/src/main/java/comp5348/storeservice/exception/OrderNotFoundException.java) with the same message. For a user's order *list* the caller supplied the `userId`, so 403 says nothing new ([NotYourOrdersException](../../backend/storeService/src/main/java/comp5348/storeservice/exception/NotYourOrdersException.java)).

**Never trust the body for identity.** `create-with-payment` used to read `userId` from the JSON. Now [OrderProductService#createOrderWithPaymentFor](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) overwrites it with the token's account. Ignoring rather than rejecting a mismatch meant the frontend kept working unchanged.

### 2.3 The JPA trap: a lock query does not refresh what you already read
Found by the review of PR 2. `cancelOwnOrder` first called `ownOrder` (loads the `Order` into the transaction's persistence context), then `OrderService#cancelOrder` called `findByIdForUpdate`. Hibernate does send `SELECT … FOR UPDATE` and does take the lock, but when the row's id is already in the persistence context it **returns the instance it already has**, without copying the newer column values into it. So the cancel decided on the status from before the lock.

The deterministic test does not need two threads: in one transaction, read the order; in a second transaction (`REQUIRES_NEW`) set it to DELIVERED and commit; then cancel. Before the fix the cancel succeeded. The fix is one line after the lock: `entityManager.refresh(order)`. Rule 1 in the code ("lock the row, then read it") was right; reading it *before* the lock in the same transaction broke it silently.

### 2.4 Roles in a JWT
The role is a claim in the token: [JwtUtil#generateToken](../../backend/storeService/src/main/java/comp5348/storeservice/utils/JwtUtil.java) adds `role`, and the filter turns it into `ROLE_ADMIN` or `ROLE_USER`. Three rules:
- **Missing means USER.** Tokens signed before this change have no claim and stay valid for 24 hours. [Role#of](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java) returns ADMIN only for the exact string `ADMIN`.
- **The user cannot write it.** The token is HMAC-signed with store's secret; a token whose payload says `role: ADMIN` but was signed with any other key fails parsing and the request is anonymous.
- **It is read from the token, not the database.** No lookup per request, and delivery can trust `validate-token`'s answer. The cost: a role changed in the database applies at the next log-in.

The column itself: `ddl-auto=update` adds `account.role` as nullable on an existing database, so old rows are NULL. [AccountRoleUpgrade](../../backend/storeService/src/main/java/comp5348/storeservice/config/AccountRoleUpgrade.java) runs `UPDATE account SET role='USER' WHERE role IS NULL` at startup (ADR-21). On the compose database it filled 19 real accounts.

### 2.5 Path checks in a filter vs. after routing
delivery's first version checked `request.getRequestURI().startsWith("/api/admin/")` in its token filter. The review found two bypasses: `/api/admin;x/dlq/delivery` and `/api/%61dmin/dlq/delivery`.
- Tomcat and Spring MVC route on the **decoded** path with `;params` removed: `admin;x` → `admin`, `%61dmin` → `admin`. The request reaches `DeliveryDeadLetterController`.
- `getRequestURI()` returns the **raw** text, which does not start with `/api/admin/`. The check is skipped.

store is not affected: Spring Security's `StrictHttpFirewall` rejects `;` and encoded characters before any rule. delivery has no Spring Security. The fix asks a question that has no such gap: *is the handler Spring chose admin-only?* The controller carries [@AdminOnly](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnly.java) and a `HandlerInterceptor` ([AdminOnlyInterceptor](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java)) runs after routing. Checked on the real Tomcat: all three spellings → 403 for a user; an admin using `;x` gets 200, which proves the variant really routes to the DLQ.

A smaller cousin in store: a rule written for `GET` only does not match `HEAD`, but Spring MVC serves HEAD with the GET handler. `GET /api/orders` was admin-only, `HEAD /api/orders` ran the full query for anyone. Admin rules now have no method.

### 2.6 Encoding a value into a URL path
`BankAdapter#getAccountByOwnerEmail` built `bankUrl + "/by-owner/" + email`. An email of `x/../victim@…` became two extra path segments, and bank's Tomcat normalised `..` away: the request asked for the victim's account, and store saved it as the attacker's. Two Spring details:
- `UriComponentsBuilder.pathSegment(value).encode()` encodes only characters illegal in a segment; `;`, `+` and `=` stay as they are, and `{b}` is taken as a template variable. Expanding a **URI variable after `encode()`** (`path("/by-owner/{email}").encode().buildAndExpand(email)`) encodes strictly.
- Pass a `URI`, not a `String`, to `RestTemplate`; a `String` is encoded again (`%2F` → `%252F`).

---

## 3. Request path

**A user cancels someone else's paid order**
1. `JwtAuthenticationFilter` parses the token → `Authentication(user B, ROLE_USER)`.
2. `SecurityConfig`: `POST /api/store/orders/{id}/cancel` needs `authenticated()` → passes.
3. `StoreController#cancelOrder` → `OrderProductService#cancelOwnOrder` → `ownOrder` finds the order belongs to A → `OrderNotFoundException` → **404**. delivery is never asked, no refund is written, the order is never locked.

**An ordinary user tries the admin refund**
1. Filter → `ROLE_USER`. 2. Rule `POST /api/payments/*/refund → hasRole("ADMIN")` fails → **403** before the controller.

**An admin refunds**
1. Filter → `ROLE_ADMIN` (from the token). 2. Rule passes. 3. `PaymentService#requestRefund` writes a REFUND task if the payment is SUCCESS → 200; otherwise 409 "Nothing to refund: the payment is PENDING".

Evidence from the running system (compose `-p authcheck`, kind): users get 401 / 403 / 404 as above; an admin's refund takes the payment to REFUNDED; smoke and scenarios 01, 02, 03, 06, 07, 08 pass; `check-entry.ps1` 54 / 54 on both entries, including 14 rows that require 401 without a token.

---

## 4. Why this way

### 4.1 404 or 403 for someone else's order: recommended by AI, Christy agreed 🤖
404 hides whether the id exists; 403 is more honest to a confused real user. With sequential ids, existence is worth hiding. Lists use 403 because the caller already named the user.

### 4.2 Role in the token, not checked in the database each time 🤖
| Option | Gives | Costs |
|---|---|---|
| **Claim in the JWT** (chosen) | no database per request; delivery trusts `validate-token` | a role change waits for the next log-in (≤ 24 h) |
| Look up the account on every request | changes apply at once | a query per request; delivery would need store for every call anyway |
| Short tokens + refresh tokens | changes apply within minutes | a refresh flow in the frontend |

### 4.3 Refunds for admins only (decision 4, Christy decided)
Christy pointed out that cancel and refund mean different things: cancel is "I don't want it before it ships", refund is after-sales. A user refund button that worked after delivery was a free-goods button. The real after-sales flow (request, admin approves) is future work; until then only an admin refunds.

### 4.4 The first admin from configuration, never by promotion 🤖
Registration is open and activation does not check the code (A11), so "the account with the admin email" may belong to anyone. AdminBootstrap creates the admin only if the email is unused and never promotes an existing account. A later finding: the email can also be someone's *username*, and a unique-constraint clash would stop store from starting; the admin then gets `admin-xxxxxxxx`.

### 4.5 What the reviews found
PR 2's review: the cancel decided on a stale read (§2.3), anyone logged in could still refund any order, `;` and `{` were not encoded, and the public rule `/api/products/**` also opened stock records. PR 3's review: delivery's prefix check was bypassable (§2.5), the admin email as someone's username stopped startup, HEAD on admin reads, a refund with nothing to refund said "success". Every one got a red test first; the stale read and the bypass were only reasoned by the reviewer, and the red tests proved them real. **A review finds what the failure table did not ask; the red test proves the review is not imagining it.**

---

## 5. Interview topics here

**Conclusion**: authentication is mostly framework; authorisation is the application's job, and the bugs are in the gaps between layers (filter vs. router, read vs. lock, GET vs. HEAD).

| Topic | Where | The call made here |
|---|---|---|
| ➕ Authentication vs. authorisation | `JwtAuthenticationFilter` (who), `SecurityConfig` (what), `ownOrder` (which order) | Three layers, each checking one thing |
| ➕ 401 vs. 403, `AuthenticationEntryPoint` | [SecurityConfig](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java) | Anonymous → 401 (set it; the default is 403); wrong role → 403 |
| ➕ IDOR (insecure direct object reference) | [OrderProductService#ownOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) | Check ownership where the object is loaded; 404 for someone else's |
| ➕ RBAC in a JWT | [Role](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java), `JwtUtil` | Missing role = least privilege; change applies at next log-in |
| ➕ JPA persistence context and locking | [OrderService#cancelOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java) | A lock query returns the instance already loaded; `refresh` after locking |
| ➕ Path normalisation bypass | [AdminOnlyInterceptor](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java) | Check after routing, not on the raw URI |
| ➕ URL encoding / path traversal | [BankAdapter#getAccountByOwnerEmail](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java) | Expand a URI variable after `encode()`; pass a `URI` |
| 📒 Delete what nobody calls | `OldStoreEndpointsRemovedTest` | A red test proves it works today, then it goes |

Scenario anchors:
- "Anyone could cancel or refund anyone's order. I made store require a login, checked ownership in the service layer, and answered 404 for someone else's order so ids can't be probed."
- "A review found that our cancel decided on a stale status: the ownership check had already loaded the order, and Hibernate's lock query returned that copy. A deterministic test proved it, and one `refresh` after the lock fixed it."
- "delivery's admin check compared the raw URL, so `/api/admin;x/...` slipped past it while Spring still routed it. I moved the check to after routing."

---

## 6. Follow-up chain

1. **Why not check ownership in the controller?** → Every new endpoint would have to remember. In the service, the method that loads the order requires the user, so it cannot be skipped by accident.
2. **Why 404 and not 403?** → Sequential ids. 403 confirms the order exists; 404 does not. Lists use 403 because the caller named the user.
3. **A role is in the token. How do you demote someone right now?** → Today you can't; it applies at the next log-in (≤ 24 h). Options: check the role in the database per request, shorten tokens and add refresh tokens, or keep a denylist of token ids.
4. **How did the stale read happen, and how did you prove it?** → The owner check loaded the entity; the lock query returned that instance without new values. A test reads, commits a change from another transaction, then cancels; it cancelled a DELIVERED order before the fix.
5. **Why did store not have the `;` bypass?** → Spring Security's `StrictHttpFirewall` rejects `;` and encoded characters before matching. delivery had no Spring Security and compared the raw URI.

---

## 7. How to say it

- "The filter only says who you are. The rules say what a path needs. The service says whether this order is yours."
- "Someone else's order is a 404, not a 403: with sequential ids, existence is information."
- "Check permissions on the handler the router chose, not on the URL text the client sent."

---

## 8. Further reading

- Spring Security, authorisation of HTTP requests and `ExceptionTranslationFilter`: <https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>, <https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter>
- `StrictHttpFirewall`: <https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html>
- OWASP, Insecure Direct Object Reference: <https://cheatsheetseries.owasp.org/cheatsheets/Insecure_Direct_Object_Reference_Prevention_Cheat_Sheet.html>
- Jakarta Persistence `EntityManager.refresh` and pessimistic locking: <https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/entitymanager>
- Spring `UriComponentsBuilder` encoding: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html#uri-encoding>
- Spring MVC `HandlerInterceptor`: <https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/interceptors.html>

---

## 9. Self-check

1. Remove `exceptionHandling(... HttpStatusEntryPoint(401))` from `SecurityConfig`. What does the frontend see without a token?
<details><summary>Answer</summary>
403 instead of 401. The frontend's interceptor logs out and redirects only on 401, so a user whose token expired would stay on a page where every request fails with "forbidden".
</details>

2. Why does the cancel need `refresh` after the lock, when `findByIdForUpdate` already ran a `SELECT … FOR UPDATE`?
<details><summary>Answer</summary>
The owner check loaded the same order earlier in the same transaction. Hibernate keeps one instance per id per persistence context; the lock query returns that instance and does not overwrite its fields with the newer row. `refresh` re-reads the row now that the lock is held.
</details>

3. (Only answerable if you really understand it) store and delivery both had a path-based admin check. Why could only delivery's be bypassed with `/api/admin;x/...`?
<details><summary>Answer</summary>
store runs Spring Security: its `StrictHttpFirewall` rejects `;` and encoded characters, and its matchers use the same decoded path as Spring MVC. delivery compared `getRequestURI()` (raw) in a plain servlet filter, while Spring MVC routed on the decoded path without `;params`. The two layers read the path differently, and the check sat in the one that disagreed with the router.
</details>

---

## 10. Replay time

**50 minutes**: read §1 and §2 (20 min) → run red: `git checkout 89ff488`, then `./gradlew :storeService:test --tests '*StoreAuthorizationIntegrationTest'` and watch a stranger cancel a paid order (5 min) → `git checkout ca8281a`, run `--tests '*CancelReadsLockedRowIntegrationTest'` and watch a DELIVERED order get cancelled (5 min) → `git checkout main`, run both green (5 min) → self-check (15 min).
