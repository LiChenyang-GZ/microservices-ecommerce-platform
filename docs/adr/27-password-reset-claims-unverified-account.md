# ADR-27 · An unverified account does not hold its address: a password reset claims it

**English** | [中文](27-password-reset-claims-unverified-account.zh-CN.md)

**Status:** Accepted. Amends ADR-25 ("an unactivated account keeps its email") and ADR-26 (the "someone can still sign up first" consequence).

## Context
Sign-up creates an account before the address is proved (ADR-25). So anyone could register someone else's address with a password of their own. They could not activate it, but the row held the address for good:
- the owner's sign-up answered "Email already exists!";
- a password reset changed the password but left the account inactive;
- log-in kept saying "Please verify your email first", and nothing led to the verification page.

ADR-26's consequence also said the owner "can now reach the verification page"; they could not: the register page only goes there after a successful sign-up.

## Decision

### A password reset claims an unverified account (Christy decided)
- When `reset-password` accepts the code and the account was never verified, store also marks it verified and active, and opens its bank account if sign-up could not. The reset code only reaches the owner's inbox, so it proves the address as well as a registration code does.
- The password is the one the owner just chose. Whoever registered the address first can no longer log in with theirs.
- Activation and the claim share one step (`AccountService#markEmailProved`).

### Sign-up says how to claim
- An existing address that was never verified answers 400 with `emailNotVerified: true` and "This email is registered but not verified. If it is yours, reset the password to claim it." The register page shows a link to the password reset. A verified address still answers "Email already exists!".
- This reveals that an address is registered but not verified. Log-in already said so ("Please verify your email first").

### The forgot-password page does not strand the owner
A 429 from `forgot-password` (codes were sent recently, perhaps asked for by the squatter) now goes on to the reset page with the email and the message: the newest code in the inbox still works.

### Rejected
| Option | Why not |
|---|---|
| sign-up overwrites an unverified account | a race: the owner registers and gets a code; before they activate, the squatter registers again without asking for a code; the owner activates with the code they have, and the password is the squatter's |
| delete unverified accounts after 24 hours | the owner is blocked for up to a day, and a script registers the address again the moment it is free |
| no account until the address is proved (a pending-registration table) | cleanest, but sign-up, activation, the bank account and the pages all change, and the pending rows have the same race |

### The verification page stays for people who just signed up
`activate` sets no password: the account keeps the one typed at sign-up. That is safe because only the person who just signed up reaches the verification page. An unverified account must not get another way there (a "verify now" button on the log-in page, say): its owner would activate the squatter's password. Such users go to the password reset. `AccountService#activateAccount`'s comment says so.

## Consequences
- **The squatter's username stays.** Changing a username is another feature.
- **The claim can still be held off.** Someone, even from one address, can use up the address's 3 emails per 15 minutes and then guess the one live code dead (5 wrong guesses, whoever makes them; ADR-25). The owner's codes are then all dead. Counting wrong guesses per email + client address (so that someone else's guesses do not kill your code) is the follow-up.
- **Sign-up is still unlimited**, and unverified accounts are never cleaned up.
- **Email case is not normalised**: `Victim@x.com` and `victim@x.com` are two accounts. A separate item.
- The claim runs inside `resetPassword`'s transaction, so when it has to open a bank account a slow bank holds a database connection meanwhile. Only when sign-up could not reach bank; rare.
- `activateAccount` reads the account, waits for email and saves the whole row; a claim committed in between would be overwritten with the old password. It needs a registration code and a reset code used at the same moment, which a squatter cannot arrange. Written in the comment.

## In the code
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) (`resetPassword`, `markEmailProved`, `createAccount`, `EmailNotVerifiedException`, `activateAccount`), [AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java) (`createAccount`, `CreateAccountResponse.emailNotVerified`)
- frontend [RegisterPage.jsx](../../frontend/src/pages/RegisterPage.jsx), [ForgotPasswordPage.jsx](../../frontend/src/pages/ForgotPasswordPage.jsx), [ResetPasswordPage.jsx](../../frontend/src/pages/ResetPasswordPage.jsx)
- Tests: store `ClaimByPasswordResetIntegrationTest`; frontend `RegisterPage.test.jsx`, `ForgotPasswordPage.test.jsx`
- [docs/learning/14-claim-by-password-reset.md](../learning/14-claim-by-password-reset.md)
