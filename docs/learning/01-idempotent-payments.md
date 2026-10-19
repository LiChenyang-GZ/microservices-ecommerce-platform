# 01 · Fixing charges and refunds: a timeout is not a failure

**English** | [中文](01-idempotent-payments.zh-CN.md)

> Commits: `49b4c70 test(red)` (rows 1–15), `67235f0 test(red)` (row 16) → `ce8d34d feat`
> Replay time: **60 minutes**
> The 16-row failure-scenario table lives in Obsidian: `开发/电商项目/03-第1波A-修钱-失败场景表.md`. This document covers **the concepts behind this step**.

---

## 1. The problem

**When store calls bank to charge or refund, one timeout, one double click, or "the charge succeeds just as the order is cancelled" is enough to charge twice, lose a refund, or leave the payment in the wrong state.**

The red commits reproduced 16 problems on the old code. The four most typical:

| Problem | Test that reproduces it | Why the old code got it wrong |
|---|---|---|
| A retry after a timeout charges twice | [PaymentTimeoutIntegrationTest.java:33](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PaymentTimeoutIntegrationTest.java#L33) | Every charge made up a random reference, and placing an order wrote **two** PAY tasks |
| Pay Now in two tabs charges twice | [PayNowIntegrationTest.java:101](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PayNowIntegrationTest.java#L101) | Pay Now called bank directly, with a millisecond timestamp in the reference and no lock |
| The charge succeeds after the order was cancelled; no refund | [PaymentResultIntegrationTest.java:57](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PaymentResultIntegrationTest.java#L57) | The charge result never looked at the order and set it back to PAID |
| 10 concurrent transfers from one account, only one is actually deducted | [BankConcurrencyIntegrationTest.java:64](../../backend/bankService/src/test/java/com/comp5348/bank/BankConcurrencyIntegrationTest.java#L64) | bank did "read balance → subtract in memory → write back" with no row lock (lost update) |

---

## 2. What it is (framework level)

### 2.1 Idempotency key
**In plain words**: give every "thing to do" a fixed name. However many times a request with that name arrives, bank does it once and answers every later copy with the first result.
- Charge: `PAY-{paymentId}-{attempt}`; refund: `REFUND-{paymentId}`. The name is fixed **when the task is written**, stored in `payment_outbox.idempotency_key`, and reused by every retry: [PaymentService.java:76](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L76)
- `attempt` only goes up when the user clicks Pay Now **after bank definitely said no**: [PaymentService.java:121](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L121)
- On bank's side, `transactions.transaction_ref` holds the key and has a unique constraint. bank looks the key up first and returns the first result if it finds one: [BankService.java:53](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L53)

### 2.2 Three outcomes: succeeded / rejected / unknown
- One call to bank has exactly three outcomes: `SUCCEEDED`, `REJECTED` (bank said no, e.g. insufficient funds) and `UNKNOWN` (timeout, connection reset, 5xx: **we don't know whether money moved**): [BankCallResult.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankCallResult.java)
- Only bank's own `success=false` (HTTP 400 with its JSON body) is a rejection. Everything else is unknown: [BankAdapter.java:91](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L91)
- The old bug: `RestTemplate` throws `HttpClientErrorException` on a 4xx. The old `BankAdapter` caught everything, retried 3 times, then reported "bank unavailable". So "insufficient funds" looked like "bank is down", and a timeout looked like "failed". Wrong in both directions.
- `RestTemplate` errors come in two kinds: `HttpClientErrorException` (4xx, the other side answered) and `ResourceAccessException` (I/O error including timeouts, no answer). The latter is a `RestClientException`.

### 2.3 Rule 7: a timeout is not a failure
- On `UNKNOWN`: retry **with the same key** straight away, up to 5 times. Still unknown → `PAY_UNKNOWN` (`REFUND_UNKNOWN` for refunds) + an alert, then one try with the same key every 10 minutes: [OutboxProcessor.java:163](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L163), [PaymentService.java:255](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L255)
- **Never** mark it FAILED and never add 1 to attempt: the money may already be taken, and a new key would take it again.
- "Ten minutes later" is a `nextAttemptAt` column plus an injected `Clock`, so tests just move the clock.

### 2.4 Pessimistic locking: `SELECT … FOR UPDATE`
**In plain words**: lock the row when you read it. Anyone else who wants to change it waits until you commit.
- In Spring Data JPA: `@Lock(LockModeType.PESSIMISTIC_WRITE)` on a repository method; Hibernate adds `FOR UPDATE` to the SQL: [BankAccountRepository.java:28](../../backend/bankService/src/main/java/com/comp5348/bank/repository/BankAccountRepository.java#L28), [PaymentRepository.java:26](../../backend/storeService/src/main/java/comp5348/storeservice/repository/PaymentRepository.java#L26)
- The lock goes away when the transaction commits or rolls back. So **never call another service while holding it**; one slow call would block everyone.
- Used in three places:
  1. bank transfer/refund locks both account rows (fixes the lost update in #16): [BankService.java:62](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L62)
  2. Pay Now locks the payment row (fixes two tabs, #13): [PaymentService.java:121](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L121)
  3. The charge result and order cancellation lock the order row (fixes #7): [PaymentService.java:208](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L208), [OrderService.java:187](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L187)

### 2.5 Lock in a fixed order to avoid deadlock
- If A→B locks A then B while B→A locks B then A, each waits for the other forever.
- bank always locks the two accounts **sorted by account number**: [BankService.java:187](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L187)
- store always locks **the order row first, then the payment row** (cancel, charge result, compensation).
- Postgres detects deadlocks and fails one transaction, but that is a safety net, not the design.

### 2.6 "Lock, then check again" under READ COMMITTED
- Postgres defaults to READ COMMITTED: **each statement** sees data committed before that statement started.
- Two requests with the same key reach bank at once (#3). Both look up the key and find nothing; both go to lock the accounts. The first gets the locks, moves the money, commits. The second was waiting for the lock; once it has it, it **looks up the key again**, now sees the committed transaction, and returns the first result: [BankService.java:64](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L64)
- The old code relied on the unique constraint alone: the second insert hit the constraint, its transaction became rollback-only, and the caller got `UnexpectedRollbackException`. The money was right, but store would think the charge failed.
- The unique constraint is still there as a backstop.

### 2.7 Outbox: one way in for money (rules 1 and 2)
- Placing an order, Pay Now, cancel, lost parcel, compensation and the refund endpoint **only write a PAY or REFUND task to `payment_outbox`**, in the same transaction as the business change: [OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java)
- Only `OutboxProcessor` calls bank: [OutboxProcessor.java:171](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L171). An ArchUnit test keeps it that way: [BankAccessArchitectureTest.java:42](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/BankAccessArchitectureTest.java#L42)
- Benefit: retries, timeouts and keys are done right in one place. Cost: the payment result is asynchronous, so the frontend shows "payment processing".

### 2.8 `@Transactional` scope: why the big transaction is gone
- The old `processOutbox()` was one `@Transactional` method: every task in a round, including the seconds spent waiting for bank, ran in one transaction.
- Now `processOutbox()` has no transaction. Each step calls one `@Transactional` method on `PaymentService` (another bean, so the call goes through Spring's proxy and the transaction applies). Each transaction is short, and **nothing is locked while we wait for bank**.
- `@Transactional` is an AOP proxy: only calls coming in from outside the bean start a transaction; a method calling another method of the same object (self-invocation) does not. That is why the transactional methods live in `PaymentService` and `OutboxProcessor` calls them.
- The older non-money events (create delivery, emails, compensation) run one per transaction through `TransactionTemplate` (programmatic transactions): [OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124)

### 2.9 An injected `Clock`
- Code asks for the time with `LocalDateTime.now(clock)`, where `clock` is a Spring bean: [ClockConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ClockConfig.java)
- Tests swap in a `MutableClock` they can move forward, so "10 minutes later" and "15 minutes later" need no waiting: [MutableClock.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/MutableClock.java)

### 2.10 Alert = a log line with a fixed marker
- `ALERT payment_unknown …`, `ALERT refund_unknown …`, `ALERT refund_rejected …`: [PaymentService.java:262](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L262), [:337](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L337)
- Operations search for the marker or set a log alert on it. Tests check it with Spring Boot's `OutputCaptureExtension`. An alert table comes later.

### 2.11 Test tools new in this step
| Tool | What it is for |
|---|---|
| Testcontainers + `@ServiceConnection` | A throwaway Postgres in Docker; Spring connects to it automatically and never touches the local webuser database |
| WireMock | Fake bank: `withFixedDelay` for timeouts, `Fault.CONNECTION_RESET_BY_PEER` for "no answer", scenarios for "first 3 time out, the 4th succeeds" |
| ArchUnit | Reads compiled classes and checks who calls whom |
| `OutputCaptureExtension` | Captures logs so a test can assert the alert was written |
| `CountDownLatch` | A starting gun so several threads start together |
| A `TaskScheduler` that never fires | `@Scheduled` jobs don't run in the background; tests call `processOutbox()` themselves |
| React Testing Library + jest fake timers | Frontend: click Pay Now → see "processing" → move time 3 s → the page refreshes to SUCCESS |

---

## 3. Request path walkthrough

### 3.1 Place an order → charge
```
POST /api/store/orders/create-with-payment
 └ StoreController → OrderProductService.createOrderWithPayment       (one transaction)
     ├ OrderService.createOrder            hold stock, create the order (no longer writes a PAY task)
     └ PaymentService.createPayment        Payment(attempt=1) + the one PAY task, key PAY-{id}-1
                                                       ↓ commit
OutboxProcessor.processOutbox()   every 5 s, no transaction
 └ runPayTask
     ├ PaymentService.startPayAttempt     (tx) lock payment row; still worth sending? retryCount + 1
     ├ BankAdapter.transfer               (no tx, no lock) HTTP POST /api/bank/transfer {idempotencyKey, …}
     │    └ bank: BankService.transfer    (tx) check key → lock both accounts in order → check key again → move money
     └ by outcome:
         SUCCEEDED → onPaySucceeded       (tx) lock order row → lock payment row →
                                              order still pending: PAID + PAYMENT_SUCCESS (creates the delivery)
                                              order cancelled:     REFUND_PENDING + REFUND task
         REJECTED  → onPayRejected        FAILED, order unchanged (waits for Pay Now)
         UNKNOWN   → same key again, up to 5 times → onPayStillUnknown: PAY_UNKNOWN + ALERT + retry in 10 min
```

### 3.2 Cancel a paid order → refund
```
POST /api/store/orders/{id}/cancel
 └ OrderService.cancelOrder               (one tx) lock order row → release stock → CANCELLED
     └ PaymentService.requestRefund       lock payment row → only if SUCCESS: REFUND task (REFUND-{paymentId})
OutboxProcessor → runRefundTask → BankAdapter.refund {idempotencyKey, originalTransactionId, reason}
 └ bank: BankService.refund               check key → lock both accounts → check key again → already refunded? → refund
```

### 3.3 Pay Now
```
POST /api/store/orders/{id}/retry-payment
 └ OrderProductService.retryOrderPayment  does the order belong to the caller? still awaiting payment?
     └ PaymentService.payNow              lock payment row:
                                              FAILED → PENDING, attempt+1, new PAY task
                                              PENDING / PAY_UNKNOWN → change nothing
                                              SUCCESS → already paid
Frontend OrderDetailPage: "payment processing", re-read every 3 s until SUCCESS / FAILED / PAY_UNKNOWN
```

### 3.4 Evidence: logs from one real order
Real bank + real store (throwaway Postgres; delivery deliberately not running, so compensation refunds the order):
```
15:28:22.698 OutboxService       PAY task written: taskId=1, orderId=1, key=PAY-1-1
15:28:25.567 BankAdapter         Calling bank transfer: key=PAY-1-1, amount=6999.00
15:28:25.628 BankService         Processing transfer: key=PAY-1-1, from=ACC-1792384095982, to=STORE_ACCOUNT_001
15:28:25.684 BankService         Transfer successful: txnId=1, key=PAY-1-1
15:28:25.713 BankAdapter         Bank transfer result: key=PAY-1-1, outcome=SUCCEEDED, txnId=1
15:28:25.732 PaymentService      Payment successful: orderId=1, key=PAY-1-1, bankTxnId=1
15:28:32.805 DeliveryAdapter     Delivery request failed after 3 attempts for orderId=1
15:29:02.891 OutboxProcessor     DELIVERY_FAILED event for orderId 1 has waited 30s. Triggering compensation (refund).
15:29:02.921 OutboxService       REFUND task written: taskId=4, orderId=1, key=REFUND-1
15:29:07.958 BankAdapter         Calling bank refund: key=REFUND-1, originalTransactionId=1
15:29:08.004 BankService         Refund successful: refundTxnId=2, key=REFUND-1, originalTxnId=1
15:29:08.020 PaymentService      Refund successful: orderId=1, key=REFUND-1, refundTxnId=2
```
Sending `PAY-1-1` to bank again by hand afterwards: `"Transaction already processed successfully"`, still transaction 1, balance unchanged.

---

## 4. Why it is done this way

| Choice | Alternative | Why / cost |
|---|---|---|
| **Pessimistic lock** on bank account rows (Christy's decision) | Optimistic `@Version` | The store account is a hot row: every payment changes it. Under optimistic locking most concurrent payments would conflict and retry. The locked section is tiny (two updates, no outside calls), so waiting is cheap |
| **Check the key again** after locking 🤖 | Catch the unique-constraint error and read the first result in a new transaction | The lock already makes same-key requests queue, so a second lookup is the simplest. The catch approach has to deal with a rollback-only transaction and is easy to get wrong. The constraint stays as a backstop |
| Fast retries **back to back in one round** 🤖 | One try per 5-second round | Faster result for tests and users. Cost: when all 5 time out, that round is stuck for 25 s (5 × 5 s timeout) and other tasks wait |
| **No retries inside `BankAdapter`** 🤖 | Keep the adapter's 3 retries | Two retry layers multiply (3 × 5 = 15 calls), and the adapter knows nothing about keys or state. Retrying happens in one place, OutboxProcessor |
| Big transaction in `processOutbox` removed 🤖 | Keep it | It held a connection and locks while waiting for bank; in #7 the cancel would be blocked, and the result transaction could read a stale order |
| Lock order: order, then payment 🤖 | Payment first | Cancel reads the order first anyway; what matters is the same order everywhere |
| Never sent (`retryCount = 0`) and order cancelled → FAILED without calling bank 🤖 | Charge anyway, then refund | Nothing was sent, so no money moved; no need to charge and refund. The count is committed **before** the call, so 0 really means "never sent" |
| Only 400 + `success=false` is a rejection; other 4xx count as unknown 🤖 | Every 4xx is a rejection | 404 or 401 mean a broken setup, not bank's business decision. Treating them as unknown retries and alerts, which is safer than quietly marking FAILED |
| bank does **not** record rejected refunds 🤖 | Record a FAILED row as for transfers | A rejected refund needs a human; after the cause is fixed, the same key can be retried. Rejected transfers are still recorded, because Pay Now moves to a new key |
| Refunds also deduplicated by original transaction 🤖 | Key only | Stops the same money being refunded twice under two keys (e.g. a manual refund with a wrong key) |
| Pay Now checks the order belongs to the caller 🤖 | No check | The old code charged **the caller's** account for someone else's order. Now the owner's account is charged and only the owner can trigger it |
| Balance pre-check before Pay Now removed 🤖 | Keep it | The balance can change between the check and the charge; bank's rejection reason is shown on the page instead |
| bank still accepts `transactionRef` and still returns `success` 🤖 | New fields only | The frontend Add Money page (outside this step) still uses the old names; it must keep working |
| Alert = log line with a fixed marker (Christy's decision) | Alert table + admin page | Searchable and alertable today; a table comes later |
| Frontend hides Pay Now while PENDING / PAY_UNKNOWN; no polling for PAY_UNKNOWN 🤖 | Always show the button / always poll | The button would do nothing and look broken. With an unknown result bank is retried every 10 minutes, so polling every 3 s is pointless |

**Later**:
- **Several store instances**: today only one `OutboxProcessor` is assumed. Two instances could run the same task; money stays correct thanks to the key, but task state could be written twice. Claim tasks with `SELECT … FOR UPDATE SKIP LOCKED`, or use `pg_try_advisory_xact_lock` as in ADR-06.
- **Alert table**: store alerts like delivery's DLQ alerts, show them in the admin page, mark them handled.
- **A slow round**: move slow tasks to their own thread pool, or make one try per round.
- In step 6 (gRPC), `BankAdapter.post`'s three-way split reads gRPC status codes instead (`DEADLINE_EXCEEDED`, `UNAVAILABLE` → unknown).

---

## 5. Interview fundamentals in this code

**Bottom line**: this step **did** need a real fix: bank account rows had no lock and concurrent transfers lost deductions (#16), so a pessimistic lock was required. Nothing else needs a heavier tool: no SERIALIZABLE, no Redis distributed lock, no Java `synchronized`.

| Topic | Code | The call made here |
|---|---|---|
| 📒 E3 optimistic vs pessimistic | [BankAccountRepository.java:28](../../backend/bankService/src/main/java/com/comp5348/bank/repository/BankAccountRepository.java#L28) | **Pessimistic**: the store account is a hot row, conflicts are normal, and optimistic would fail and retry a lot; the lock is short. **Switch to optimistic** when conflicts are rare or a user sits between read and write (admin product edits keep `@Version`). **Not needed**: read-only balance queries |
| 📒 E3 follow-up: why not synchronized | [OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124) | Two tabs are two HTTP requests that may hit two instances; `synchronized` only covers one JVM. The lock has to live in the database everyone shares |
| 📒 B6 deadlock | [BankService.java:187](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L187) | Sorted account numbers break the circular wait. store always locks order, then payment. **No need** for tryLock with timeouts: a fixed order cannot form a cycle |
| 📒 E4 isolation levels | [BankService.java:64](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L64) | Postgres' default READ COMMITTED: after the lock, the next SELECT sees what others committed, so "lock, then check again" works. **No need** for SERIALIZABLE everywhere |
| 📒 F3 idempotency | [PaymentService.java:76](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L76) | The notes say "a unique constraint rejects the second insert". This step found that **is not enough**: the second caller gets an error and thinks it failed (#3 red). So add "lock + check again" and the duplicate gets the first result |
| 📒 F4 outbox | [OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java) | Refunds are tasks too, written in the cancel's transaction; bank's key handles a task that runs twice. SKIP LOCKED for several instances is **not done yet**; one instance today |
| 📒 F2 resilience | [BankAdapter.java:91](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L91) | Timeout → same-key retry → unknown → slow retry + alert. The adapter's own retries are gone so retries don't multiply |
| 📒 F5 saga | [CompensationService.java:74](../../backend/storeService/src/main/java/comp5348/storeservice/service/CompensationService.java#L74) | Compensation (delivery creation failed → refund) only writes a REFUND task, so the compensation itself can be retried |
| 📒 D4 @Transactional | [OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124) | Big transaction removed; transactional methods live in another bean (PaymentService) so self-invocation can't skip them |
| 📒 C3 monitoring | [PaymentService.java:262](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L262) | Alerts are fixed-marker log lines, checked by tests; an alert table later |
| 📒 G4 Testcontainers | [StoreIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/StoreIntegrationTest.java) | Row locks, unique constraints and READ COMMITTED only behave correctly in real Postgres |
| ➕ Telling 4xx / 5xx / timeout apart | [BankAdapter.java:98](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L98) | Only "the other side said no" is a failure; no answer or an answer we can't read is unknown |
| ➕ Injected Clock | [ClockConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ClockConfig.java) | Time-based logic becomes testable without sleep |
| ➕ Hibernate first-level cache + FOR UPDATE | [PaymentService.java:159](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L159) | If the entity was already loaded in this transaction, a FOR UPDATE query may hand back the cached, stale object. So each transaction **locks on its first read** |

**Scenario anchors**:
- *"The store account is a hot row. Every payment touches it. So I lock it, briefly, instead of letting most writers fail and retry."*
- *"A unique constraint stops the second charge, but the second caller gets an error. I lock first, then check the key again, so it gets the first result."*
- *"There's no network call while a lock is held. Locks are for a few updates, not for waiting on the bank."*

---

## 6. Follow-up question chain

1. **How do you make sure a retry doesn't charge twice?** → An idempotency key, fixed when the task is written and reused on every retry; bank deduplicates on it.
2. **And after a timeout? Mark it failed and let the user pay again?** → No. A timeout means unknown. Retry the same key 5 times; still unknown → PAY_UNKNOWN + alert, retry every 10 minutes. Only bank's explicit rejection is a failure, and only then does Pay Now use a new key (attempt + 1).
3. **Two requests with the same key reach bank at the same time?** → Neither finds the key. Both lock the account rows; the second waits for the first to commit, then checks the key again, finds it, and returns the first result. The unique constraint is the backstop.
4. **Why pessimistic, not optimistic?** → The store account changes on every payment, so conflicts are normal; the locked section is two updates with no network call. Accounts are locked in account-number order to avoid deadlock.
5. **What if you called bank while holding a lock?** → One slow call blocks everyone and ties up the connection pool. So `OutboxProcessor` has no big transaction and holds nothing while calling bank; the result is handled in a new short transaction that locks the order row.
6. (Deeper) **Several store instances running OutboxProcessor?** → Not done yet. Money stays correct (keys), but a task could be processed twice. Next: claim tasks with `FOR UPDATE SKIP LOCKED`.

---

## 7. How to say it

> Payments go through one place. The API only writes a task, and one processor calls the bank with a fixed key, like PAY-12-1.
> A timeout means unknown, not failed. I retry with the same key. After five tries I mark it unknown, alert, and retry every ten minutes.
> On the bank side, I lock the two account rows in a fixed order, then check the key again. That fixed a lost update I reproduced with ten concurrent transfers.

---

## 8. Further reading

- Idempotency keys: [Stripe · Idempotent requests](https://docs.stripe.com/api/idempotent_requests) (a retry with the same key returns the saved first result)
- Outbox: [microservices.io · Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- Row locks and deadlocks: [PostgreSQL · 13.3 Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html) ("acquire locks on multiple objects in a consistent order")
- What FOR UPDATE sees after waiting, under READ COMMITTED: [PostgreSQL · 13.2 Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- `@Lock`: [Spring Data JPA · Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- Self-invocation and transactions: [Spring · Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- `TransactionTemplate`: [Spring · Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- `Clock`: [Java 17 · java.time.Clock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/Clock.html) (inject a Clock and swap it in tests)
- Testing: [Spring Boot · Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html), [Spring Boot · OutputCaptureExtension](https://docs.spring.io/spring-boot/reference/testing/test-utilities.html#testing.utilities.output-capture), [WireMock · Simulating Faults](https://wiremock.org/docs/simulating-faults/), [WireMock · Stateful Behaviour](https://wiremock.org/docs/stateful-behaviour/), [ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)

---

## 9. Self-check

1. A user clicks Pay Now while the payment is PAY_UNKNOWN. Why can't we charge again with a new key?
<details><summary>Answer</summary>PAY_UNKNOWN means we don't know whether the last attempt moved money. If bank did take it, a new key is a new transfer, so the customer pays twice. Only FAILED (bank said no, so no money moved) allows a new key.</details>

2. Two transfers with the same key reach bank at once. The old code got the money right. Why was #3 still red?
<details><summary>Answer</summary>The unique constraint stopped the second insert, so money moved once, but the second request's transaction became rollback-only and its caller got UnexpectedRollbackException. store would think the charge failed although the money was taken. The new code makes the second request wait for the lock, check the key again, and return the first result.</details>

3. (Only answerable if you really get it) If `OutboxProcessor.processOutbox()` were `@Transactional` again, what would happen in #7 (the user cancels while the charge is in flight)?
<details><summary>Answer</summary>The whole round becomes one transaction. The payment row locked by startPayAttempt stays locked until the round ends, so the user's cancel blocks in requestRefund until bank answers. And within that one transaction (one persistence context) an entity read earlier can be stale, so onPaySucceeded might not see the latest order status. Short transactions with no lock held during the bank call avoid both.</details>

---

## 10. Replay time

**60 minutes**: run red and green (10 min) → read the money code in the order security → resources → concurrency → error handling → performance (30 min) → the 🤖 choices and the fundamentals table (10 min) → say it out loud in English and do the self-check (10 min).
