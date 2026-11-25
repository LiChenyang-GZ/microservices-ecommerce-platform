# ADR-30 · Codes are long enough to survive wrong guesses; store limits guesses per IP + email

**English** | [中文](30-codes-survive-wrong-guesses.zh-CN.md)

**Status:** Accepted. Amends ADR-25 (a code died after 5 wrong guesses) and ADR-26 (`activate` / `reset-password` were limited per IP only; the "someone can burn your code" consequence).

## Context
A code was 6 digits (10⁶). With so few codes, ADR-25 had to cap how often one code could be guessed: five wrong guesses for an address and its live code was dead, **whoever made them**. ADR-26 then limited `activate` and `reset-password` per IP (20 per 15 minutes), which does not help: five guesses from one address are enough.

So one address could, every 15 minutes, ask for 3 codes for your address (using up its quota; the last code is the only live one) and then send 5 wrong guesses. Every code in your inbox was dead and your own request got 429. You could not activate, reset your password, or claim an address someone had registered first (ADR-27): that claim goes through the reset code.

Counting wrong guesses per (email, IP) instead does not work on its own. The cap protected a code that was easy to guess: with no total per code, 1000 addresses (one IPv6 /64 has far more) get 5000 guesses at each code, and with up to 288 codes a day an account falls within a day. "Few guesses in total" and "others cannot kill your code" pull the same knob in opposite directions, **unless the code gets longer**.

| Option | Code | Can others kill your code? | Chance an attacker wins in a day |
|---|---|---|---|
| Before | 6 digits | yes, from 1 address | 288 × 5 / 10⁶ ≈ 0.14% |
| Per (email, IP), no total | 6 digits | no | 1000 addresses: almost certain |
| Per (email, IP) + 20 per code | 6 digits | yes, from 4 addresses | ≈ 0.58% |
| **Chosen: longer code, per (IP, email) only** | **10 digits** | **no** | 1000 addresses: 288 × 5000 / 10¹⁰ ≈ 0.014% |

## Decision (Christy decided)

- **Codes are 10 digits** (10¹⁰), from `SecureRandom`. Christy chose digits over 8 letters and digits (about 10¹², 100 times harder): phones show a number pad and the pages barely change.
- **A wrong guess does not kill a code.** `EmailService#checkCode` returns false and leaves the code live. Expiry after 5 minutes (V15) and "a new code replaces the old one" (V14) stay. Spaces are dropped before comparing (a pasted code may bring some). `failed_attempts` is no longer counted; the column stays.
- **store limits code checks per IP + email**: `activate` and `reset-password` count the IP first (20 per 15 minutes, as before) and then this IP with this email (5 per 15 minutes, the email trimmed and lower-cased, hashed in Redis). A request blocked by its IP does not count against the pair. Both checks happen before email is asked.
- Counting lives in store, not email: store already knows the client address (`ClientIp`) and has the Redis counters, so email's API does not change and the two services can ship in either order.
- The verification and reset pages take exactly 10 digits and drop anything else.

## Consequences
- **Nobody else can kill your code.** Someone can still use up your 3 sends per 15 minutes, but every code they trigger goes to your inbox and stays live, so the newest one works. This closes the claim-blocking path (ADR-27's known limit).
- **Brute force is bounded by the code's length.** With Redis down the limits are skipped (ADR-26, fail-open); a client guessing 1000 times a second for a whole day would have about 0.86%. That is the price of failing open, and why the outage alerts.
- **People behind one NAT share the pair's count**: someone on the same public address can make you wait 15 minutes for that email.
- **At release**, a 6-digit code already sent is still accepted by email until it expires, but the pages ask for 10 digits: the user asks for a new code.
- Codes are longer to type. Wrong guesses are no longer recorded anywhere except the rate-limit counters.

## In the code
- email [EmailService.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) (`CODE_DIGITS`, `generateVerificationCode`, `checkCode`), `EmailOutbox` (`failedAttempts`, no longer counted)
- store [AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java) (`activate`, `resetPassword`, `codeCheck`), [AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)
- frontend `EmailVerificationPage.jsx`, `ResetPasswordPage.jsx`; scripts `backend/docker/mailpit.ps1`
- Tests: email `VerificationCodeRulesTest`; store `AuthRateLimitIntegrationTest`; frontend `EmailVerificationPage.test.jsx`, `ResetPasswordPage.test.jsx`
- [docs/learning/17-codes-survive-wrong-guesses.md](../learning/17-codes-survive-wrong-guesses.md)
