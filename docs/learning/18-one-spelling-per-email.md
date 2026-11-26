# 18 · One spelling per email

**English** | [中文](18-one-spelling-per-email.zh-CN.md)

> Commits (branch `fix/email-case-normalize`): red `8602a4e` → green `b107644`; after the review red `31ac233` → green `d2b7241`.
> How it was found: the squatting work (Obsidian 58, decision 3), written down then as a known limit (ADR-27).
> Replay time: **20 minutes**
> Before this: [13 · Rate limits](13-rate-limits.md), [14 · Claiming a squatted address](14-claim-by-password-reset.md).

---

## 1. Problem

**`Victim@x.com` and `victim@x.com` were two accounts.** store stored and looked up the address exactly as typed, and Postgres compares text case-sensitively, so the unique column let both in: two accounts, two bank accounts, two codes in one inbox. The everyday version is smaller: **phones capitalise the first letter**, so someone who signed up as `victim@x.com` typed `Victim@x.com` on their phone and got "Email or password incorrect"; "forgot password" said the address did not exist. A pasted address with spaces failed the same way.

Tests that showed the wrong thing **really happened** before the fix (red):
- [EmailCaseIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailCaseIntegrationTest.java): a second sign-up in other capitals got 200; log-in in capitals got 401; the stored address kept its capitals; forgot-password in capitals got 400; sign-up with spaces got 400; the repository found nothing for another spelling
- [ClaimByPasswordResetIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClaimByPasswordResetIntegrationTest.java): a squatter's `VICTIM@…` and the owner's `victim@…` became two accounts
- [AdminBootstrapStartupTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/AdminBootstrapStartupTest.java): `ADMIN_EMAIL=Taken@…` next to an ordinary `taken@…` made **a second account for that inbox, and made it admin**
- [EmailAddressTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailAddressTest.java): the normaliser (a stub in the red commit) left capitals and spaces

---

## 2. What it is (framework level)

### 2.1 Canonicalise at the boundary
One real thing (a mailbox) had many spellings. The fix is to pick one canonical form and turn every input into it before it is stored or compared. The rate limits already did this for their keys (note 13); the accounts did not, so the two disagreed.

### 2.2 Choke points instead of call sites
Normalising in each service method works until someone adds a method. Here there are two places every path goes through: the entity's setter (every write) and the repository's lookup (every read). A Spring Data repository can have a `default` method, so `findByEmail` normalises and calls an exact `@Query`; an ArchUnit test makes sure nothing else calls that exact query.

### 2.3 The constraint follows the data
The unique column is case-sensitive. Once every write stores one spelling, "unique" means "one account per mailbox" without changing the schema. A functional index on `lower(email)` would say the same in the database, but store has no schema management (`ddl-auto=update`), so it was left out.

### 2.4 `Locale.ROOT`
`String#toLowerCase()` uses the server's language. In Turkish, `"I"` becomes a dotless `"ı"`, so `TIM@X.COM` would not meet `tim@x.com`. Identifiers are lower-cased with `Locale.ROOT`.

### 2.5 Data you did not write
A rule added today does not change rows written yesterday. Hibernate loads fields directly, so an old `Victim@x.com` row is never found by a normalised lookup. The local data was checked first (clean); other environments get a check, a manual step for duplicates, and an update, in ADR-31.

---

## 3. Request path

1. A phone posts `/api/user/login {email: "Victim@x.com", …}`.
2. `AccountController#loginUser` → `AccountService#getAccountByIdentifier` → `AccountRepository#findByEmail("Victim@x.com")` → `EmailAddress#normalize` → `victim@x.com` → `findByStoredEmail` finds the account. The rate-limit key is `account:<id>`, the same whatever was typed.
3. `verifyLogin` goes through the same lookup, checks the password, and the token carries the stored `victim@x.com`.
4. Sign-up, code requests, activation and password reset normalise at the top of their `AccountService` method, so email stores and checks the code under `victim@x.com`, and bank opens the account for it.

---

## 4. Why this way

### 4.1 Option A (Christy decided)
Normalise in store only, with the two choke points; no schema change, no automatic migration (duplicates need a person), email unchanged (it only sees what store sends).

### 4.2 What the red showed 🤖
The admin case was worse than planned: an `ADMIN_EMAIL` in other capitals created a second, admin account for an inbox that already had an ordinary one. One test first went red for the wrong reason: the fake bank returned the same account number for both sign-ups, so the second insert hit the bank-number constraint (500) instead of showing "two accounts"; the test now gives only the squatter a bank account.

