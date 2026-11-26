# ADR-31 · One spelling per email address

**English** | [中文](31-one-spelling-per-email.zh-CN.md)

**Status:** Accepted. Amends ADR-27 (known limit "email case is not normalised") and ADR-30 (consequence "the email typed with other capitals, which the database does not match").

## Context
store looked emails up and stored them exactly as typed. Postgres compares text case-sensitively, so the unique column on `account.email` let `Victim@x.com` and `victim@x.com` be two accounts, each with its own bank account, both mailing codes to one inbox. The rate limits already counted by the trimmed, lower-cased address (ADR-26); the accounts did not.

The everyday failure is smaller and more common: **phones capitalise the first letter.** Someone who signed up as `victim@x.com` typed `Victim@x.com` on their phone, `findByEmail` found nothing, login fell back to the username and answered "Email or password incorrect"; "forgot password" answered "Email does not exist". Spaces around a pasted address failed the same way, and sign-up rejected them outright.

Before choosing, the local databases were checked (both compose projects): no two accounts differ only in case, and every stored email (store accounts, delivery orders, email's codes) is already lower case and trimmed. Nothing needs migrating here.

## Decision (Christy decided: option A)

- **`EmailAddress#normalize`**: `trim()` then `toLowerCase(Locale.ROOT)`; `null` stays `null`. `Locale.ROOT` because in Turkish `"I".toLowerCase()` is a dotless `ı`. `AuthRateLimits` now uses the same function, so the counters and the accounts cannot drift apart.
- **Two choke points**, so a caller added later cannot forget:
  - write: `Account#setEmail` normalises. Whatever path saves an account, the column holds one spelling, and the existing case-sensitive unique constraint therefore holds one account per mailbox;
  - read: `AccountRepository#findByEmail` normalises, then matches the stored column exactly (`findByStoredEmail`). The unused `findByEmailOrUsername`, which would have bypassed it, is gone.
- **What store sends on is normalised too.** email keeps codes by address, so `AccountService` normalises at the top of `createAccount`, `activateAccount`, `sendVerificationCode`, `sendPasswordResetEmail` and `resetPassword`: the code is stored and checked under one spelling, and bank opens the account for it. `createAccount` normalises before the format check, so surrounding spaces are accepted.
- `AdminBootstrap` normalises `ADMIN_EMAIL`, so `Admin@Shop.com` meets an existing `admin@shop.com` (not promoted, as before) instead of creating a second account for that inbox.
- email, bank and delivery do not change: they only see addresses that store sends, which are now normalised.
- No schema change and no automatic migration (options B and C were rejected): a functional `lower(email)` index needs schema management that store does not have (`ddl-auto=update`), and duplicates need a person to decide which account stays.

## Consequences
- One account per mailbox. A squatter who registers `VICTIM@x.com` holds the same row as `victim@x.com`, so the owner is told to claim it (ADR-27) instead of getting a separate account.
- Login, code requests, activation and password reset work whatever capitals or surrounding spaces are typed.
- The part before the `@` may be case-sensitive by RFC 5321. No mainstream provider treats it so; an address that really depends on case would receive mail at the lower-case spelling. The same trade-off as almost every site.
- Usernames are still case-sensitive (`Bob` and `bob` are two users). Not changed here.
- Nothing in the database enforces the spelling: a row inserted by hand with capitals would not be found. The two choke points are the guard.
- **Usernames that look like someone else's email.** Login and the other identifier lookups try the email first (now normalised), then the username (exact). Someone whose username is `Bob@X.com` while another account's email is `bob@x.com` now reaches that other account and cannot log in with the username (they still can with their email), and the failed try counts against the other account's login limit. Sign-up accepts any username, `@` included; refusing `@` in usernames is the real fix, a separate item.
- Two sign-ups racing with `A@x.com` and `a@x.com` now meet the same unique constraint, like two sign-ups with the same spelling did before: one account, one bank account (the insert fails before bank is called). The second request gets 500, not "Email already exists": an old gap, unchanged.
- Pay Now looks the bank account up by the account's stored email, not the token's (review 75): a token signed before a migration may carry other capitals, and bank, matching exactly and opening an empty account when it finds none, would give the user a second account.

### Older data: migrate before this release
**Releasing on a database that holds mixed-case emails without migrating is worse than not releasing.** Every spelling is normalised before the lookup, so a row stored as `Victim@x.com` is never found: its owner cannot log in, and anyone can register `victim@x.com` as a new, unverified row that from then on catches the owner's log-ins and old tokens (they cannot activate it, so it locks the owner out rather than letting them in). If both `Victim@x.com` and `victim@x.com` exist, the first one's tokens resolve to the second account. The local databases (both compose projects) are clean; run the check on any other environment, kind included, first.

1. **Check** (all must return no rows):
   ```sql
   -- store_db: two accounts for one mailbox
   SELECT lower(btrim(email, E' \t\r\n')), count(*) FROM account
    WHERE email IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
   -- bank_db: owner_name has no unique constraint, so check it too
   SELECT lower(btrim(owner_name, E' \t\r\n')), count(*) FROM bank_accounts
    WHERE owner_name LIKE '%@%' GROUP BY 1 HAVING count(*) > 1;
   ```
   `btrim` is given the whitespace list because by default it trims only spaces, while Java's `trim()` also drops tabs and newlines.
2. **Resolve each duplicate group by hand, before any update.** Usually both accounts were activated from the same inbox, so they belong to one person. A person decides which account keeps the address. The other one is either retired (set its email to `retired-<id>@invalid` and its bank owner name to the same, so neither is found again) or merged (its orders' `user_id` moved to the kept account; what happens to the second bank balance is a decision, not a script). Check the delivery orders of both before merging: after the update they are visible to whoever holds the kept address.
3. **Update**, one transaction per database, in this order (they are three databases, so not one transaction):
   ```sql
   -- store_db (a unique-constraint error here means a duplicate group was missed: stop)
   UPDATE account SET email = lower(btrim(email, E' \t\r\n')) WHERE email <> lower(btrim(email, E' \t\r\n'));
   -- bank_db (only owner names that are addresses; SYSTEM, Online Store and the seeded customers stay)
   UPDATE bank_accounts SET owner_name = lower(btrim(owner_name, E' \t\r\n'))
    WHERE owner_name LIKE '%@%' AND owner_name <> lower(btrim(owner_name, E' \t\r\n'));
   -- delivery_db
   UPDATE delivery SET email = lower(btrim(email, E' \t\r\n')) WHERE email <> lower(btrim(email, E' \t\r\n'));
   ```
4. **Verify**: no account whose email is not in the stored form (the lower-case version of `AccountService`'s format check); fix any row it lists by hand.
   ```sql
   SELECT id, email FROM account WHERE email !~ '^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$';
   ```

(The SQL above was run on the local authcheck databases on 2026-11-26 inside `BEGIN … ROLLBACK`: the checks and the verify returned no rows, the three updates touched 0 rows.)

bank and delivery matter because store looks the bank account up by owner email (when an account has no number yet) and delivery decides who owns an order by the email in the login token. Tokens signed before the migration carry the old spelling: store uses the stored spelling (the read choke point; Pay Now, above), but delivery compares exactly, so those users should log in again. email's codes live 5 minutes and need nothing.

## In the code
- store [EmailAddress.java](../../backend/storeService/src/main/java/comp5348/storeservice/utils/EmailAddress.java), [Account.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/Account.java) (`setEmail`), [AccountRepository.java](../../backend/storeService/src/main/java/comp5348/storeservice/repository/AccountRepository.java) (`findByEmail`, `findByStoredEmail`), [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java), [AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java), [AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java) (`fingerprint`), [OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) (`retryOrderPayment`)
- Tests: store `EmailAddressTest`, `EmailCaseIntegrationTest`, `ClaimByPasswordResetIntegrationTest`, `AdminBootstrapStartupTest`, `EmailLookupArchitectureTest` (only `AccountRepository` calls `findByStoredEmail`)
- [docs/learning/18-one-spelling-per-email.md](../learning/18-one-spelling-per-email.md)
