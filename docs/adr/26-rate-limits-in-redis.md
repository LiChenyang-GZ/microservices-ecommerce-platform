# ADR-26 · Rate limits on store's public account endpoints, counted in Redis

**English** | [中文](26-rate-limits-in-redis.zh-CN.md)

**Status:** Accepted, amended by 27 (the squatted-address consequence). Amends ADR-23 (email has no public endpoint left) and ADR-25 (a code is asked from store; the "no rate limit yet" consequence).

## Context
After ADR-25 an account needed the emailed code, but nothing limited how often anyone could ask:
- email's `send-verification` was public and did not look at accounts: anyone could make it mail any address, as often as they liked.
- store's `activate` and `reset-password` were unthrottled. A code dies after 5 wrong guesses (ADR-25), whoever made them, so someone who knew an address could send 5 wrong codes a second and keep its sign-up or reset from ever succeeding.
- `forgot-password` mailed any existing account without limit, and `login` allowed unlimited password guesses.
- store did not know the client's address: compose's nginx did not pass it, so every request looked like it came from nginx.

## Decision

### One place: store
- The browser asks store for a registration code (`POST /api/user/send-verification`). store has email send one, with email's service token, only for an account that exists and is not active yet; for any other address it answers the same 200 and sends nothing. email refusing the token, failing to send, being down or slow → 503 (`ALERT email_auth_failed` on 401).
- email's `ServiceTokenFilter.PUBLIC_PATHS` is empty: every endpoint needs store's token. nginx and the gateway route nothing to email.
- So all five public account endpoints are in store, and the limits are written once. (Christy decided.)
- Rejected: limits at the entry. nginx's `limit_req` counts in each worker's memory, Envoy's global rate limit needs a separate rate-limit service, and neither can count per email or reset a count when a log-in succeeds.

### The rules
| Endpoint (`/api/user/...`) | Counted by | Limit |
|---|---|---|
| `send-verification`, `forgot-password` | client address, then email | 20 per hour per address; 3 per 15 minutes per email |
| `activate`, `reset-password` | client address | 20 per 15 minutes |
| `login` | client address + account | 10 per 15 minutes; a successful log-in resets it |
| `login` | client address | 100 per 15 minutes |

- Each endpoint counts on its own. An email is lower-cased and trimmed (`" Victim@X.com"` reaches the same inbox), then stored only as the first 16 bytes of its SHA-256: Redis holds no addresses.
- **The address is counted first**, and the email only if the address is still under its limit: a request blocked by its address does not use up the email's quota.
- **A log-in is counted per account**, not per text typed: the box takes an email or a username. store looks the account up first and counts `account:<id>`; an identifier that matches no account is counted as `typed:<text>`. The count is taken before the password is checked, so once over the limit even the right password gets 429 (otherwise the answer would tell a guesser they got it).
- 3 emails per 15 minutes is enough for "I did not get it, send again" twice; the page already waits 60 seconds between resends. A guesser then gets at most 3 codes × 5 guesses per 15 minutes, about 1,440 guesses a day: a 6-digit code takes about 350 days on average, and the victim receives about 290 emails a day meanwhile.

### Answer when over the limit
`429 Too Many Requests`, `Retry-After: <seconds left in the window>` (a positive integer), body `{"success":false,"message":"Too many attempts, please try again in N seconds"}`. The pages already show `message`.

### Counting: fixed windows in Redis
- **Redis**, not memory: counts survive a store restart and stay right with more than one replica. compose and kind each run one (7.4, `--requirepass`, no published port, no snapshots). (Christy decided.)
- **Fixed window**: one Lua script does `INCR`, and `PEXPIRE` when the count is 1 or the key has no expiry, and returns the count and the time left. It must be one script: with `INCR` and `EXPIRE` as two commands, a crash in between leaves a key that never expires, and that email or address is blocked for good.
- Rejected: a sliding window or a token bucket. They are smoother at the edges, but cost a sorted set per key or more script; the fixed window's known flaw (two windows' worth across a boundary) is accepted.

