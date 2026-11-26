# ADR-32 · Usernames are 3-32 letters, digits, dot, underscore or hyphen

**English** | [中文](32-username-rules.zh-CN.md)

**Status:** Accepted. Amends ADR-31 (consequence "usernames that look like someone else's email").

## Context
Sign-up checked the email's format but nothing about the username, beyond "not taken" (case-sensitively). A username could hold an `@`, spaces, surrounding whitespace, any Unicode letter, control characters, or hundreds of characters.

The `@` mattered most. Log-in, and the other lookups that take "email or username", try the email first and the username second. After ADR-31 normalised the email half, a username like `Bob@X.com` reached the account whose email is `bob@x.com`: its owner could not log in with the username, and the failed try counted against the other account's limit (review 75). A username shaped like an email could also pose as someone else wherever the name is shown, and taking `ADMIN_EMAIL` as a username was how someone once stopped store from starting (review 47).

Before choosing, the local databases were checked: of 42 accounts (authcheck) and 27 (shop), only the two bootstrapped admins have an `@` in their username, and it is their own email. No ordinary username falls outside the rule below.

## Decision (Christy decided: option B)

- **Sign-up trims the username and requires `^[A-Za-z0-9._-]{3,32}$`**, otherwise 400 "Username must be 3-32 characters: letters, digits, dot, underscore or hyphen". `AccountService#createAccount` checks it after the email's format and **before** looking the email up, so a request with a bad username learns nothing about the email's state, and bank is not called. The trimmed name is what is checked for "already exists" and stored.
- **Log-in trims the username half of the lookup the same way** (`AccountService#byUsername`), so someone who signed up as `"  bob "` (stored `bob`) can type it either way (review 79).
- **The register page applies the same rule and message before sending**, and sends the trimmed name. It trims only what Java's `String#trim()` trims (code points up to U+0020): JavaScript's `trim()` also drops a full-width space or NBSP, and the page would then pass a name store refuses (review 79).
- Sign-up only. Older accounts keep their usernames; `AdminBootstrap` still gives the first admin their own email as username (not through sign-up), which log-in finds by email anyway.
- Rejected: only refusing `@` (option A: spaces, look-alike letters and control characters would stay); also changing log-in to "an identifier with `@` is only an email" (option C: with sign-up refusing `@`, it would only affect the two admins, whose username is their own email).

## Consequences
- A new username can no longer equal any email (every valid email has an `@`), so log-in cannot mistake one for the other, and `ADMIN_EMAIL` can no longer be taken at sign-up. `AdminBootstrap`'s `admin-xxxxxxxx` fallback stays for older rows.
- No spaces, look-alike letters (a Cyrillic `а` in `аdmin`), control characters or very long names. The price: no usernames in Chinese or other non-Latin scripts.
- Usernames are still case-sensitive (`Bob` and `bob` are two users), and there are no reserved names (`admin`, `support` can be registered; they look like staff in the delivery's customer name but carry no role: roles live in the `role` column and the token).
- Two sign-ups racing for the same username: the unique constraint keeps one; the second gets 500, not "Username already exists!" (the same old gap as racing emails, ADR-31).
- A script that uses "the part of the email before `@`" as the username would fail on `a+b@x.com` (`+` and `%` are valid in emails, not in usernames). The current scripts use plain prefixes and pass.
- A future "change username" endpoint must apply the same rule.
- The rule's message is written four times (store, the page, two tests); change them together.

## In the code
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) (`USERNAME_FORMAT`, `USERNAME_RULE`, `createAccount`, `byUsername`), [AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java) (`freeUsername` comment)
- frontend `RegisterPage.jsx` (`trimmedUsername`, `validateForm`)
- Tests: store `UsernameRulesIntegrationTest`, `AdminBootstrapStartupTest#signingUpWithTheAdminEmailAsUsername_isRefused`; frontend `RegisterPage.test.jsx`
- [docs/learning/19-username-rules.md](../learning/19-username-rules.md)
