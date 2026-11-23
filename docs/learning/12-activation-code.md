# 12 · Activation needs the emailed code (A11)

**English** | [中文](12-activation-code.zh-CN.md)

> Commits. PR #31: red `4d6cdef` → green `0cb47b3`, `f762b70`; after the review red `ba883eb` → green `44de260`.
> How it was found: while writing auth PR 2's failure table (scenario A11), recorded as "not in this PR".
> Replay time: **40 minutes**
> Before this: [11 · Login, ownership and the admin role](11-login-ownership-admin-role.md) (who may touch which order; this note is about proving who you are in the first place).

---

## 1. Problem

**Anyone could activate an account registered with someone else's email.** The verification page checked the code with email's public `verify-code`, then called store's `activate` with the email only. store never saw the first step, so `POST /api/store/user/activate {"email":"victim@…"}` activated any account. After ADR-24 every order belonged to an account, but the account's email proved nothing.

The codes were weak too: any SENT code for the address passed, of either kind (a password-reset code activated an account); guesses were unlimited; a resend left old codes live; codes came from `java.util.Random`. And locally nobody got the mail at all: without Gmail settings sending failed, so the scripts called `activate` with an email only, the very hole this fixes.

Tests that showed the wrong thing **really happened** before the fix (red):
- [ActivationNeedsCodeIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ActivationNeedsCodeIntegrationTest.java): no code, a blank code, a wrong code → 200 and the account active; email answering 401, resetting the connection or hanging → still activated; with the right code store never called email at all
- [VerificationCodeRulesTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerificationCodeRulesTest.java): a reset code passed the registration check and vice versa; the right code still passed after 5 wrong guesses; the old code still passed after a resend
- [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java): `verify-code` without or with a wrong service token → 200
- [EmailVerificationPage.test.jsx](../../frontend/src/pages/EmailVerificationPage.test.jsx): the page sent only the email to `activate` and still called email directly
- From the review: [VerifyEndpointErrorsTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerifyEndpointErrorsTest.java) (email's own error answered 400 "wrong code"), a 404 from email became "wrong code" in store, a resend that failed to send killed the code the user still had

---

## 2. What it is (framework level)

### 2.1 Never let the client report that a check passed
The old flow was "browser asks email, browser tells store". Anything the browser says can be skipped or forged; only facts store checks itself count. The fix moves the check to a server-to-server call: the browser hands the **code** to store, and store asks email with its service token ([AccountService#activateAccount](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)). The same pattern already existed for password reset.

The alternative was a signed one-time token from email that the browser passes on. It works (that is what OAuth's authorization code or a JWT does), but it needs another shared secret, an expiry and a table of used tokens against replay, all to carry a fact store can simply ask for.

### 2.2 What makes a 6-digit code safe
Six digits is only a million values. It is safe only with the rules around it ([EmailService#checkCode](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java)):
- **Few attempts**: 5 wrong guesses kill the code, so a guesser has 5 in a million, not unlimited tries.
- **One live code**: if every resend left the old code alive, ten resends would give ten targets. A new code expires the older ones.
- **One purpose**: a code for registration is not a code for resetting a password.
- **Short life**: 5 minutes.
- **Unpredictable**: `java.util.Random` is a linear congruential generator with 48 bits of state; a few outputs are enough to compute the next ones. `SecureRandom` draws from the operating system's entropy.

### 2.3 A counter needs a lock, and the lock needs a transaction
"Read failed_attempts, add one, write it back" in two requests at once loses an update: both read 3, both write 4. The same race lets one right code be used twice. [EmailOutboxRepository#lockCodes](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java) reads the live codes with `@Lock(PESSIMISTIC_WRITE)` (`SELECT … FOR UPDATE`): the second request waits until the first commits, then sees its changes.

Two details:
- **The transaction must be on the method called through the Spring proxy.** `checkCode` is private and has no annotation; `verifyCode` (public, called from the controller) is `@Transactional`, so the lock lives as long as the whole check. A `@Transactional` on a private method, or on a method called from inside the same class, does nothing: the call never passes the proxy.
- **Lock in a fixed order.** Two transactions locking rows A then B, and B then A, deadlock; Postgres kills one. `order by o.id` makes every transaction lock in the same order.

The review asked "does the lock really work?". Two concurrent tests answer it: with `@Lock` removed they failed 3 runs out of 3.

### 2.4 Classify errors by whose fault they are
| Cause | Answer | Why |
|---|---|---|
| wrong or expired code | 400 | the user can fix it |
| email refused store's token | 503 + ALERT | an operator must fix it; the user can only wait |
| email down, slow, 404, 5xx | 503 + log | same |
| email's own failure while checking | 500 from email → 503 from store | same |

Before the review, email answered 400 for its own exceptions and store treated every 4xx as "wrong code": a broken database looked like a typo, the user kept retrying and nobody was alerted. The rule is to map **only** the status that means "user error" to a user error, and everything else to "try later".

### 2.5 Destroy the old state only after the new one exists
The first version expired old codes, then saved and sent the new one. If sending failed, the user's working code was already gone. Now [EmailService#markSent](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) expires the others (`expireOtherLiveCodes`, excluding the new row) only after the mail went out. The same idea as writing a new file and renaming it over the old one.

### 2.6 A fake SMTP server for local runs
Mailpit accepts SMTP on 1025 and shows every mail on a web page and a REST API on 8025. compose and kind point email at it through `MAIL_HOST` / `MAIL_PORT` / `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS`; the scripts read the code from `GET /api/v1/search?query=to:"…"` and `GET /api/v1/message/{id}` ([mailpit.ps1](../../backend/docker/mailpit.ps1)). So the scripts test the real sign-up path instead of a back door. It is local only: its API has no login, so in a shared cluster it would hand every code to anyone.

---

## 3. Request path

**An attacker activates someone else's account**
1. `POST /api/store/user/activate {"email":"victim@…"}` → `AccountController#activateAccount` → `AccountService#activateAccount`: no code → `INVALID_CODE` → **400**. email is never asked.
2. With a guessed code: store asks email; `EmailService#checkCode` locks the live REGISTRATION code, finds no match, counts one wrong guess → email 400 → store **400**. The fifth wrong guess marks the code EXPIRED.
3. Directly at email: `POST /api/email/verify-code` through the entry → the entry's own **404**; inside the network → `ServiceTokenFilter` **401**.

**The real user activates**
1. Registration page: store creates the account, email sends a code (Mailpit locally).
2. Verification page → store `activate {email, code}` → store asks email with its token → email locks the row, marks it VERIFIED, commits → 200 → store sets the account active → **200** → login page.

**email is down**
store's call fails within 5 seconds → `EMAIL_UNAVAILABLE` → **503** "try again later"; the account stays inactive and the code unspent.

Evidence from the running system (compose `-p authcheck`, kind): smoke and scenario 01 pass through Mailpit on compose, smoke and scenario 06 on kind; no code / wrong code → 400; `verify-code` direct → 401, through the entry → 404; registering in a browser, the page called only store's `activate`; `check-entry.ps1` 54/54 on both entries.

---

## 4. Why this way

### 4.1 store asks email, not a signed token (Christy decided)
Recommended by AI, chosen by Christy at gate 0. It reuses the password-reset pattern and adds no secret or table. The cost: activation depends on email being up, which it already did for sending the code.

### 4.2 Mailpit, not scripts that edit the database (Christy decided)
The cheaper option was scripts setting `active = true` in SQL. Christy chose Mailpit: the scripts then cover the real activation path, and local sign-up in the browser works for the first time. The cost: a new container, four new settings, and a changed default for anyone who had Gmail configured (the startup WARN).

### 4.3 Five wrong guesses kill the code 🤖
Without a limit, a guesser has unlimited tries at a million values. With one, a guesser can only burn the code: five wrong guesses per second keep the victim from ever activating or resetting. That is a nuisance, not a takeover, and rate limiting (by address and IP) is the real fix, recorded as next.

### 4.4 What the review found
No must-fix. The useful findings were in the edges: email's own errors and a 404 were reported as "wrong code" (§2.4); expiring before sending lost the user's code when sending failed (§2.5); the lock had no test (§2.3); Mailpit sat in `k8s/base`, where a cloud overlay would pick it up; a `.env` with only Gmail credentials now silently sent to Mailpit. **The review asks what the table did not: what happens when the parts around the fix fail.**

---

## 5. Interview topics here

**Conclusion**: a verification code is only as strong as the rules around it, and the server must check it itself.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Trust boundary: the client cannot vouch for a check | [AccountService#activateAccount](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) | store asks email itself; the browser only passes the code |
| ➕ OTP design: attempts, lifetime, single live code, purpose | [EmailService#checkCode](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) | 5 attempts, 5 minutes, one per kind, `SecureRandom` |
| ➕ Lost update, `SELECT … FOR UPDATE`, lock order | [EmailOutboxRepository#lockCodes](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java) | pessimistic lock in id order; concurrent tests prove it |
| ➕ Spring `@Transactional` and self-invocation | `EmailService#verifyCode` → `checkCode` | annotate the public method the proxy sees |
| ➕ 4xx vs 5xx, failure classification | `AccountService#activateAccount`, `EmailController#verifyCode` | only 400 is the user's fault |
| ➕ Ordering side effects | `EmailService#markSent` | expire the old code after the new one is sent |
| 📒 Dev/prod parity with a mail sink | [mailpit.yaml](../../k8s/base/mailpit.yaml), [compose.yml](../../backend/compose.yml) | scripts run the real path; local only |

Scenario anchors:
- "Activation trusted the browser to say the email was verified. I moved the check server-side: store takes the code and asks the email service itself."
- "A six-digit code is a million values. It is safe only with an attempt limit, one live code and a short life; I added all three, and a row lock so concurrent guesses can't slip past the counter."
- "The review showed a database error in the email service was being reported to users as 'wrong code'. I made only a real 400 count as the user's fault; everything else is 503."

---

## 6. Follow-up chain

1. **Why not just let the frontend call email and then store?** → The second call can be sent alone. Whatever the browser reports is a claim, not a fact.
2. **Five attempts lets an attacker lock a user out. Isn't that worse?** → Without a limit they could take the account. With it they can only get in the way; rate limiting per address and IP removes most of that.
3. **How do you know the lock works?** → Two threads with the same right code: exactly one succeeds. Five concurrent wrong guesses: the code still dies. Both fail when the lock is removed.
4. **The lock method is called from a private method. Is it in a transaction?** → Yes, because the public `verifyCode` that calls it is `@Transactional` and is called through the proxy. On the private method alone the annotation would do nothing.
5. **Store said 503 but the code is gone. How?** → email committed VERIFIED, but its answer arrived after store's 5-second timeout. The user requests a new code. Making this exactly-once would need an idempotency key on the check; not worth it for a resend.
6. **Why is Mailpit not in production?** → No login on its API: anyone in the cluster could read every reset code. It is a local tool.

---

## 7. How to say it

- "The browser carries the code; the server checks it."
- "A short code needs a short life, few attempts and only one alive at a time."
- "Only the user's mistakes should look like the user's mistakes."

---

## 8. Further reading

- OWASP Forgot Password and Authentication cheat sheets (codes, attempts, responses): <https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>, <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>
- NIST SP 800-63B, rate limiting for authenticators: <https://pages.nist.gov/800-63-3/sp800-63b.html#throttle>
- Java `SecureRandom`: <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html>
- PostgreSQL row locks and deadlocks: <https://www.postgresql.org/docs/16/explicit-locking.html>
- Spring transactions and proxies (self-invocation): <https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html>
- Mailpit: <https://mailpit.axllent.org/docs/>

---

## 9. Self-check

1. Remove `@Transactional` from `EmailService#verifyCode`. What happens?
<details><summary>Answer</summary>
`lockCodes` uses a pessimistic lock, which needs a transaction. Tried on 2026-11-23: every check throws `InvalidDataAccessApiUsageException: Query requires transaction be in progress`; `EmailController` answers 500 and store answers 503, so nobody can activate. Had the lock simply been dropped too, it would "work" but lose counts and let a code be used twice.
</details>

2. Why does store not call email when the account does not exist?
<details><summary>Answer</summary>
Calling email would count a wrong guess, or use up a right code, against a real person's address for an account that is not there. And the answer is 400 either way.
</details>

3. (Only answerable if you really understand it) The first version expired old codes before saving the new one. Which two problems did that cause, and how does expiring after sending fix both?
<details><summary>Answer</summary>
(a) If sending failed, the old code was already dead and the user had none. (b) The expiry and the insert were separate statements, so the order was "destroy, then create". Expiring in `markSent`, after the mail went out and excluding the new row's id, means the old code dies only once a replacement really reached the user. (Two resends at the very same moment can still expire each other; the user resends once more.)
</details>

---

## 10. Replay time

**40 minutes**: read §1 and §2 (15 min) → `git checkout 4d6cdef`, run `./gradlew :storeService:test --tests '*ActivationNeedsCodeIntegrationTest'` and watch activation succeed without a code (5 min) → `git checkout 44de260`, run it and `:emailService:test --tests '*VerificationCodeRulesTest'` green (5 min) → remove `@Lock` from `lockCodes`, run the two concurrent tests and watch them fail, put it back (5 min) → self-check (10 min).
