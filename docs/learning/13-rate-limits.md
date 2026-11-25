# 13 · Rate limits on the public account endpoints

**English** | [中文](13-rate-limits.zh-CN.md)

> Commits. PR #33: red `72d2484` → green `9180c61`; found on kind, red `ef5ecaa` → green `77e7021`; after the review red `d534262` → green `a828678`.
> How it was found: ADR-25's review (activation code) recorded "no rate limit yet" as the next step.
> Replay time: **45 minutes**
> **Changed since** (note 17, ADR-30): `activate` and `reset-password` also count 5 per IP + email, and codes can no longer be burned; the guessing arithmetic below uses the old "5 guesses per code".
> Before this: [12 · Activation needs the emailed code](12-activation-code.md) (the code's own rules; this note is about how often anyone may ask for or try one).

---

## 1. Problem

**Nothing limited how often anyone could ask.** email's `send-verification` was public and did not look at accounts, so anyone could make it mail any address without end. `activate` and `reset-password` were unthrottled, and a code dies after 5 wrong guesses whoever made them: knowing an address was enough to keep its sign-up or reset failing forever, 5 wrong codes a second. `forgot-password` mailed any account and `login` allowed unlimited guesses. And store could not tell clients apart: behind compose's nginx every request came from nginx.

Tests that showed the wrong thing **really happened** before the fix (red):
- [SendVerificationViaStoreIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/SendVerificationViaStoreIntegrationTest.java): store had no `send-verification`; nothing checked whether the account existed before mail went out
- [AuthRateLimitIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/AuthRateLimitIntegrationTest.java): the 4th code for one email, the 21st from one address, the 11th wrong password all answered 200 / 400 / 401, never 429; Redis held no keys
- [RateLimitRedisDownIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/RateLimitRedisDownIntegrationTest.java): with Redis paused there was no `ALERT rate_limit_unavailable`
- [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java): `send-verification` without a token → 200 and a mail
- [RegisterPage.test.jsx](../../frontend/src/pages/RegisterPage.test.jsx), [EmailVerificationPage.test.jsx](../../frontend/src/pages/EmailVerificationPage.test.jsx): both pages still called email directly
- Found on kind: [ClientIpOnKubernetesIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClientIpOnKubernetesIntegrationTest.java), a forged `X-Forwarded-For` was counted as the client
- From the review: a sign-up whose code was not sent stayed on the form forever; log-in by email and by username were two counts

---

## 2. What it is (framework level)

### 2.1 One place for the limits
The five public account endpoints now all live in store: the browser asks store for a code, and store has email send it only to an account that exists and is not active ([AccountService#sendVerificationCode](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)). email has no public endpoint left. Putting the limits at the entry was the other option, but an entry can only count by address: it cannot count per email, or reset a count when a log-in succeeds.

### 2.2 What to count, and in which order
A limit is a key and a window: "this email, 15 minutes", "this address, one hour" ([AuthRateLimits](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)).
- **Per email** stops one inbox being flooded; **per address** stops one client trying many emails.
- **Address first**, email only if the address is still under: otherwise an attacker blocked by their address would still use up the victim's email quota with every blocked request.
- **Log-in per account**, not per text typed: `alice@x.com` and `alice` are the same account, and counting the typed text gave a guesser two quotas (found by the review).
- **Count before checking the password**, so once over the limit the right password also gets 429. A 401 for wrong and 200 for right would tell a guesser exactly when they hit.

### 2.3 Fixed window in Redis, atomically
[RateLimiter](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java) runs one Lua script: `INCR` the key; if the count is 1, or the key somehow has no expiry, `PEXPIRE` it; return the count and the time left. Redis runs a script as one command. As two commands, a crash after `INCR` leaves a key with no expiry, and that email is blocked for good.

The alternatives:
| Algorithm | Cost | Flaw |
|---|---|---|
| fixed window (chosen) | one counter per key | up to 2× the limit across a window edge |
| sliding log | a sorted set of timestamps per key | memory grows with the limit |
| sliding window counter / token bucket | two counters or a small state per key | more script; smoother |

Why Redis and not memory: counts survive a store restart and agree across replicas.

### 2.4 Which address is the client?
`X-Forwarded-For` is a list each proxy appends to. Only the value **our** entry wrote can be trusted; anything before it came from the client. So [ClientIp](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java) takes the **last** value. compose's nginx replaces the header with what it saw, Envoy appends.

The trap was in the framework, not our code. On Kubernetes Spring Boot sets `server.forward-headers-strategy` to NATIVE, which turns on Tomcat's `RemoteIpValve`. It walks the list from the right, skips private addresses as "trusted proxies", takes the first public one as the client, and removes what it used from the header. kind's client is `172.22.0.1`, a private address, so `X-Forwarded-For: 1.2.3.4` became "the client" and every forged value had a fresh quota. compose is not Kubernetes and MockMvc has no Tomcat, so only the cluster showed it. The fix is `server.forward-headers-strategy=none`: one place decides, our `ClientIp`.

### 2.5 Fail-open, and keeping it out of health
If Redis errors or is slow (timeout 500 ms), the request goes on without limits and logs `ALERT rate_limit_unavailable`. The choice is about what an outage should cost: fail-closed protects against guessing but stops every sign-up and log-in. And Redis is removed from `/actuator/health`: otherwise compose's healthcheck would mark store unhealthy because a helper is down.

### 2.6 A test that cannot fail tests nothing
The first test for "Redis down does not make store unhealthy" asserted health 200. In the test environment RabbitMQ points at a dead port on purpose, so health was always 503: the test could never pass. The second version ("same status before and after pausing Redis") passed even with the fix removed. The final one turns on `show-components` and asserts that `redis` is not a health component, and was checked both ways: remove `management.health.redis.enabled=false` → red, put it back → green.

---

## 3. Request path

**Someone floods an inbox**
1. `POST /api/store/user/send-verification {"email":"victim@…"}` → `AccountController#sendVerificationCode` → `AuthRateLimits#sendVerification`: the address count, then the email count.
2. 1st–3rd within 15 minutes: the account exists and is not active → store asks email with its token → mail; otherwise 200 and nothing sent.
3. 4th: **429**, `Retry-After: 894`, "Too many attempts, please try again in 894 seconds". email is not asked.

**Someone guesses a password**
1. `POST /api/store/user/login` → store looks the account up → counts `rl:login:ip:<address>` (100) and `rl:login:ip-account:<address>:<hash of account:id>` (10).
2. 11th within 15 minutes, by email or username, right password or not: **429**. From another address the owner can still log in.
3. A successful log-in clears that address + account count.

**A forged header on kind**
`curl -H "X-Forwarded-For: 1.2.3.4" http://127.0.0.1/api/store/user/send-verification` → Envoy appends `172.22.0.1` → Tomcat leaves the header alone → `ClientIp` takes `172.22.0.1` → the key is `rl:send-verification:ip:172.22.0.1`.

**Redis is down**
`RateLimiter#hit` times out after 500 ms → `UNAVAILABLE` → the request goes on, `ALERT rate_limit_unavailable`. Health stays UP.

Evidence from the running system: compose (`-p authcheck`) four quick codes for one email → 200, 200, 200, 429; the forged header through nginx counted as nginx's address; Redis stopped → answers in about 515 ms with the ALERT, health UP; kind → the forged header counted as `172.22.0.1`; smoke scripts pass on both; `check-entry.ps1` 55/55.

---

## 4. Why this way

### 4.1 Only in store (Christy decided)
One place to read and test. The cost: a code request now goes browser → store → email, one more hop.

### 4.2 Redis (Christy decided)
Chosen over an in-memory map at gate 0: store's one replica today is a limit of the outbox (ADR-17), not a promise, and a restart should not hand out fresh quotas. The cost: one more container and a `REDIS_PASSWORD` on upgrade.

### 4.3 Fail-open (Christy decided)
Written into the failure table as a choice, not a default. An outage of a helper should not become an outage of sign-up and log-in; the ALERT makes it visible.

### 4.4 The numbers 🤖
3 codes per email per 15 minutes: enough for two resends after the page's 60-second wait. With 5 guesses per code that is 15 guesses per 15 minutes, about 350 days on average for a 6-digit code, while the victim receives about 290 emails a day. 10 log-ins per address + account per 15 minutes; 100 per address against many accounts. All guesses at "normal use", recorded so they can be changed.

### 4.5 What the review found
No must-fix. Two should-fix, both about **what happens around the limit**: a sign-up whose code was refused left the user with an account they could never reach (no code page, "email already exists", "verify your email first"); and log-in counted the typed text, so email and username were two quotas. The rest are recorded limits: an email outage uses the quota, shared addresses share it, IPv6 /64, Redis `maxmemory`, timing of `send-verification`, signing up with someone else's address.

---

## 5. Interview topics here

**Conclusion**: a limit is only as good as its key: the right thing counted, at the right moment, from an address you can trust.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Rate-limiting algorithms: fixed window, sliding window, token bucket | [RateLimiter](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java) | fixed window; edge burst accepted |
| ➕ Redis atomicity: Lua script vs `INCR` + `EXPIRE` | `RateLimiter` | one script, so no key lives forever |
| ➕ Choosing the key: per email, per address, per account; order of checks | [AuthRateLimits](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java), `AccountController#loginUser` | address first; log-in per account |
| ➕ `X-Forwarded-For` and trusted proxies | [ClientIp](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java), `forward-headers-strategy=none` | last value only; the framework's valve turned off |
| ➕ Fail-open vs fail-closed | `RateLimiter`, `application.properties` | open, with an ALERT |
| ➕ Health checks: what belongs in them | `management.health.redis.enabled=false` | a helper's outage does not mark store down |
| ➕ 429 and `Retry-After`; not leaking a hit | `AccountController#tooManyAttempts`, `#loginUser` | count before the password check |
| ➕ User enumeration and timing | `AccountService#sendVerificationCode` | same 200 for every address; the timing difference recorded |
| 📒 Testing: a guard test must be able to fail | `RateLimitRedisDownIntegrationTest` | checked red with the fix removed |

Scenario anchors:
- "Anyone could make our email service send codes to any address, as often as they liked. I moved the endpoint behind store and limited it per email and per client address in Redis."
- "On Kubernetes a forged X-Forwarded-For got a fresh quota each time. Spring Boot had turned on Tomcat's RemoteIpValve, which trusts private addresses; I turned it off and take only the value our entry wrote."
- "A test I wrote for 'Redis down keeps store healthy' passed even with the fix removed. I rewrote it until removing the fix turned it red."

---

## 6. Follow-up chain

1. **Why not limit at the gateway?** → The gateway only sees addresses. Per-email and "reset on successful log-in" need the application.
2. **Why not `INCR` then `EXPIRE`?** → Two commands; a crash between them leaves a key that never expires. A Lua script runs as one command.
3. **The attacker rotates addresses.** → Then per-email still holds: 3 codes per 15 minutes. What it does not stop is burning the victim's quota; counting wrong guesses per email is the next step.
4. **How do you get the real client address behind a proxy?** → Trust only what your own proxy wrote: the last `X-Forwarded-For` value, and only when the request came through it. Check what the framework does with the header too; ours rewrote it.
5. **Redis is down. Now what?** → Requests go on without limits, each waits up to 500 ms, an ALERT is logged, health stays up. A choice: fail-closed would be safer and would stop all log-ins.
6. **Why does the right password get 429?** → Otherwise 429 vs 200 tells the guesser they were right.
7. **What changes with IPv6 or a load balancer?** → IPv6: one person has a /64, so group by /64. A balancer in front of the entry: its address becomes the last value, so trust that hop explicitly.

---

## 7. How to say it

- "Count the right thing, before the check, from an address you wrote yourself."
- "A helper's outage should cost protection, not availability, and it should be loud."
- "If removing the fix does not turn the test red, the test is not testing the fix."

---

## 8. Further reading

- Redis rate limiting patterns and `INCR`: <https://redis.io/docs/latest/commands/incr/#pattern-rate-limiter>
- Redis scripting (atomic execution): <https://redis.io/docs/latest/develop/interact/programmability/eval-intro/>
- Spring Boot behind a proxy (`server.forward-headers-strategy`, Kubernetes default): <https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server>
- Envoy `x-forwarded-for` and trusted hops: <https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/headers#x-forwarded-for>
- RFC 6585, 429 Too Many Requests: <https://www.rfc-editor.org/rfc/rfc6585#section-4>
- OWASP Authentication cheat sheet (throttling, enumeration): <https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>

---

## 9. Self-check

1. Remove `server.forward-headers-strategy=none` from store's `application.properties`. Which test fails, and why only that one?
<details><summary>Answer</summary>
`ClientIpOnKubernetesIntegrationTest`: it starts a real Tomcat with `spring.main.cloud-platform=kubernetes`, so Spring Boot turns on `RemoteIpValve`, and `1.2.3.4, 203.0.113.9` is counted as `1.2.3.4`. That is the red commit `ef5ecaa`. `AuthRateLimitIntegrationTest` stays green: MockMvc does not go through Tomcat, so the valve never runs.
</details>

2. Why is the address counted before the email?
<details><summary>Answer</summary>
Otherwise every request blocked by its address would still add one to the email's count, and an attacker whose own address is over the limit could keep the victim's email at its limit. Counting the address first means a blocked request costs the victim nothing.
</details>

3. (Only answerable if you really understand it) The first two versions of the "Redis down keeps store healthy" test were wrong in different ways. What was wrong with each, and what makes the final one trustworthy?
<details><summary>Answer</summary>
(a) "Health is 200" could never pass: RabbitMQ is unreachable in the test environment on purpose, so overall health is always 503. (b) "Same status before and after" passed without the fix: with a 500 ms timeout Redis's own check answers DOWN quickly, overall health is 503 both times. The final test asserts that `redis` is not among the health components (with `show-components` on), and it was run with `management.health.redis.enabled=false` removed (red) and restored (green).
</details>

---

## 10. Replay time

**45 minutes**: read §1 and §2 (15 min) → `git checkout 72d2484`, run `./gradlew :storeService:test --tests '*AuthRateLimitIntegrationTest'` and watch the 4th code go out (5 min) → `git checkout 77e7021`, run it and `--tests '*ClientIpOnKubernetesIntegrationTest'` green (5 min) → remove `server.forward-headers-strategy=none`, run the Kubernetes test and watch it fail, put it back (5 min) → self-check (15 min).
