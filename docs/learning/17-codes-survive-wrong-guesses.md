# 17 · Codes that survive wrong guesses

**English** | [中文](17-codes-survive-wrong-guesses.zh-CN.md)

> Commits (branch `fix/code-guess-per-ip-email`): red `e59f8f8` → green `ac371f5`; after the review red `329ee9b` → green `2c3a050`.
> How it was found: the squatting review (Obsidian 59, finding 🟠2), written down then as a known limit (rate limits R29).
> Replay time: **25 minutes**
> Before this: [12 · Activation code](12-activation-code.md), [13 · Rate limits](13-rate-limits.md), [14 · Claiming a squatted address](14-claim-by-password-reset.md).

---

## 1. Problem

**Anyone who knew your address could keep you from ever using a code.** A code was 6 digits and died after 5 wrong guesses for your address, whoever made them. From one IP, every 15 minutes: ask for 3 codes for you (using up your quota), then guess wrong 5 times. Every code in your inbox was dead, and your own request got 429. Activation, password reset and claiming a squatted address (note 14) all need a code, so all three were blocked.

Tests that showed the wrong thing **really happened** before the fix (red):
- [VerificationCodeRulesTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerificationCodeRulesTest.java): after 20 wrong guesses the right code failed (both kinds); codes were 6 digits; a pasted code with spaces was wrong
- [AuthRateLimitIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/AuthRateLimitIntegrationTest.java): the 6th check from one IP for one email still reached email (400, not 429)
- `EmailVerificationPage.test.jsx`, `ResetPasswordPage.test.jsx`: the pages took only 6 digits

---

## 2. What it is (framework level)

### 2.1 One knob, two ends
A short code needs a small total number of guesses, or someone will hit it. But a small total is exactly what lets a stranger use it up. Counting per (email, IP) protects the owner and gives an attacker with many addresses many more guesses. The only way to get both is to make each guess worth less: **a longer code**.

### 2.2 Do the arithmetic
Guesses per day ÷ number of codes. Before: 288 codes × 5 / 10⁶ ≈ 0.14% a day. Per (email, IP) with 6 digits and 1000 addresses: 288 × 5000 / 10⁶, more than one. With 10 digits: ≈ 0.014%. Writing this table is what showed the "obvious" fix was wrong.

### 2.3 Lockout vs throttling
Locking the *resource* (the code) after N failures is a denial-of-service tool for whoever can fail on purpose. Throttling the *caller* (IP + email) slows the attacker without touching the owner. Same idea as login: the count is per IP + account, never "lock the account".

### 2.4 Count where you already know the caller
store has the client address and the Redis counters; email only sees store. Counting in store means email's API does not change and neither service has to ship first.

### 2.5 Fail-open needs a floor
Rate limits fail open when Redis is down (note 13). Before, the 5-guess cap in email's database was the floor. Now the floor is the code's length: a day of 1000 guesses a second gives about 0.86%.

---

## 3. Request path

1. Someone posts `/api/user/reset-password {email: you, code: …}` from IP A.
2. store `AccountController#resetPassword` → `AuthRateLimits#resetPassword(ip, email)`: IP count (20 / 15 min), then IP + email (5 / 15 min). The 6th from A for you → 429, email is not asked.
3. Allowed → `AccountService#resetPassword` → email `verify-password-reset` → `EmailService#checkCode`: spaces dropped, compared with the live codes under a row lock. Wrong → false; the code stays live.
4. You, from IP B, with the newest code in your inbox → your own count is fresh → email says yes → the password is set (and a squatted account is claimed).

---

## 4. Why this way

### 4.1 10 digits (Christy decided)
The recommendation was 8 characters from a 32-letter alphabet (about 10¹²). Christy chose 10 digits: a number pad on phones and almost no page change, still 100 times better than before even against 1000 addresses.

### 4.2 What the red showed 🤖
Two store tests I expected to be green already (another IP can still use the code; one IP can try another email) were red at their middle step: the 6th attempt was 400, not 429. And R11 / R12 (20 per IP) had to spread over 4 accounts, or they would have hit the new 5 first. The script that reads codes from Mailpit matched `\d{6}`, which would have taken the first 6 of 10 digits. I also removed `maxLength={6}` thinking the browser would cut a pasted code; the review showed `Input` never passed it to the page, so it did nothing either way.

