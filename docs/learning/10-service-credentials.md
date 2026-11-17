# 10 · Service credentials: who is allowed to call bank, delivery and email (auth PR 1)

**English** | [中文](10-service-credentials.zh-CN.md)

> Commits (PR #26): red `9dad7eb` `550bb26` `584f482` `92e5433` `dc1672f` `775c043` `0ea58ef` `03c1aea` `b47d980` `1c85326` → green `8dbd151` (bank) `f3145d2` (delivery) `d36fbd2` (email) `a391340` `896510b` `e38b223` (store), `eed42cc` `76d57da` (deployment)
> How it was found: the wave 1 review backlog. Reading the code showed that inside the network nothing checked who was calling (ADR-23 "Context").
> Replay time: **45 minutes**
> Before this: [05 · gRPC bank call](05-grpc-bank-call.md) (store → bank over gRPC), [08 · Kubernetes on kind](08-kubernetes-on-kind.md) (the entry allowlist).

---

## 1. Problem

**The entry's allowlist was the only lock, and it only faced outward.** Anything already inside (a pod in the cluster, a container on the compose network, a process on the host) could:

- call bank's gRPC `Transfer` / `Refund` and move money (no interceptor);
- call `POST /api/bank/transfer` and move money from `SYSTEM` to any account (bank's REST filter let requests without a token through);
- call delivery's `/internal/deliveries/cancel-by-order/{id}` and cancel any delivery, which makes store refund (the filter only covered `/api/*`);
- call email's notification endpoints and send mail as the shop to any address.

Tests that showed the request **really succeeded** before the fix (red):
- bank [BankGrpcAuthIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcAuthIntegrationTest.java): a transfer without the token moved the balance. [BankRestAuthIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankRestAuthIntegrationTest.java), [OldBankRestEndpointsRemovedTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/OldBankRestEndpointsRemovedTest.java)
- delivery [CancelByOrderAuthIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/CancelByOrderAuthIntegrationTest.java): the cancel marker was written and a Cancelled event went out
- email [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java) (email's first tests ever), [OldEmailEndpointsRemovedTest.java](../../backend/emailService/src/test/java/com/comp5348/email/OldEmailEndpointsRemovedTest.java)
- store [BankServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankServiceTokenIntegrationTest.java), [DeliveryServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryServiceTokenIntegrationTest.java), [EmailServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailServiceTokenIntegrationTest.java): store sent no token and raised no alert
- one `*StartupWithoutServiceTokenTest` per service: each started with no secret configured

---

## 2. What it is (framework level)

### 2.1 A shared secret, compared in constant time
Each called service reads one value (`bank.service-token`, filled from `BANK_SERVICE_TOKEN`). A request must carry exactly that value. [ServiceToken#matches](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java) compares with `MessageDigest.isEqual`, which takes the same time however many leading bytes match. `String.equals` returns at the first different byte, so a caller timing many replies could in theory learn the secret one byte at a time. With a random 64-character secret over a network this is hard to exploit, but the constant-time compare costs nothing.

### 2.2 Refusing to start
The `ServiceToken` constructor throws `IllegalStateException` when the value is blank and names the key. The bean cannot be created, so the application context fails and the service exits at once. Without this, a service with no secret either rejects everything (looks healthy, every payment fails) or, with a careless `if (token == null) allow`, skips the check.

A trap on the way: `application.properties` must spell out `bank.service-token=${BANK_SERVICE_TOKEN:}`. Spring's relaxed binding maps the environment variable `BANK_SERVICE_TOKEN` to `bank.service.token` (underscore → dot), not to `bank.service-token`; a key with a hyphen needs the explicit placeholder.

### 2.3 A servlet filter, registered for chosen paths only
[ServiceTokenFilter](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceTokenFilter.java) extends `OncePerRequestFilter`: wrong token → 401, and `filterChain.doFilter` is never called, so the controller never runs.

It is **not** a `@Component`. Spring Boot registers every `Filter` bean for **every** path (`/*`). delivery needs the service filter on `/internal/*` and the user-token filter on `/api/*`, so both are plain classes registered by [FilterConfig](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java) with a `FilterRegistrationBean` and `addUrlPatterns(...)`.

Tests must go through the filter. MockMvc built with `standaloneSetup` or with filters off skips it, and an auth test would pass for the wrong reason; delivery's `CancelByOrderApiIntegrationTest` was moved from `mvc()` to `mvcWithFilters()` for that.

### 2.4 gRPC interceptors on both ends
- Server: [ServiceTokenServerInterceptor](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java) reads metadata `x-service-token`. Wrong → `call.close(Status.UNAUTHENTICATED, …)` and an empty listener, so the method never starts. [GrpcServerLifecycle#start](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java) registers the service as `ServerInterceptors.intercept(bankGrpcService, interceptor)`: every method of the service is wrapped, including ones added later.
- Client: [BankAdapter](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java) builds the channel with `.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))`, so every call on that channel carries the token.
- gRPC metadata keys must be lower case (HTTP/2 header rule). The HTTP header is `X-Service-Token`; the gRPC key is `x-service-token`.

A servlet filter does not see gRPC at all: gRPC runs on its own Netty server on port 9090. Protecting REST alone would have left the money path open; this is why the wave 2 B review called gRPC "the second door".

### 2.5 Allowlist vs denylist (email)
email has two public endpoints (the registration page) and the rest are store-only. Two ways to write the filter:
- **Denylist**: protect the paths you know are internal. An endpoint added later is open until someone remembers to add it.
- **Allowlist** (chosen): [ServiceTokenFilter#PUBLIC_PATHS](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java) lists the two public paths, matched **exactly**; everything else under `/api/*` needs the token, whether it exists or not. A removed endpoint answers 401 to an outsider (who cannot tell it is gone) and 404 to store.

The old filter matched with `startsWith`, so `/api/email/send-verification-anything` counted as public too.

### 2.6 One HTTP client per callee
store attaches the header with `RestTemplateBuilder#defaultHeader` when it builds each callee's `RestTemplate`. Each one only talks to one service, so bank's secret never reaches delivery. `RestTemplateBuilder` is immutable: `defaultHeader` returns a new builder, so the shared builder bean is not changed.

Two traps found on the way:
- `RestTemplateBuilder` picks the JDK `HttpClient` by default, which first tries an HTTP/2 upgrade (h2c). WireMock reset those connections (`RST_STREAM`); the old `new RestTemplate()` used plain HTTP/1.1. [AccountService](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) sets `SimpleClientHttpRequestFactory` explicitly, like the two adapters.
- The old email client had no timeout. A hanging email held one Tomcat thread per password reset. Now 5 seconds for connect and read ([EmailCallTimeoutIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailCallTimeoutIntegrationTest.java), found by the review).

### 2.7 NetworkPolicy
A NetworkPolicy selects pods (`app: bank`) and lists who may connect to which port. Once a policy selects a pod for ingress, everything not listed is dropped. The one at the end of [bank.yaml](../../k8s/apps/bank.yaml): 9090 from `app: store` only; 8084 from anywhere (probes). Kubernetes only stores the object; the **network plugin** enforces it. kind's default kindnet does since v0.24; checked from inside real pods on v0.33: store → bank:9090 connects, delivery → bank:9090 is blocked.

---

## 3. Request path

**A payment with the right secret**
1. `OutboxProcessor` picks a PAY task → `BankAdapter#transfer` calls `Transfer` on the channel; the client interceptor adds `x-service-token`.
2. bank's `ServiceTokenServerInterceptor` compares → equal → `BankGrpcService#transfer` runs as before (ADR-04, ADR-22).

**The same payment with a wrong secret** (store and bank configured with different values)
1. bank's interceptor → `UNAUTHENTICATED`, logs `Rejected gRPC …: wrong service token`. No money moves.
2. store maps every gRPC error to UNKNOWN (ADR-22) → same key retried, later PAY_UNKNOWN; **and** `BankAdapter#alertIfAuthFailed` logs `ALERT bank_auth_failed key=…`. Without that line the logs look like a run of timeouts.
3. Fix the secret, restart → the next retry with the same key succeeds; no double charge (ADR-04).

Evidence from the running system (compose `-p authcheck` and kind): smoke and scenarios 06 / 07 / 08 / 01 pass with no `ALERT`; by hand, no or wrong secret → 401 on bank REST, delivery `/internal`, email password reset; `localhost:9090` is closed.

---

## 4. Why this way

### 4.1 Shared secret, mTLS or signed JWTs: recommended by AI, Christy agreed 🤖
| Option | Gives | Costs |
|---|---|---|
| **Shared secret per callee** (chosen) | caller identity, works on compose and kind the same day | leak = that service open; rotation has a gap; secret in plain text on the wire |
| mTLS | identity + encryption, no secret in requests | a CA, certificates, renewal, TLS on probes: days of infrastructure |
| Caller-signed short-lived JWT | expiry, no shared secret | another key system |

Production would usually use mTLS through a service mesh (Istio, Linkerd) or SPIFFE identities. The trade-off is written in ADR-23.

### 4.2 One secret per callee, not one for all 🤖
With one shared secret, any service holding it could call any other. With one per callee, delivery's secret cannot move money. `check-config-keys.ps1` fails if a pod holds another service's secret; only store, the caller of all three, holds all three.

### 4.3 What store does when refused 🤖
The rule: **keep the answer you would give for "I don't know", add an alert.**
- bank refuses → UNKNOWN, never REJECTED. REJECTED marks the payment FAILED and lets Pay Now charge again with a new key; a wrong secret is our fault, not the buyer's.
- delivery refuses → "try again later", not "too late": the parcel was not picked up.
- email refuses → the password is not changed. The worst wrong fix would be "the check did not succeed but nothing was thrown", allowing a reset without the code.

### 4.4 Delete, don't protect 🤖
bank's REST transfer / refund and email's notification endpoints had no callers (money goes over gRPC, emails over RabbitMQ). A removed endpoint cannot be attacked and needs no test. Each got a red test first proving it really worked, then was removed.

### 4.5 What the review found
After everything was green, a separate session reviewed the branch. One finding mattered: store raised an alert when bank's **gRPC** refused the secret, but not when bank's **REST** did (open account, account by email). Users who registered while the secret was wrong got no bank account and could not pay even after it was fixed. The failure table only had the gRPC case (C3), so the red tests covered gRPC, the code fixed gRPC, and everything was green. A row C3b was added, a red test written (`1c85326`), then fixed (`e38b223`). **A green suite proves what the table asks, not what the table forgot.**

---

## 5. Interview topics here

**Conclusion**: the step mostly uses web-security basics, not new frameworks: where a request is checked, what a refusal means for money, and how a check fails closed.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Constant-time comparison | [ServiceToken#matches](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java) | `MessageDigest.isEqual`, not `equals`. Leaks only the length; the secret is random 64 hex. **Not needed** for values that are not secrets |
| ➕ Servlet filter vs `@Component` auto-registration | [delivery FilterConfig](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java) | A `Filter` bean runs on every path; two filters on two path sets → `FilterRegistrationBean`. **Not needed** when one filter really applies everywhere |
| ➕ gRPC interceptors | [ServiceTokenServerInterceptor](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java), `MetadataUtils.newAttachHeadersInterceptor` | gRPC bypasses servlet filters; wrap the whole service so new methods are covered |
| ➕ Allowlist vs denylist | [email ServiceTokenFilter](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java) | Default closed; exact match, not `startsWith` |
| ➕ Fail fast on bad config | `ServiceToken` / `ServiceTokens#require` | A blank secret stops startup and names the key; catches blank, not wrong |
| 📒 C3 Monitoring | `ALERT bank_auth_failed`, `delivery_auth_failed`, `email_auth_failed` | A wrong secret does not heal like a timeout; the alert line says what to fix. Not the email in the line |
| ➕ NetworkPolicy needs the CNI | [bank.yaml](../../k8s/apps/bank.yaml) | The object is only a request; check it is enforced (kindnet ≥ v0.24) |

Scenario anchors:
- "Inside the cluster anything could call bank's gRPC Transfer. I gave each called service its own secret, checked by a gRPC interceptor and a servlet filter, and the services refuse to start without it."
- "When bank refuses store's secret, the payment stays 'unknown' and is retried with the same key, never 'rejected', because the wrong secret is our fault. An alert says which secret to fix."

---

## 6. Follow-up chain

1. **The entry already blocked these paths. Why check again in the service?** → The entry only faces outward. A compromised pod, any compose container or a host process bypasses it. Defence in depth.
2. **Why not mTLS?** → mTLS also encrypts and avoids a shared secret, but needs a CA, certificates and renewal; days of work locally. A shared secret gave identity on both platforms the same day; the trade-off is written down (ADR-23).
3. **What if the secret leaks?** → That one callee is open until the secret is changed; the others are not (one per callee). Rotation today needs both sides restarted; accepting a current and a previous secret would remove the gap.
4. **Why constant-time comparison?** → `equals` stops at the first difference; timing many replies could reveal the secret byte by byte. `MessageDigest.isEqual` leaks only the length.
5. **How do you know the check is really enforced?** → Red tests showed the request really succeeded before (money moved); after the fix, the same requests get 401 / `UNAUTHENTICATED` and the balance is unchanged; on the running system, requests by hand and a pod-to-pod connection test.

---

## 7. How to say it

- "The gateway only keeps outsiders out. Inside the network, each service now checks a per-service secret, and won't start without one."
- "For money, a refused call isn't a failure. It stays unknown, is retried with the same key, and raises an alert."
- "Shared secrets were the practical choice; in production I'd move to mTLS through a service mesh."

---

## 8. Further reading

- `MessageDigest.isEqual` (constant time since Java 6u17): <https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html>
- Spring Boot: registering servlet filters, and `FilterRegistrationBean`: <https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.add-servlet-filter-listener>
- Spring Boot relaxed binding from environment variables: <https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables>
- gRPC interceptors and metadata: <https://grpc.io/docs/guides/interceptors/>, <https://grpc.io/docs/guides/metadata/>
- Spring Boot HTTP client detection for `RestTemplateBuilder`: <https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.clienthttprequestfactory>
- Kubernetes NetworkPolicy: <https://kubernetes.io/docs/concepts/services-networking/network-policies/>
- kind v0.24.0 release notes (kindnet NetworkPolicy): <https://github.com/kubernetes-sigs/kind/releases/tag/v0.24.0>

---

## 9. Self-check

1. Add `@Component` to delivery's `ServiceTokenFilter`. What breaks?
<details><summary>Answer</summary>
Spring Boot registers it for every path, so the frontend's `/api/deliveries/...` calls (which carry a user token, not the service secret) all get 401. FilterRegistrationBean limits it to `/internal/*`.
</details>

2. bank answers `UNAUTHENTICATED`. Why must store not mark the payment REJECTED?
<details><summary>Answer</summary>
REJECTED makes the payment FAILED, and Pay Now then charges with a new key. The money never moved, but the secret is store's mistake: once fixed, retrying the same key is safe and correct. UNKNOWN + alert keeps the money rules (ADR-05) intact.
</details>

3. (Only answerable if you really understand it) All tests were green and the review still found a missing alert. Why did the tests not catch it?
<details><summary>Answer</summary>
The red tests were written from the failure table, and the table only listed bank's gRPC refusal (C3). REST calls to bank (open account, account by email) were not a row, so no test covered them and the code was not changed there. Tests prove what the table asks; they cannot show what the table left out. A review comparing the four call paths noticed that one of them had no alert.
</details>

---

## 10. Replay time

**45 minutes**: read §1 and §2 (15 min) → run red: `git checkout 9dad7eb`, then `./gradlew :bankService:test --tests '*BankGrpcAuthIntegrationTest'` and watch a transfer without a token succeed (5 min) → `git checkout main`, run the same test green (5 min) → run `./gradlew :storeService:test --tests '*BankServiceTokenIntegrationTest'` and read the `ALERT` lines (5 min) → self-check (15 min).