### The client's address
- store takes the **last** `X-Forwarded-For` value, the one our entry wrote, and the connection's address if there is no header. Values before it were written by the client and are not trusted.
- compose: nginx **replaces** the header (`proxy_set_header X-Forwarded-For $remote_addr`), so a header the browser sent never reaches store. kind: Envoy appends the address it saw.
- store sets `server.forward-headers-strategy=none`. On Kubernetes Spring Boot otherwise turns on Tomcat's `RemoteIpValve`, which skips private addresses as trusted proxies and takes the first public one. kind's client is `172.22.0.1`, a private address, so a forged `X-Forwarded-For: 1.2.3.4` became the client and every forged value got a fresh quota. compose (not Kubernetes) and MockMvc (no Tomcat) could not show it; it was found by testing on kind.
- Someone who reaches store directly, bypassing the entry, can write any header. store is reachable directly only on this machine (compose publishes it on 127.0.0.1) and inside the cluster.

### Redis down: fail-open
- If Redis errors or does not answer within 500 ms, the request goes on without limits and store logs `ALERT rate_limit_unavailable`. Each request waits up to about 0.5 s (a successful log-in about 1 s: the count, then the reset). (Christy decided.)
- Rejected: fail-closed (503 while Redis is down). Safer against guessing, but a Redis outage would stop everyone from signing up and logging in.
- Redis is left out of store's health (`management.health.redis.enabled=false`); otherwise compose's healthcheck, which reads `/actuator/health`, would mark store unhealthy whenever Redis is.

## Consequences
- **What the limits do not stop:**
  - someone with many addresses can still burn another person's codes, or use up that email's 3 messages per 15 minutes, and block a sign-up or reset for a while; wrong guesses are not counted per email yet;
  - many addresses guessing passwords together needs a CAPTCHA or anomaly detection;
  - sign-up itself is not limited, so junk accounts can be created (they cannot be activated, and each gets at most 3 emails per 15 minutes);
  - a fixed window allows two windows' worth across a boundary.
- **An email outage still uses the quota**: three resends that got 503 leave the fourth waiting about 15 minutes.
- **Shared addresses share limits**: many people behind one public address (campus, office, a mobile carrier's NAT) share the per-address counts.
- **IPv6**: one person usually has a whole /64, so the per-address limits do not hold against them, and each new address adds keys to a Redis with no `maxmemory`. To do before a cloud deployment: group by /64, set `maxmemory` with `volatile-ttl`.
- **A load balancer in front of Envoy** (likely in a cloud) would make its own address the last `X-Forwarded-For` value, so everyone would share one count. Envoy must then trust that hop, and `ClientIp` must be checked again.
- **`send-verification` timing**: it answers 200 for every address, but for an account that exists and is not active it waits for email, so it is slower. That tells such addresses apart; sign-up already answers "Email already exists!", so little more is revealed.
- **Someone can still sign up first with another person's address** (older than this decision). This said the owner could reach the verification page and activate; they could not (the page is reached only by a successful sign-up). ADR-27 lets a password reset claim the account.
- **Upgrade**: `backend/.env` needs `REDIS_PASSWORD`; compose refuses to start without it, and kind needs `up.ps1` again. The scripts register about 10 users per full run, so about two full runs an hour; the READMEs show how to reset the counts.
- **One more container**, single replica, like every stateful part (ADR-13).

## In the code
- store [RateLimiter.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java) (the Lua script, fail-open), [AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java) (the rules), [ClientIp.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java), [AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java) (`loginUser`, `sendVerificationCode`, `tooManyAttempts`), [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) (`sendVerificationCode`), [application.properties](../../backend/storeService/src/main/resources/application.properties)
- email [ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java)
- frontend [RegisterPage.jsx](../../frontend/src/pages/RegisterPage.jsx), [EmailVerificationPage.jsx](../../frontend/src/pages/EmailVerificationPage.jsx), [api.js](../../frontend/src/services/api.js), [nginx.conf](../../frontend/nginx.conf)
- Deployment and scripts: [compose.yml](../../backend/compose.yml), [.env.example](../../backend/.env.example), [redis.yaml](../../k8s/base/redis.yaml), [store.yaml](../../k8s/apps/store.yaml), [routes.yaml](../../k8s/gateway/routes.yaml), [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1), [mailpit.ps1](../../backend/docker/mailpit.ps1)
- Tests: store `SendVerificationViaStoreIntegrationTest`, `AuthRateLimitIntegrationTest`, `RateLimitRedisDownIntegrationTest`, `ClientIpOnKubernetesIntegrationTest`; email `EmailServiceTokenIntegrationTest`; frontend `RegisterPage.test.jsx`, `EmailVerificationPage.test.jsx`
- [docs/learning/13-rate-limits.md](../learning/13-rate-limits.md)
