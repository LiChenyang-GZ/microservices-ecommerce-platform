# 14 · A squatted address, and claiming it with a password reset

**English** | [中文](14-claim-by-password-reset.zh-CN.md)

> Commits. PR #36: red `9f63cec` → green `286ecb2`; after the review red `0552938` → green `c5ca529`.
> How it was found: the rate-limit review (scenario "signing up with someone else's address"), first noted in A11's failure table.
> Replay time: **30 minutes**
> Before this: [12 · Activation code](12-activation-code.md) (proving the address) and [13 · Rate limits](13-rate-limits.md) (how often anyone may ask).

---

## 1. Problem

**An account that was never verified still held its address.** Anyone could register `victim@x.com` with their own password. They could not activate it, but the owner's sign-up then answered "Email already exists!", a password reset changed the password and left the account inactive, and log-in said "Please verify your email first" with nowhere to go. The owner could never use their own address.

Tests that showed the wrong thing **really happened** before the fix (red):
- [ClaimByPasswordResetIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClaimByPasswordResetIntegrationTest.java): after a reset with the right code the squatted account was still not verified or active, so the owner's new password could never log in; sign-up gave no hint that the address could be claimed
- [RegisterPage.test.jsx](../../frontend/src/pages/RegisterPage.test.jsx): no link to the password reset
- From the review: [ForgotPasswordPage.test.jsx](../../frontend/src/pages/ForgotPasswordPage.test.jsx), a 429 left the owner on the form

---

## 2. What it is (framework level)

### 2.1 Who owns an address? Whoever proved it
An address belongs to an account only once someone showed they read its inbox. Before that, the row is a claim anyone could have made. So the rule is: **any proof of the inbox settles ownership**, and the person who proves it sets the password.