### 4.3 What the review found
0 must-fix, 1 should-fix, 5 could-fix. No way around the IP + email key: the count normalises the email more coarsely than the database matches it, so any input that can reach a code lands on one key; and only store calls email's verify endpoints.
- **Should-fix (written down, not code)**: the guess limit now rests on telling clients apart by address. With IPv6 (a new address per request) or behind a cloud load balancer (everyone looks like the balancer) it is gone, or turns into "5 per email for the whole site". So grouping by /64 and checking the client-address rule became preconditions for a cloud deployment.
- **Fixed** (red `329ee9b` → green `2c3a050`): during the switch the pages take 6 or 10 digits, so the frontend can ship first; `Input` passes `inputMode` / `autoComplete`, so phones show a number pad, which was half the reason for choosing digits; two regression tests for the pair key (activate ignores case; a request blocked by its IP leaves no pair key); a test renamed to say what it checks; the `lockCodes` comment no longer mentions a counter.
- **Written down**: the owner's own typos and 503s count too (15 minutes' wait); pages opened before the release must be reloaded; ADR-27 and notes 12 / 13 point here.

---

## 5. Interview topics here

**Conclusion**: do not lock the thing the owner needs; slow down the caller, and make each guess worth less.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Brute force vs denial of service | `EmailService#checkCode` | a lockout anyone can trigger is a DoS; throttle the caller instead |
| ➕ Entropy arithmetic | ADR-30 table | guesses × codes per day ÷ code space, for each option |
| ➕ Rate-limit keys | `AuthRateLimits#codeCheck` | IP first, then IP + normalised email; a request blocked by IP does not count against the pair |
| ➕ Fail-open floor | ADR-26, ADR-30 | when the limiter is down, the code's length is what is left |
| ➕ Changing a format in flight | `checkCode`, the pages | old 6-digit codes still compare; the pages ask for 10, so users resend |

Scenario anchors:
- "Our codes were 6 digits and died after 5 wrong guesses, from anyone. So one IP could keep someone from ever resetting their password. Counting per IP alone would have let a thousand IPs brute-force it within a day; I did the arithmetic, and the fix was making the code 10 digits so wrong guesses no longer need to kill it, plus a per-IP-and-email limit in store."

---

## 6. Follow-up chain

1. **Why not just count per (email, IP)?** → With 6 digits and no total, many addresses break the code within a day.
2. **Why not keep a total per code, say 20?** → Then 4 addresses kill it again, and brute force gets 4 times easier.
3. **Why count in store, not email?** → store knows the caller's address and already has Redis; email's API stays the same.
4. **What if Redis is down?** → No limits; 10¹⁰ codes give about 0.86% for a full day at 1000 guesses a second.
5. **Can the attacker still block the owner?** → They can use up the 3 sends per 15 minutes, but each code goes to the owner and stays live; or share the owner's NAT and make them wait 15 minutes.

---

## 7. How to say it

- "A lockout anyone can trigger is a denial-of-service feature."
- "If guesses must be few, make each guess worth less."
- "Throttle the caller, not the resource."

---

## 8. Further reading

- OWASP, *Blocking Brute Force Attacks* (lockout vs throttling): <https://owasp.org/www-community/controls/Blocking_Brute_Force_Attacks>
- NIST SP 800-63B §5.1.3 (out-of-band authenticators) and §5.2.2 (rate limiting): <https://pages.nist.gov/800-63-3/sp800-63b.html>

---

## 9. Self-check

1. Why did 5 wrong guesses kill a code before, and what did that cost?
<details><summary>Answer</summary>
6 digits are 10⁶ codes, so a code could only take a few guesses in total. But the cap did not care who guessed, so one IP could kill the owner's live code every 15 minutes.
</details>

2. Why does the per (IP, email) limit check the IP first?
<details><summary>Answer</summary>
So a request already blocked by its IP does not use up the pair's 5 (same as the send limits, R9), and so the 20 per IP still caps one address trying many emails.
</details>

3. (Only answerable if you really understand it) An attacker uses up your 3 sends per 15 minutes. Why can you still reset your password now, when you could not before?
<details><summary>Answer</summary>
Each send mails a code to you and replaces the older one; the newest is live. Before, the attacker then guessed it to death from one IP. Now wrong guesses leave it alive, and the attacker's guesses are counted against their IP + your email, not yours, so you use the newest code from your inbox.
</details>

---

## 10. Replay time

**25 minutes**: read §1 and §2 (10 min) → `git checkout e59f8f8`, run `./gradlew :emailService:test --tests '*VerificationCodeRulesTest'` and see the code die (5 min) → `git checkout ac371f5`, run it and `:storeService:test --tests '*AuthRateLimitIntegrationTest'` green (5 min) → self-check (5 min).
