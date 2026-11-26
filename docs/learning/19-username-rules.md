# 19 · Username rules

**English** | [中文](19-username-rules.zh-CN.md)

> Commits (branch `fix/username-no-at`): red `789e6e1` → green `dd6570d`; after the review red `e6a9ae7` → green `40bed87`.
> How it was found: the review of one spelling per email (Obsidian 75, finding 🟡1).
> Replay time: **15 minutes**
> Before this: [18 · One spelling per email](18-one-spelling-per-email.md).

---

## 1. Problem

**A username could look like someone else's email, and log-in believed it.** Log-in tries the email first, then the username. Once emails were normalised (note 18), a username `Bob@X.com` reached the account whose email is `bob@x.com`. Sign-up accepted any username: `@`, spaces, a Cyrillic `а` passing for `admin`, control characters, hundreds of characters.

Tests that showed the wrong thing **really happened** before the fix (red):
- [UsernameRulesIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/UsernameRulesIntegrationTest.java): usernames with `@`, a space, a tab, a Cyrillic letter, 2 or 33 characters all got 200; `"  bob "` was stored with its spaces; a bad username with an unverified email got "registered but not verified" instead of the rule; after the review, a name signed up with spaces could not log in typed the same way
- `RegisterPage.test.jsx`: the page sent `bob@x.com`, `ab`, `bob smith`; after the review, it passed a name ending in a full-width space (which store refuses)

---

## 2. What it is (framework level)

### 2.1 Allow-list, not deny-list
Refusing `@` fixes today's bug. An allow-list (`[A-Za-z0-9._-]{3,32}`) also closes the next ones: whitespace, look-alike letters, control characters, length. It is easier to say what is allowed than to list every bad character.

### 2.2 Validate before you reveal
The username is checked before the email is looked up. A request that will fail anyway should not learn whether the email is registered, or cause a bank call.

### 2.3 Two identifiers in one box
"Email or username" in one field only works if the two sets cannot overlap. Every valid email has an `@`; no new username does. The lookup order then no longer matters.

### 2.4 Normalise the same way on both sides
Sign-up trimmed, log-in did not: the stored name and the typed name drifted apart. And `trim()` is not the same function in Java (code points up to U+0020) and JavaScript (all Unicode whitespace). The page must apply store's definition, or it passes names store refuses.

---

## 3. Request path

1. The page trims the username the Java way, checks the rule, and sends the trimmed name.
2. store `AccountService#createAccount`: normalise the email, check its format, trim and check the username (400 with the rule), then "email exists / not verified", then "username exists", then save and open the bank account.
3. Log-in: `getAccountByIdentifier` / `verifyLogin` → email (normalised), else `byUsername` (trimmed).

---

## 4. Why this way

### 4.1 Option B (Christy decided)
The allow-list, sign-up only; older accounts untouched (locally only the two bootstrapped admins have an `@`, their own email).

### 4.2 What the red showed 🤖
The same wrong-reason red as in note 18: the fake bank returned one account number for every sign-up in a test, so the second sign-up hit the bank-number constraint (500). Each sign-up now gets a fresh number.

### 4.3 What the review found
0 must-fix, 0 should-fix, 5 could-fix. New usernames can no longer be mistaken for emails, and `ADMIN_EMAIL` can no longer be taken at sign-up.
- **Fixed** (red `e6a9ae7` → green `40bed87`): log-in trims the username as sign-up does; the page trims only what Java trims and sends that name; a regression test that `ADMIN_EMAIL` cannot be signed up as a username; two stale comments.
- **Written down**: no reserved names (`admin` can be registered, with no role); racing sign-ups for one username give a 500; usernames are still case-sensitive.

---

## 5. Interview topics here

**Conclusion**: say what is allowed, check it before anything else, and use one definition on both sides.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Allow-list validation | `AccountService#USERNAME_FORMAT` | a small allowed alphabet instead of chasing bad characters |
| ➕ Homoglyphs | `UsernameRulesIntegrationTest` | Cyrillic `а` passing for `admin` is refused by the ASCII alphabet |
| ➕ Validation order and information leaks | `createAccount` | the username rule comes before anything about the email |
| ➕ Client and server validation | `RegisterPage.jsx` | the page helps; store decides; both must mean the same thing (`trim` differs) |

Scenario anchors:
- "After we normalised emails, a username shaped like an email could be found as someone else's account at log-in. We made usernames an allow-list, 3 to 32 ASCII letters, digits, dot, underscore, hyphen, so the two kinds of identifier can never overlap. The review then caught that log-in didn't trim like sign-up, and that JavaScript's trim removes more than Java's."

---

## 6. Follow-up chain

1. **Why not just refuse `@`?** → It fixes the overlap, but leaves spaces, look-alike letters and control characters.
2. **Why check the username before the email?** → So a doomed request learns nothing about the email and calls nobody.
3. **What about existing usernames?** → Untouched; the rule is for sign-up. Locally only admins have an `@`, and it is their own email.
4. **Is `admin` reserved?** → No. Roles come from the database and the token, not the name; reserving names is a separate choice.

---

## 7. How to say it

- "Allow-list the alphabet; don't chase bad characters."
- "Two identifiers in one box need disjoint shapes."
- "Client validation is a courtesy; it must agree with the server's."

---

## 8. Further reading

- OWASP Input Validation Cheat Sheet (allow-lists): <https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html>
- Unicode Technical Report #36, Security Considerations (confusable characters): <https://www.unicode.org/reports/tr36/>

---

## 9. Self-check

1. Why can log-in no longer confuse a new username with an email?
<details><summary>Answer</summary>
Every valid email has an `@` and no new username can, so the email lookup never finds an account through a username's text.
</details>

2. Why is the username checked before the email is looked up?
<details><summary>Answer</summary>
A request with a bad username fails anyway; checking it first means it never learns "email already exists / not verified" and never calls bank.
</details>

3. (Only answerable if you really understand it) The page and store both "trim". Why could the page still pass a name store refuses?
<details><summary>Answer</summary>
JavaScript's `trim()` removes all Unicode whitespace (full-width space, NBSP); Java's removes only code points up to U+0020. `newuser` plus a full-width space passed the page and failed at store. The page now trims only what Java trims.
</details>

---

## 10. Replay time

**15 minutes**: read §1 and §2 (6 min) → `git checkout 789e6e1`, run `./gradlew :storeService:test --tests '*UsernameRulesIntegrationTest'` and see the 200s (4 min) → `git checkout dd6570d`, run it green (2 min) → self-check (3 min).