### 2.2 A reset code is a proof too
The registration code and the reset code both arrive in the same inbox. ADR-25 keeps them apart (a code is for one purpose), but either one proves "I read this inbox". So [AccountService#resetPassword](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java), after the reset code is accepted, also marks a never-verified account verified and active (`markEmailProved`, shared with activation). The password is the one the owner just typed, so the squatter's password stops working at that moment.

### 2.3 Pre-account hijacking, and why the order matters
The dangerous version of this attack is not "the owner is blocked" but "the owner activates the attacker's account": the attacker registers first with a password they know, the owner later proves the address, and the attacker logs in to a verified account. Here that cannot happen through activation only because the verification page is reached by signing up successfully, i.e. by the person who typed the password. That is why `activateAccount` sets no password, the claim goes through the reset (which sets one), and the comment says not to give unverified accounts another way to the verification page.

### 2.4 Why not "sign-up overwrites an unverified account"?
It looks simpler, but it has a race. The owner registers and receives a code. Before they type it, the attacker registers the same address again with their own password and does not ask for a code. The owner types the code they have, and activates an account whose password is the attacker's. Tying the code to one registration attempt would fix it, at the cost of more state.

### 2.5 Every way in has to survive an attacker who uses up the quota
The review found the forgot-password page left the owner on the form when the attacker had used up the address's 3 emails: a 429, though a code was already in the inbox. Now a 429 goes on to the reset page with the message. The deeper problem is left open: the attacker can also guess the one live code dead (5 wrong guesses, whoever makes them), so all the owner's codes are dead. Counting wrong guesses per email and client address is the follow-up.

---

## 3. Request path

**The squatter**
`POST /api/store/user {username, email: victim@…, password: theirs}` → 200, an inactive account.

**The owner**
1. Sign-up → `AccountService#createAccount` finds an unverified row → `EmailNotVerifiedException` → **400** `emailNotVerified: true`, "If it is yours, reset the password to claim it." → the page links to `/forgot-password`.
2. `forgot-password` → a reset code to the owner's inbox (a 429 → the reset page anyway).
3. `reset-password {email, code, newPassword}` → email checks the PASSWORD_RESET code → the password is replaced → `markEmailProved`: verified, active → **200**.
4. Log-in with the new password → 200. The squatter's password → 401.

Evidence from the running system (compose `-p authcheck`, through nginx): exactly these steps, the code read from Mailpit; the squatter's password failed by email and by username; `smoke-order.ps1` passed.

---

## 4. Why this way

### 4.1 Claim by reset (Christy decided)
Chosen at gate 0 over overwriting, expiring, or a pending-registration table: no schema change, the owner can act at once, and the person who proves the inbox sets the password. The cost: the squatter's username stays.

### 4.2 Saying "registered but not verified" 🤖
It tells a stranger that an address signed up and never verified. Log-in already said so, and the owner needs to know what to do; the message names the fix.

### 4.3 What the review found
No must-fix. The two should-fix items were both about **an attacker who uses up the address's quota**: the forgot-password page stranded the owner (fixed), and the code can be guessed dead (written down, the follow-up). It also corrected the docs: the first draft said a later "verify now" button would be harmless, which is the opposite of §2.3.

---

## 5. Interview topics here

**Conclusion**: an address belongs to whoever proves the inbox, and that person sets the password.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Pre-account hijacking | `AccountService#activateAccount`, `#resetPassword` | the claim sets the owner's password; no other way to the verification page |
| ➕ What proves an email address | `markEmailProved` | either code proves the inbox |
| ➕ Race in "overwrite on re-register" | ADR-27 rejected options | the code is not tied to one registration attempt |
| ➕ User enumeration vs a useful message | `EmailNotVerifiedException` | log-in already reveals it; say how to fix it |
| ➕ Lost update: read, wait, save the whole row | `activateAccount` | a narrow window, written in the comment |
| ➕ Denial of service through rate limits and code burning | `ForgotPasswordPage`, R29 | do not strand the user; the deeper fix is per-email counting |

Scenario anchors:
- "Someone could register another person's email with their own password and hold it forever. I made a password reset claim the account: the reset code proves the inbox, and the owner sets the password, so the squatter's password stops working."
- "The tempting fix was letting sign-up overwrite an unverified account. It has a race: the attacker re-registers between the owner's sign-up and activation, and the owner activates the attacker's password."

---

## 6. Follow-up chain

1. **Why does a reset code prove the address?** → It was sent to that inbox. Reading it is the same proof as reading a registration code.
2. **Could the squatter claim it themselves?** → They would need the reset code, which goes to the owner's inbox.
3. **What if you later add a "verify now" button to the log-in page?** → Then the owner could activate an account whose password the squatter set. Unverified users must go through the reset.
4. **Isn't "registered but not verified" an enumeration leak?** → Log-in already reveals it; the message is what lets the owner recover.
5. **Can the attacker still block the owner?** → Yes, by using up the emails and guessing the live code dead. Counting wrong guesses per email and client address stops someone else's guesses from killing your code.

---

## 7. How to say it

- "Whoever proves the inbox owns the address, and sets the password."
- "An unverified account is a claim, not an owner."
- "Every recovery path has to work while someone is spending your quota."

---

## 8. Further reading

- Sudhodanan & Paverd, *Pre-hijacked accounts: An Empirical Study of Security Failures in User Account Creation on the Web* (USENIX Security 2022): <https://www.usenix.org/conference/usenixsecurity22/presentation/sudhodanan>
- OWASP Forgot Password cheat sheet: <https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>
- OWASP Authentication cheat sheet (account enumeration): <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>

---

## 9. Self-check

1. A squatter registered your address. Walk through how you get the account and why the squatter is locked out.
<details><summary>Answer</summary>
Sign-up says the address is registered but not verified and links to the reset. The reset code arrives in your inbox; `reset-password` accepts it, replaces the password with yours and marks the account verified and active. The squatter's password no longer matches, and they never saw the code.
</details>

2. Why does `activate` not set a password, and what must never be added because of that?
<details><summary>Answer</summary>
The account keeps the password typed at sign-up, which is safe only because the verification page is reached by that same person. A second way to the verification page for unverified accounts would let an owner activate a squatter's password.
</details>

3. (Only answerable if you really understand it) "Sign-up overwrites an unverified account" was rejected for a race. Describe the exact sequence, and say what extra state would close it.
<details><summary>Answer</summary>
Owner signs up (password P1), receives code C. Squatter signs up again with password P2 and does not ask for a code, so C stays live. Owner enters C → the account activates with P2. To close it, the code would have to belong to one registration attempt (a registration id or the password hash version stored with the code and checked on activation), so a later sign-up invalidates C.
</details>

---

## 10. Replay time

**30 minutes**: read §1 and §2 (10 min) → `git checkout 9f63cec`, run `./gradlew :storeService:test --tests '*ClaimByPasswordResetIntegrationTest'` and watch the reset leave the account inactive (5 min) → `git checkout 286ecb2`, run it green (5 min) → self-check (10 min).
