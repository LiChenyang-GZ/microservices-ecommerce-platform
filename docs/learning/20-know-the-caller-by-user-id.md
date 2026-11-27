# 20 · Know the caller by the token's userId

**English** | [中文](20-know-the-caller-by-user-id.zh-CN.md)

> Commits (branch `fix/identity-by-user-id`): red `3bd9932` → green `5bfff7b`; after the review red `dfefa47` → green `ffd7995`.
> How it was found: the review of one spelling per email (Obsidian 75, finding 🟡3).
> Replay time: **15 minutes**
> Before this: [11 · Login, ownership and the admin role](11-login-ownership-admin-role.md), [18 · One spelling per email](18-one-spelling-per-email.md).

---

## 1. Problem

**store knew the caller by the email in the token, which is a snapshot.** The token also carried `userId`, but the filter only read the email and `accountOf` looked it up. On older mixed-case data that was not migrated, one person's token could resolve to another account (and read, pay, cancel its orders) or to none.

Tests that showed the wrong thing **really happened** before the fix (red), in [TokenIdentityIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/TokenIdentityIntegrationTest.java). Each token is signed with store's own key, with `userId` and `subject` pointing at different real accounts, or a `userId` missing or of the wrong type:
- `userId` = A, `subject` = B: A's order was 404, B's was visible, and a new order was recorded for B
- `subject` an email no longer in the table: the owner's own order was 404
- no `userId`, a string, a fraction: 200
- `userId` with no account but `subject` = A's email: treated as A
- after the review: `validate-token` (for delivery and tracking) said `valid: true, userId: null` for a token without a `userId`

---

## 2. What it is (framework level)

### 2.1 Identity is the key, attributes are snapshots
A primary key does not change; an email can (normalisation, a future "change email", old data). A token is a statement frozen at signing time, so anything it says besides the id may be out of date. Resolve the caller by the id.

### 2.2 Type-check claims
JSON has one number type; Jackson turns `5` into `Integer`, `5.0` into `Double`, `"5"` into `String`. An id claim is accepted only as `Integer` / `Long`. Anything else did not come from store's signer, so the token is treated as invalid, not guessed at.

### 2.3 One definition of a valid token
Three entry points read store's tokens: the filter, `validate-token` (delivery, tracking) and, through it, the other services. If one accepts what another refuses, the rule only half holds.

### 2.4 Remove the tempting path
The `userEmail` request attribute and `JwtUserHelper` still offered "the current user's email". With nobody reading them, keeping them only invites the next endpoint to look an account up by the snapshot again.

---

## 3. Request path

1. `Authorization: Bearer …` → `JwtAuthenticationFilter`: verify, read `subject`, `role`, and `userId` (must be `Integer` / `Long`). Missing → anonymous → 401 on protected paths.
2. The filter sets the request attribute `userId`; `StoreController#callerId` reads it.
3. `OrderProductService#accountOf(callerId)` → `findById` → ownership checks compare that account's id with the order's `userId`.
4. delivery calls `validate-token`, which now refuses a token without a `userId` too.

---

## 4. Why this way

### 4.1 Option A (Christy decided)
Know the caller by the id in store; leave delivery (its rows have no `userId`) for a separate change.

### 4.2 What the red showed 🤖
All six went red at the expected step. The first full run after the change did not compile (a missing import) and the result files were the previous run's; clearing them before re-running is what showed it.

### 4.3 What the review found
0 must-fix, 0 should-fix, 6 could-fix. jjwt-jackson reads small ids as `Integer` and large ones as `Long`, so every real id is accepted; all live tokens carry `userId`, so nobody is logged out.
- **Fixed** (red `dfefa47` → green `ffd7995`): `validate-token` refuses a token without a `userId`; `JwtUserHelper` removed; two comments.
- **Tests added** (regression): the mixed token on my orders, Pay Now and cancel; a `userId` of `5.0`.
- **Written down**: a token whose account no longer exists answers 400 / 404 / 500 by endpoint (old); delivery still uses the email.

---

## 5. Interview topics here

**Conclusion**: resolve identity by the immutable key in the signed token; treat everything else in it as a snapshot.

| Topic | Where | The call made here |
|---|---|---|
| ➕ JWT claims: what to trust | `JwtAuthenticationFilter` | the signed `userId` decides who; `subject` is display data |
| ➕ Stable identifiers | `OrderProductService#accountOf` | `findById`, not `findByEmail` |
| ➕ Claim type checking | `JwtAuthenticationFilter#wholeNumber` | only `Integer` / `Long`; `5.0` and `"5"` are not ids |
| ➕ Consistent validation across entry points | `TokenValidationService` | the same "no userId, no token" rule for other services |

Scenario anchors:
- "Our tokens carried both a userId and the email, but we looked users up by the email. After normalising emails that turned out to be a snapshot that could point at the wrong account on old data. I switched store to the signed userId, refused tokens without one, made the token-validation endpoint for other services agree, and removed the helpers that still offered the email."

---

## 6. Follow-up chain

1. **Could an attacker put another userId in a token?** → Only with the signing key, and with the key they could already write any email. Nothing got easier.
2. **Will existing sessions break?** → No: every token store signed since 2025-10-30 has a `userId`, and tokens last 24 hours.
3. **Why refuse `5.0`?** → store never signs it; accepting "numbers whose value is whole" would accept tokens store did not produce.
4. **Why not delivery too?** → Its rows store an email, not a userId; that needs a schema and event change.

---

## 7. How to say it

- "Who you are is your id; your email is just something about you."
- "A token is a snapshot; trust only what cannot go stale."
- "If nobody should use it, delete it."

---

## 8. Further reading

- RFC 7519 §4.1.2 (the `sub` claim is an identifier the issuer chooses): <https://www.rfc-editor.org/rfc/rfc7519#section-4.1.2>
- OWASP JSON Web Token Cheat Sheet for Java: <https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html>

---

## 9. Self-check

1. Why is the email in a token a snapshot, and why does that matter here?
<details><summary>Answer</summary>
It is copied in when the token is signed and never updated. Normalised lookups, old mixed-case rows or a future email change can make it point at another account or none.
</details>

2. Why is a token without a `userId` treated as invalid instead of falling back to the email?
<details><summary>Answer</summary>
store has signed every token with a `userId` since 2025-10-30, so one without it did not come from store's login; guessing by email would bring the snapshot problem back.
</details>

3. (Only answerable if you really understand it) The filter refused tokens without a `userId`. What was still accepting them, and why did it matter?
<details><summary>Answer</summary>
`validate-token`, which delivery and tracking call: it answered `valid: true, userId: null`. The rule only holds if every entry point applies it.
</details>

---

## 10. Replay time

**15 minutes**: read §1 and §2 (6 min) → `git checkout 3bd9932`, run `./gradlew :storeService:test --tests '*TokenIdentityIntegrationTest'` and see the six reds (4 min) → `git checkout 5bfff7b`, run it green (2 min) → self-check (3 min).
