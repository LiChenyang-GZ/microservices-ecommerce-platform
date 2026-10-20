# 05 · Store calls bank over gRPC instead of HTTP: only the protocol changes, the money rules do not

**English** | [中文](05-grpc-bank-call.zh-CN.md)

> Commits: `b83b3ff test` (safety net, green on HTTP) → `840199a test(red)` → `427c3e0 feat`
> Replay time: **30 minutes**
> Failure table (31 rows) in Obsidian: `开发/电商项目/18-第2波B-gRPC-失败场景表.md`. This document covers **the concepts in this step**.

---

## 1. Problem

**The design (ADR-10) wants gRPC between services. But wave 1 had just fixed "a timeout is not a failure" on the payment path. Map one gRPC error code wrong while switching, and double charging is back.**

This is a "swap the implementation, keep the behaviour" step: there is no bug to reproduce, the tests are a safety net. Three steps:

| Step | Commit | Tests |
|---|---|---|
| 1. Add 6 protocol-neutral tests, green on the **old HTTP implementation** | `b83b3ff` | store 28 green |
| 2. Swap the fake bank from WireMock (HTTP) to a real gRPC server, add gRPC-only tests | `840199a` | store 37 red, bank 8 red: store still calls HTTP, bank does not listen on gRPC |
| 3. Swap the implementation | `427c3e0` | store 46, bank 20 all green |

The three tests that best show what can go wrong:

