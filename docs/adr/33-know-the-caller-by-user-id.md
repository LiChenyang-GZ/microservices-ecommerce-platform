# ADR-33 · store knows the caller by the token's userId, not its email

**English** | [中文](33-know-the-caller-by-user-id.zh-CN.md)

**Status:** Accepted. Amends ADR-24 (the JWT filter put the email on the request, and ownership was checked through it) and ADR-31 (older mixed-case data: tokens resolving to the wrong account in store).

## Context
store's JWT carries `userId`, `subject` (the account's email) and `role`. The filter read only the email and put it on the request; `OrderProductService#accountOf` looked the account up by it, and every ownership check (place an order, read one, list mine, Pay Now, cancel) went through that account.

An account is its id; the email is one of its fields, and the one in a token is a **snapshot from signing time**. After ADR-31 normalised emails, review 75 pointed out what a snapshot does on older data that was not migrated: with rows `Victim@x.com` (id 5) and `victim@x.com` (id 6), id 5's token resolved to id 6 and could read, pay and cancel its orders; with only `Victim@x.com`, a valid token found nobody. A future "change email" would break every live token the same way.

## Decision (Christy decided: option A)

- **The filter reads `userId` and requires a JSON integer** (`Integer` or `Long` as jjwt-jackson reads it). A token without one, or with a string, a fraction or `5.0`, stays anonymous (protected paths 401). Every token store signs has had a `userId` since 2025-10-30 and tokens last 24 hours, so no live session is cut off.
- The id goes on the request (`JwtAuthenticationFilter.USER_ID`); `StoreController` passes it as `callerId`; `OrderProductService#accountOf(Long)` is `findById`. The five user paths (place, read one, list mine, Pay Now, cancel) all go through it.
- **The `userEmail` request attribute is gone**, and so is `JwtUserHelper` (it handed the token's email out as "the current user"; nothing called it). The principal is still the email, for Spring and logs only.
- **`validate-token` applies the same rule** (review 83): a token without a `userId` is invalid there too, so store, delivery and tracking agree on what a valid token is.
- delivery is unchanged: a delivery row has an email and no `userId`, so it still matches the token's email. Changing that means a schema and event change; a separate item.
- Rejected: also checking on every request that the token's email equals the stored one (option B: no "change email" exists, and it adds a failure without a case it fixes).

## Consequences
- In store, a token always means the account it was signed for. Older mixed-case data no longer makes one person's token act as another's or as nobody; ADR-31's migration is still needed for log-in and for delivery.
- Forging a token for someone else still needs `jwt.secret`, exactly as before (with the secret, `subject` could be any email too). Nothing becomes easier to forge.
- A token whose `userId` has no account (deleted by hand: there is no delete path) answers as before: 400, 404 or 500 depending on the endpoint (an old inconsistency, unchanged).
- delivery still decides ownership by the token's email; older mixed-case data or a future "change email" would still confuse it.
- The Pay Now log line records the id instead of the email.

## In the code
- store [JwtAuthenticationFilter.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java) (`USER_ID`, `wholeNumber`), [StoreController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/StoreController.java) (`callerId`), [OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) (`accountOf`), [TokenValidationService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/TokenValidationService.java)
- Tests: store `TokenIdentityIntegrationTest`; the callers in `CancelReadsLockedRowIntegrationTest`, `KeyConflictIntegrationTest`, `PayNowIntegrationTest`, `PayRetryAccountIntegrationTest` pass the buyer's id (`StoreIntegrationTest#buyerIdOf`)
- [docs/learning/20-know-the-caller-by-user-id.md](../learning/20-know-the-caller-by-user-id.md)