### 4.3 What the review found
0 must-fix, 1 should-fix, 6 could-fix. Nothing bypasses the two choke points; log-in always issues the token for the account whose password it checked; the rate-limit keys now match the accounts exactly.
- **Should-fix (docs)**: the migration steps were incomplete. bank's `owner_name` has no unique constraint, so a blind update could leave two rows for one owner and break bank's lookup; ADR-31 now checks bank too, says what to do with the account that loses, runs the three databases one at a time, trims tabs as Java does, and ends with a verify query. The SQL was run inside `BEGIN … ROLLBACK` on the local databases.
- **Fixed** (red `31ac233` → green `d2b7241`): Pay Now looked the bank account up by the email in the token. With a token signed before a migration, bank would not find the account and would open an empty one. It now uses the stored email. Two regression tests: the admin logs in with any case; only `AccountRepository` calls the exact lookup.
- **Written down**: a username that looks like someone else's email in other capitals now finds that other account first (refusing `@` in usernames is the real fix); two racing sign-ups get one account and a 500; releasing without migrating old mixed-case data locks those owners out.

---

## 5. Interview topics here

**Conclusion**: one thing, one spelling: normalise where every path passes, not where you remember to.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Canonicalisation | `EmailAddress#normalize` | trim + `toLowerCase(Locale.ROOT)`; the same function for accounts and rate-limit keys |
| ➕ Choke points | `Account#setEmail`, `AccountRepository#findByEmail` | a `default` repository method in front of an exact `@Query`, guarded by ArchUnit |
| ➕ Unique constraints and case | `account.email` | a case-sensitive constraint is enough once every write is canonical |
| ➕ Locale bugs | `EmailAddressTest` | the Turkish dotless i |
| ➕ Data migrations | ADR-31 | check, resolve duplicates by hand, update per database, verify |

Scenario anchors:
- "Our login looked emails up exactly as typed, so a phone that capitalised the first letter could not log in, and `Victim@` and `victim@` were two accounts. I normalised at the two places every path passes, the entity setter and the repository lookup, and the review caught one path that still used the token's spelling for the bank lookup."

---

## 6. Follow-up chain

1. **Why not `findByEmailIgnoreCase`?** → It compares `upper(email)` at query time: no index on the column helps, and two rows differing in case could still be inserted.
2. **Why not a `lower(email)` unique index?** → Right in principle; store has no migrations tool yet, and with every write canonical the existing constraint already holds.
3. **Is the local part really case-insensitive?** → RFC 5321 allows it to be case-sensitive; no mainstream provider does that. Same trade-off as almost every site.
4. **What about old data?** → Check for duplicates in store and bank, decide by hand, update store → bank → delivery, verify. Releasing without it is worse than not releasing.
5. **Old tokens?** → store resolves them through the normalised lookup; Pay Now uses the stored email; delivery compares exactly, so those users log in again.

---

## 7. How to say it

- "Normalise at the boundary, compare canonical forms."
- "Put the rule where every path passes, then test that nothing goes around it."
- "A new rule does not rewrite old rows."

---

## 8. Further reading

- RFC 5321 §2.4 (the local part may be case-sensitive): <https://www.rfc-editor.org/rfc/rfc5321#section-2.4>
- `String#toLowerCase(Locale)` Javadoc (the Turkish example): <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html#toLowerCase(java.util.Locale)>
---

## 9. Self-check

1. Why is the existing case-sensitive unique constraint enough now?
<details><summary>Answer</summary>
`Account#setEmail` stores every address in one spelling, so two spellings of one mailbox become the same value, and the constraint rejects the second.
</details>

2. Why does `AccountService` still normalise in five methods if the repository already does?
<details><summary>Answer</summary>
Those methods pass the address on to email or bank. email stores and checks codes by address, so the code must be stored under the same spelling it is checked with.
</details>

3. (Only answerable if you really understand it) The code was green. Why was Pay Now still wrong in an environment that had just been migrated?
<details><summary>Answer</summary>
It sent the email from the login token to bank. A token signed before the migration still has the old capitals, bank matches exactly and opens a new empty account when it finds none, and store would save that number. Now Pay Now uses the account's stored email.
</details>

---

## 10. Replay time

**20 minutes**: read §1 and §2 (8 min) → `git checkout 8602a4e`, run `./gradlew :storeService:test --tests '*EmailCaseIntegrationTest'` and see the second account and the 401 (5 min) → `git checkout b107644`, run it green (3 min) → self-check (4 min).