| What goes wrong | Test |
|---|---|
| A bank internal error is read as "rejected" → Payment FAILED → Pay Now with a new key → charged twice | [BankCallIntegrationTest.java:37](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankCallIntegrationTest.java#L37), [BankGrpcIntegrationTest.java:206](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L206) |
| Deadline set once at startup → every payment times out after 5 seconds | [BankCallIntegrationTest.java:78](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankCallIntegrationTest.java#L78) |
| Store gives up waiting, but bank has already charged | [BankGrpcIntegrationTest.java:241](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L241) |

---

## 2. What it is (framework level)

### 2.1 gRPC and `.proto`
**Plain words**: you write down in a `.proto` file which methods exist and which fields they carry; a tool generates the code for both sides. The client calls `bank.transfer(request)`, which looks like a local method but is a network call (HTTP/2 + protobuf binary).
- Contract: [bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto) (fixed in wave 0, not changed here).
- The `contracts` module generates `BankServiceGrpc`, `TransferRequest`, etc. with the `com.google.protobuf` Gradle plugin. Store and bank add `implementation project(':contracts')` in this step.
- Generated `BankServiceGrpc.BankServiceImplBase`: the server extends it and overrides `transfer` / `refund`. `BankServiceGrpc.newBlockingStub(channel)`: the client; the calling thread blocks until an answer or an error.

### 2.2 Three ways a call can end → `BankCallResult`
- **Status OK + `outcome`**: bank's answer. `SUCCEEDED` → success, `REJECTED` → definite failure.
- **A gRPC error status** (`StatusRuntimeException`, unchecked): `DEADLINE_EXCEEDED`, `UNAVAILABLE`, `INTERNAL`… **all** → `UNKNOWN`.
- **Status OK without an `outcome`** → `UNKNOWN` (new behaviour, see 2.5).
- Code: [BankAdapter.java:134](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L134) (`fromOutcome`), [BankAdapter.java:147](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L147) (`unknown`).
- Why even codes that "sound definite" (`UNIMPLEMENTED`, `INVALID_ARGUMENT`) are unknown: calling it a failure risks a double charge; calling it unknown only costs a few more retries with the same key (safe). The test runs all 16 error codes: [BankGrpcMappingIntegrationTest.java:43](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankGrpcMappingIntegrationTest.java#L43)
- The official docs say the same about `DEADLINE_EXCEEDED`: for operations that change state, it may be returned even if the operation completed.

### 2.3 A deadline is not a timeout
**Plain words**: an HTTP timeout says "how long to wait"; a gRPC deadline says "until what time".
- `stub.withDeadlineAfter(5, SECONDS)` means "5 seconds from **now**", a fixed point in time.
- So it has to be set **fresh for every call**: [BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80). Set once in the constructor, every call after the first 5 seconds fails at once with `DEADLINE_EXCEEDED`. I broke it on purpose (deadline on the stub): `deadlineIsPerCall` went red.
- When the deadline passes, store stops waiting, but **bank does not stop by itself** (official docs: stopping the server-side work is the server application's job). Bank may still charge and commit. That is "a timeout is not a failure"; see the third test in section 1.
- Same length as before: `bank.grpc.deadline-ms=5000`. HTTP had "connect 5 s + read 5 s"; now it is 5 s in total.

### 2.4 The channel: one long-lived connection, and reconnecting
- A `ManagedChannel` is the connection to bank (HTTP/2, many calls at once on one connection). Store builds one and every call shares it: [BankAdapter.java:63](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L63). `@PreDestroy` closes it on shutdown.
- `usePlaintext()`: no TLS, inside the container network, like the REST calls.
- **Reconnecting**: when the connection drops, gRPC reconnects with backoff (about 1 s, 1.6 s, 2.6 s…). By default (fail-fast) new calls **fail immediately** while it waits.
- `withWaitForReady()`: while there is no connection, the call is queued until the connection is ready, up to the deadline. Christy decided: use it.
  - Measured (`bankBriefRestart_paymentStillCompletes`, bank down for 1.5 s): without it, 2–3 UNKNOWN calls are wasted before success; with it, the first call succeeds.
  - First version of the test only checked "paid in the end" and stayed green without waitForReady. After review (#5) it also checks that no UNKNOWN was logged for the key. Counting requests at the fake bank is not enough: fail-fast calls never reach it. Broken on purpose, it is red 3 out of 3 (once even PAY_UNKNOWN).
  - Cost: when bank is down for long, every call waits the full 5 s, about 27 s for the 5 tries of one PAY task (same kind of issue as A#5 in the review backlog).
- `disableRetry()`: gRPC can retry on its own from configuration. Turned off so OutboxProcessor is the only retry layer (5 fast, then every 10 minutes). `noAnswer_bankSeesExactly5CallsPerRound` asserts exactly 5 calls per round.

### 2.5 proto3 default values: there is no null
- A proto3 field is never "unset": strings default to `""`, enums to the value numbered 0 (here `OUTCOME_UNSPECIFIED`).
- Three consequences:
  1. **Generated setters reject null**: `setReason(null)` throws `NullPointerException`. The adapter turns null into `""` first: [BankAdapter.java:158](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L158). Otherwise the exception escapes the adapter, OutboxProcessor only logs it, and the task retries every 5 seconds **without ever reaching PAY_UNKNOWN or raising an alert**.
  2. Success with transaction id `""` → stored as null, like the HTTP version.
  3. Bank forgets `outcome` → store reads 0 = `OUTCOME_UNSPECIFIED`. The HTTP version treated "no success field" as **rejected**; now it is **unknown** (Christy, 2026-10-20).
- Amounts are strings (bank.proto, never float). `BigDecimal.toString()` keeps scientific notation like `1E+2`, so the adapter uses `toPlainString()`: [BankAdapter.java:153](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L153)

### 2.6 Bank side: a business "no" and a crash are different things
- [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java) extends the generated base class and calls the **existing** `BankService`. Idempotency, row locks and key-conflict checks are untouched; REST and gRPC share one implementation.
- `BankService` returns `success=false` → OK + `REJECTED` + reason.
- An amount string that does not parse → also OK + `REJECTED` ([BankGrpcService.java:45](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L45)). Not `INVALID_ARGUMENT`: store would read it as unknown and retry every 10 minutes forever.
- `BankService` throws → `INTERNAL` ([BankGrpcService.java:92](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L92)); details only in bank's own log, no SQL or table names sent to the caller. **Never caught into REJECTED.**
- The answer cannot be sent (store gave up) → ignored. The money has committed; store retries with the same key and gets the same result: [BankGrpcService.java:102](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L102)

### 2.7 `SmartLifecycle`: when the gRPC server starts and stops
- Spring Boot starts Tomcat (REST) by itself, but not a gRPC server. [GrpcServerLifecycle.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java) implements `SmartLifecycle`:
  - `start()` runs after every bean (`BankService`, the connection pool) is ready. If the port is taken it throws and bank fails to start, so there is never a bank that is up without gRPC.
  - `stop()` runs **before** those beans are destroyed: `shutdown()` stops new calls, calls in flight get 5 seconds, then `shutdownNow()`.
- Port: `bank.grpc.port=${BANK_GRPC_PORT:9090}`. Tests use 0 (any free port), so they never clash with a bank already running locally.

### 2.8 Test double: a real gRPC server as the fake bank
- [FakeBankGrpc.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/FakeBankGrpc.java) runs a **real** Netty gRPC server on a local port, answers from a script ("first call 6 s late, then success") and records every request.
- Why not mock the stub with Mockito: deadlines, dropped connections and reconnects would all be fake, and rows 13 and 16 could not be tested.
- Test bodies do not know whether HTTP or gRPC is underneath: they call script helpers such as `bankTransferSucceeds()` and `transferRequests()`. So the same bodies were green on HTTP first and green again after the swap.
- Every test whose expected result is "unknown" first asserts that bank actually received the request. Otherwise a store that never reached the gRPC bank would also get "unknown", and the test would be **falsely green**.

---

## 3. Request path

One charge (the user has ordered; a PAY task sits in `payment_outbox`):

1. `OutboxProcessor.runPayTask` picks the task every 5 s and calls `bankAdapter.transfer(...)` (**unchanged**).
2. `BankAdapter.transfer` builds a `TransferRequest` (null → `""`, amount `toPlainString`) → `oneCall()` gives this call a 5 s deadline + waitForReady → sent over the channel to `bank:9090`.
3. Bank's Netty server → `BankGrpcService.transfer` → parse amount → `BankService.transfer` (check key → lock both account rows → check key again → move money → commit).
4. Answer `TransferResponse` (OK + SUCCEEDED + transaction_id).
5. Store: `fromOutcome` → `BankCallResult.succeeded` → OutboxProcessor → `paymentService.onPaySucceeded` (locks the order row, order PAID).
6. On errors, step 4 is an error status → `unknown(...)` → OutboxProcessor retries the same key up to 5 times → PAY_UNKNOWN + `ALERT payment_unknown`.

Account creation and account-by-email are not in the proto and stay on REST (`bank.service.url`). (`getBalance` had no callers and was removed.)

---

## 4. Why this way

| Choice | Alternative | Trade-off |
|---|---|---|
| 🤖 Plain grpc-java + a hand-written `SmartLifecycle` (Christy agreed) | Spring's spring-grpc auto-configuration | About 60 more lines, but every line can be explained; spring-grpc is shorter and hides what it starts |
| Every error code → unknown | Split by code (e.g. `UNIMPLEMENTED` = failure) | Splitting surfaces misconfiguration sooner, but one wrong code can charge twice. Same-key retries are safe, so no split |
| `waitForReady` (Christy's decision) | Default fail-fast | No wasted retries during a short bank restart; during a long outage every call waits the full 5 s |
| 🤖 `disableRetry()` | gRPC retry policies | One retry layer, easy to count calls and time; gRPC's transparent retry (resend when the call never left the client) is off too |
| 🤖 Bank errors are `INTERNAL` with only "bank internal error" | Send the exception message back | Less detail when debugging (read bank's log), but no SQL or table names leak |
| Only transfer / refund on gRPC | Move account calls to gRPC too | Not in the proto, and the contract is fixed; they move no money, HTTP is enough |

---

## 5. Interview topics here

**Verdict**: nothing in this step needs an "optimised" version. The interesting part is **how timeout, retry and idempotency work together**, and gRPC versus REST.

| Topic | Code | Judgement |
|---|---|---|
| 📒 F2 resilience | [BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80) | Timeout + retry + idempotency. A deadline **per call**; one retry layer (OutboxProcessor). No circuit breaker needed: one caller, and it already backs off to every 10 minutes after 5 tries |
| 📒 F3 idempotency | [BankGrpcIntegrationTest.java:241](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L241) | Still holds after the switch: store gives up, bank commits, the same-key retry gets the same transaction id. The protocol changed, the key rules did not |
| 📒 F7 REST API design | [BankController.java](../../backend/bankService/src/main/java/com/comp5348/bank/controller/BankController.java) | REST stays for the browser (universal, easy to debug); gRPC between services (strict contract, generated code). Not every API has to move |
| 📒 A4 checked vs unchecked | [BankAdapter.java:88](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L88) | `StatusRuntimeException` is unchecked; the compiler does not remind you to catch it. The adapter turns it into an `UNKNOWN` value at the boundary, so callers handle no exceptions |
| 📒 D2 is a singleton bean thread-safe | [BankAdapter.java:63](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L63) | `BankAdapter` is a singleton; channel and stub are shared by all threads. Fine, they are thread-safe and immutable: `withDeadlineAfter` returns a **new** stub |
| 📒 D1 IoC / bean lifecycle | [GrpcServerLifecycle.java:37](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java#L37) | `SmartLifecycle` lets Spring start the server after its dependencies and stop it before they are destroyed. A plain bean that runs nothing in the background does not need it; `@PostConstruct` is enough |
| 📒 G4 Testcontainers / real doubles | [FakeBankGrpc.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/FakeBankGrpc.java) | Same reason as "not H2": to test network behaviour (deadlines, drops), the double must be a real server, not Mockito |
| ➕ gRPC deadline vs timeout | [BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80) | A deadline is a point in time, set per call. For a single hop they are close; in a chain (A→B→C) a deadline can be passed downstream, a timeout cannot |
| ➕ Mapping gRPC status codes | [BankAdapter.java:147](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L147) | For calls that change state, every error is "unknown". Read-only calls (balance) can split by code: a retry or a misread costs no money |
| ➕ proto3 default values | [BankAdapter.java:158](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L158) | No null; `""` and 0 mean "not set". Keep enum value 0 for `UNSPECIFIED` and read it as "don't know", never as a business value |

**Scenario anchors**:
- "Switching to gRPC, the risky part wasn't the plumbing. It was mapping status codes. Every error code maps to 'unknown', because a wrong 'failed' can mean charging twice."
- "A gRPC deadline is a point in time, not a duration. If you set it once on the stub at startup, every call after five seconds fails. I have a test that waits six seconds and pays again."

---

## 6. Follow-up chain

1. **Why gRPC from store to bank?** → The contract lives in `.proto`, code is generated on both sides, a wrong field fails to compile; HTTP/2 runs many calls on one connection. REST stays for the browser.
2. **What happens on a timeout?** → `DEADLINE_EXCEEDED`, read as "unknown", retried with the same idempotency key. Bank may have charged already, so it cannot be a failure.
3. **Why isn't `UNAVAILABLE` "definitely not sent"?** → It covers both "could not connect" and "connection dropped mid-call". In the second case the request may have reached bank.
4. **Where is the deadline set?** → Fresh for every call. On a shared stub it would mean "5 seconds after startup".
5. **What does store do while bank restarts?** → The channel reconnects with backoff; with `waitForReady` the call waits for the connection, up to the deadline. Cost: during a long outage every call waits the full 5 s.

---

## 7. How to say it

- "Store calls bank over gRPC now. Only the protocol changed. Idempotency keys and the retry rules are the same."
- "Only an OK status with an explicit outcome counts as bank's answer. Everything else is unknown, and we retry with the same key."
- "I wrote the safety-net tests on the old HTTP version first. Then I swapped the fake bank for a real gRPC server, and the same test bodies had to pass."

---

## 8. References

- gRPC Deadlines: https://grpc.io/docs/guides/deadlines/ (what a deadline is; the server must stop its own work)
- gRPC Status codes: https://grpc.io/docs/guides/status-codes/ (`DEADLINE_EXCEEDED` may come after the operation completed)
- gRPC Wait-for-Ready: https://grpc.io/docs/guides/wait-for-ready/
- gRPC Retry: https://grpc.io/docs/guides/retry/ (on by default with no default policy; transparent retry; can be disabled)
- gRPC Java Basics: https://grpc.io/docs/languages/java/basics/ (ManagedChannel, blocking stub, ServerBuilder)
- proto3 default values: https://protobuf.dev/programming-guides/proto3/#default
- Spring `Lifecycle` / `SmartLifecycle`: https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor

---

## 9. Self-test

1. Store gets `UNIMPLEMENTED`. Why not mark the payment FAILED?
<details><summary>Answer</summary>FAILED lets the user press Pay Now: attempt + 1, a new key, another charge. If the first call did reach a bank and succeed (say, two bank versions during a rolling update), that is a double charge. Unknown only retries the same key; bank deduplicates by key, so at most one charge.</details>

2. What happens if `bank.withDeadlineAfter(5, SECONDS)` moves into the constructor? Which test catches it?
<details><summary>Answer</summary>The deadline becomes "construction time + 5 s". Every call after that starts already past its deadline and fails at once with DEADLINE_EXCEEDED → unknown → PAY_UNKNOWN. `deadlineIsPerCall_callAfterIdleStillSucceeds` goes red (I checked by breaking it).</details>

3. (Only if you really get it) In bank's gRPC service, catching every exception and answering `REJECTED` with the message looks friendlier. What is wrong?
<details><summary>Answer</summary>An exception is not bank's decision, and money may already have moved (an error after commit, before the answer). REJECTED makes store mark FAILED; Pay Now uses a new key → charged twice. Exceptions must be an error status (INTERNAL) so store treats them as unknown and retries the same key. `grpcTransfer_serviceThrows_errorStatus_notRejected` guards this.</details>

---

## 10. Replay time

30 minutes: run red (`840199a`) and green (`427c3e0`), read `BankAdapter` transfer / fromOutcome / unknown, then `BankGrpcService`, then the self-test.
