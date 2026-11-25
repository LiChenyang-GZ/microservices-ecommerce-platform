# ADR-25 · Activation needs the emailed code, and store checks it with email

**English** | [中文](25-activation-needs-the-emailed-code.zh-CN.md)

**Status:** Accepted, amended by 26 (a code is asked from store; rate limits) and 27 (a password reset claims an unverified account), and by 30 (codes are 10 digits and a wrong guess no longer kills them). Amends ADR-23 (email's `verify-code` is no longer public) and ADR-24 (activation now proves the address).

## Context
After ADR-24, store required a login and checked who owned each order, but "who you are" still started with an unproved email address. `POST /api/user/activate` took only `{email}`. The verification page checked the code with email's public `verify-code`, then told store "activate this address"; store could not tell whether the first step had happened. So anyone could register an account under someone else's address and activate it with one request (scenario A11).

Around the code itself:
- email accepted any SENT code for the address, whatever its kind: a password-reset code activated an account and a registration code reset a password.
- Guesses were unlimited. A resend left the old code live, so several codes could be valid at once.
- Codes came from `java.util.Random`.
- Locally nobody received the mail: compose and kind had only Gmail settings, sending failed without them, the code stayed FAILED and could never pass. The scripts skipped the problem by calling `activate` with an email only.

## Decision

### store asks email
- `activate` takes `{email, code}`. store sends them to email's `verify-code` with email's service token (ADR-23), the same way `reset-password` already checks a reset code. Only after email says yes does store set the account active and verified (and open the bank account if sign-up could not).
- Rejected: email signs a one-time token that the browser hands to store. It needs another shared secret, a table of used tokens against replay, and an expiry, all to carry a fact store can simply ask email for.
- `activate` is not `@Transactional`: no database transaction stays open while store waits up to 5 seconds for email.

### Answers
| Situation | store answers |
|---|---|
| no code, wrong or expired code (email answers 400), unknown email | **400** "Verification code is invalid or expired", the same text for all three. An unknown email does not reach email, so nobody's code is spent |
| already active | **200** without asking email, so a second click or a reload does not spend a code |
| email refuses the code but the account is active by now (two tabs, double click) | **200** "already activated" instead of "wrong code" |
| email answers 401 | **503** and `ALERT email_auth_failed` (store's token is wrong) |
| email down, no answer within 5 s, any other 4xx (404: `email.service.url` points elsewhere) or 5xx | **503**, logged. Not the user's mistake |

email answers 400 only for a wrong or expired code. Its own failures while checking (database down, deadlock, commit failed) answer **500** without the exception text.

### `verify-code` is for store only
email's `ServiceTokenFilter` allowlist keeps only `send-verification` public. nginx and the gateway no longer route `verify-code`; `check-entry.ps1` checks that the entry answers its own 404.

### Rules for a code
- **One kind per check**: registration accepts only REGISTRATION codes, reset accepts only PASSWORD_RESET codes.
- **5 wrong guesses kill it.** Each wrong guess adds one to every live code of that kind for the address; at 5 it becomes EXPIRED and even the right code fails. A 6-digit code then survives 5 guesses out of a million instead of unlimited ones.
- **One live code at a time.** A newly sent code expires the older ones of the same kind, **after** its mail went out: if sending fails, the code the user already has keeps working.
- **`SecureRandom`** generates codes.
- **Checked under a row lock.** The live codes are read with `SELECT … FOR UPDATE`, in id order, inside the check's transaction. Two requests with the same right code: one succeeds. Five wrong guesses at once: none of the counts is lost. Id order means two transactions lock rows in the same order and cannot deadlock.

### Local mail goes to Mailpit
- compose and kind send mail to **Mailpit**, a fake SMTP server with a web page and an API (http://localhost:8025 on compose; `kubectl port-forward svc/mailpit 8025:8025` on kind). Without it local sign-up never worked.
- The scripts do what the verification page does: ask email to send a code, read it from Mailpit's API (`backend/docker/mailpit.ps1`), call `activate` with it. Smoke and scenario scripts therefore go through the real activation path.
- The SMTP host, port, auth and STARTTLS come from `MAIL_HOST`, `MAIL_PORT`, `MAIL_SMTP_AUTH`, `MAIL_SMTP_STARTTLS`. compose defaults them to Mailpit; run from an IDE, email still defaults to Gmail.
- email logs `Mail goes to host:port` at startup, and a WARN when `MAIL_USERNAME` is set but mail goes to Mailpit. JavaMail gets 5-second connect, read and write timeouts (its default is to wait forever).

## Consequences
- **No rate limit yet.** `send-verification`, `activate` and `reset-password` are public and unthrottled. Someone who knows an address can make email send it many codes, and can send five wrong codes every second so that address's sign-up or password reset never succeeds. Before this decision the same person could guess the code outright; now they can only get in the way. Limiting by address and by IP is the next step (done in ADR-26).
- **A code can be used up without an activation.** If email marks the code VERIFIED and store then fails to save, or email's yes arrives after store's 5-second timeout (store said 503), the code is gone and the user asks for a new one.
- **An unactivated account keeps its email.** Someone who registers your address first cannot activate it, but you see "Email already exists" until that row is dealt with (since ADR-27 a password reset claims it). `AdminBootstrap` still never promotes an existing account for the same reason.
- **Mailpit is for local runs only.** Its API has no login; in a real cluster any pod could read every registration and reset code, and real users would receive nothing. Before deploying anywhere real, remove `k8s/base/mailpit.yaml` and point email's `MAIL_*` at a real SMTP server.
- **Upgrading changes where compose sends mail.** A `backend/.env` with only `MAIL_USERNAME` / `MAIL_PASSWORD` (the Gmail setup before this) now sends to Mailpit; the startup WARN says so. To keep real mail, set the four `MAIL_*` variables; the scripts can then no longer read codes.
- **Two tabs resending at the same moment** can each expire the other's code; the user presses resend once more. The page's 60-second countdown makes this rare.
- store has `spring.jpa.open-in-view` on, so an activation waiting for email may still hold its JDBC connection. Not checked yet.

## In the code
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) (`activateAccount`, `codeRefused`), [AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)
- email [EmailService.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) (`checkCode`, `newCode`, `markSent`), [EmailOutboxRepository.java](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java) (`lockCodes`, `expireOtherLiveCodes`), [EmailController.java](../../backend/emailService/src/main/java/com/comp5348/email/controller/EmailController.java), [ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java), [MailTargetLog.java](../../backend/emailService/src/main/java/com/comp5348/email/config/MailTargetLog.java), [application.properties](../../backend/emailService/src/main/resources/application.properties)
- frontend [EmailVerificationPage.jsx](../../frontend/src/pages/EmailVerificationPage.jsx), [api.js](../../frontend/src/services/api.js), [nginx.conf](../../frontend/nginx.conf)
- Deployment and scripts: [compose.yml](../../backend/compose.yml), [.env.example](../../backend/.env.example), [mailpit.yaml](../../k8s/base/mailpit.yaml), [email.yaml](../../k8s/apps/email.yaml), [routes.yaml](../../k8s/gateway/routes.yaml), [mailpit.ps1](../../backend/docker/mailpit.ps1), [check-entry.ps1](../../k8s/scripts/check-entry.ps1)
- Tests: store `ActivationNeedsCodeIntegrationTest`; email `VerificationCodeRulesTest`, `VerifyEndpointErrorsTest`, `EmailServiceTokenIntegrationTest`; frontend `EmailVerificationPage.test.jsx`
- [docs/learning/12-activation-code.md](../learning/12-activation-code.md)
