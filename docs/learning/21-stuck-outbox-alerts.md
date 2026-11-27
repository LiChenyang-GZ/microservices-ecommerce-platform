# 21 · A stuck outbox must alert

**English** | [中文](21-stuck-outbox-alerts.zh-CN.md)

> Commits (branch `fix/outbox-stuck-alerts`): red `cbb8daf` → green `719e9c3`; after the review red `925139a` → green `bcd2677`.
> How it was found: the wave 2 Kafka review (Obsidian 26, findings #7 and #8), triaged as tier 1 on 2026-11-27.
> Replay time: **20 minutes**
> Before this: [01 · Idempotent payments](01-idempotent-payments.md), [07 · Kafka order events](07-kafka-order-events.md).

---

## 1. Problem

**Three loops could fail forever with only log lines: no limit, no ALERT.**
- `OutboxProcessor` counted a task's retries in the same transaction as the work. When releasing stock failed, the transaction rolled back, and the count went with it: retry count 0 forever, an ERROR every 5 seconds, stock held.
- The same shape in `UnpaidOrderCanceller`, which is where a failed payment's stock really comes back today (nothing has written the outbox's `FAILED` task since 2025-12-09): a cancel that fails every time rolled back every minute, ERROR only.
- One event that Kafka will never take (too big) held back every later event in `event_outbox`, store and delivery, with a WARN per second.

Tests that showed the wrong thing **really happened** before the fix (red). "Bad data" is a Postgres trigger that makes updating one stock row fail (`StoreIntegrationTest#breakStockRow`); the stuck event is a 1.2 MB row on real Kafka:
- [OutboxRetryCountIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/OutboxRetryCountIntegrationTest.java): after a failing round `retryCount` was still 0; no ALERT after 3 rounds
- [UnpaidCancelStuckIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/UnpaidCancelStuckIntegrationTest.java): 7 failed cancels, no ALERT
- [EventOutboxStuckIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/EventOutboxStuckIntegrationTest.java): the oversized row blocked the next order's `OrderPlaced`; 276 WARN lines, no ALERT
- `EventOutboxStuckTest` (store and delivery, fake Kafka): no ALERT after 10 or 30 failed rounds; after the review, no `unstuck` when another instance sent the stuck row

---

## 2. What it is (framework level)

### 2.1 A rollback takes everything in its transaction with it
Spring joins nested `@Transactional` calls into one transaction. If an inner call throws, it marks the whole transaction rollback-only; catching the exception does not undo that, and the commit then fails with `UnexpectedRollbackException`. Anything you wrote in that transaction, including "this failed once more", is gone. **Write the failure in a different transaction** (`REQUIRES_NEW`, or after the first one has ended).

### 2.2 Every retry loop needs a bound and an alarm
A transient error is fixed by trying again; a deterministic one is not. A loop that cannot tell them apart must at least stop or shout after N tries. The log line repeated every few seconds is not an alarm: nobody reads it.

### 2.3 Head-of-line blocking is a price of ordering
Sending events in id order and stopping at the first failure keeps per-order order (ADR-06). The cost: one bad row holds back everything after it. Keep the order, but measure the block (failed rounds in a row, how long the head has waited) and alert.

### 2.4 Per-instance state with several instances
A counter in memory is per process. With two instances, each counts and alerts on its own, and "it recovered" must be noticed by each one: here, by seeing that the stuck row is no longer first, whoever sent it.

---

## 3. Path

1. `OutboxProcessor#processOutbox` → a non-money task → `runOtherTask`: the work in `transactionTemplate`. Done → PROCESSED.
2. It throws, gives up, or fails on commit → `recordFailure` in a `REQUIRES_NEW` transaction: still PENDING? +1; at 3 → FAILED + `ALERT outbox_task_failed`.
3. `UnpaidOrderCanceller` (every minute) → `cancelIfStillUnpaid` fails → `noteFailure`: the 5th in a row → `ALERT unpaid_cancel_stuck`. Success or the order leaving PENDING_PAYMENT clears it.
4. `EventOutboxPublisher#publishBatch`: lock → batch → `noteHeadOfQueue` (the stuck row is gone from the front → unstuck) → send each; a failure → `noteFailure` (same row: +1; 10, or waited 5 minutes → `ALERT event_outbox_stuck`); a success → `noteSent`.

---

## 4. Why this way

### 4.1 Option A for both (Christy decided)
#7: fix the outbox count **and** alert on the 15-minute cancel, because that is the path new orders take. #8: both services' publishers, since the code and the harm are the same.

### 4.2 What the red showed 🤖
Every new test went red at the expected step. Two things were found while making it green: the ALERT printed `error={}` (SLF4J treats a final `Throwable` argument as the stack trace, not a placeholder value; now `toString()` of the innermost cause), and a full run printed the stuck ALERT three times (several cached Spring contexts, each with its own publisher; each instance alerts once, which is acceptable).

### 4.3 What the review found
0 must-fix, 0 should-fix, 7 could-fix (Obsidian 88).
- **Fixed** (red `925139a` → green `bcd2677`): the instance that alerted logs `unstuck` even if another instance sent the row; `recordFailure` leaves a task that is no longer PENDING alone (no false FAILED / ALERT); a swallowed-then-rolled-back error is named as such in `lastError`.
- **Tests added**: R5b pins the original #7 mechanism (an error caught inside, rollback on commit); R2 / R7 check `lastError` on the ALERT line itself.
- **Written down**: a non-money task has about 15 s (3 rounds) before FAILED; "waited 5 minutes" counts from `created_at`, so an old row alerts on its first chance failure after downtime.

---

## 5. Interview topics here

**Conclusion**: count failures outside the transaction that failed, and give every retry loop a bound and an alarm.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Transaction propagation, rollback-only | `OutboxProcessor#runOtherTask` / `recordFailure` | the count is written in `REQUIRES_NEW`, never in the work's transaction |
| ➕ Retry limits and alerting | `recordFailure`, `UnpaidOrderCanceller#noteFailure` | 3 tries → FAILED + ALERT; 5 failed cancels → ALERT, keep retrying |
| ➕ Head-of-line blocking | `EventOutboxPublisher#noteFailure` | keep the order, alert after 10 rounds or 5 minutes |
| ➕ State across instances | `noteHeadOfQueue` | "recovered" is seen by each instance, not only by the sender |

Scenario anchors:
- "A cleanup task's retry counter was written in the same transaction as the work. When the work failed, the rollback undid the counter, so it retried every five seconds forever. I moved the counter into its own transaction and added an alert at the limit."
- "Our event outbox stops at the first failed row to keep order. One oversized event would have stopped all events with only warnings, so I kept the order but alert when the same row fails ten rounds or has waited five minutes."

---

## 6. Follow-up chain

1. **Why not just catch the exception?** → Catching does not clear rollback-only; the commit still fails and takes the count with it.
2. **Why not skip the stuck row?** → The rows after it may be the same order's later events; skipping breaks per-order order. A person decides (fix or remove it).
3. **Why in memory and not a column?** → Nothing changes in the database while it is stuck, and a restart re-alerting once is acceptable; no schema change.
4. **Two pods, two ALERTs?** → Yes during a rolling update; each also logs its own recovery.

---

## 7. How to say it

- "Don't write the failure in the transaction that failed."
- "A retry loop without a limit is an outage that logs."
- "Keep the order, but know when it's stuck."

---

## 8. Further reading

- Spring Framework reference, transaction propagation (`REQUIRED`, `REQUIRES_NEW`, rollback-only): <https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html>
- Chris Richardson, Transactional outbox pattern: <https://microservices.io/patterns/data/transactional-outbox.html>

---

## 9. Self-check

1. Why did the retry count never go up?
<details><summary>Answer</summary>
It was written in the same transaction as the work. The failed stock release marked that transaction rollback-only, so the commit rolled back the count too.
</details>

2. Why did fixing only `OutboxProcessor` not fix "stock not given back after a failed payment"?
<details><summary>Answer</summary>
Nothing writes the `FAILED` outbox task any more; the stock of a failed payment comes back through `UnpaidOrderCanceller`, which had the same retry-forever shape.
</details>

3. (Only answerable if you really understand it) With two instances, why could an ALERT never get its `unstuck`, and what fixed it?
<details><summary>Answer</summary>
Only the instance that alerted can log `unstuck`, and it used to do so only when it sent a row itself. If the other instance sent the next row, the alerting one had nothing left to send. Now each instance notices that the stuck row is no longer first and logs it.
</details>

---

## 10. Replay time

**20 minutes**: read §1 and §2 (7 min) → `git checkout cbb8daf`, run `./gradlew :storeService:test --tests '*OutboxRetryCountIntegrationTest' --tests '*EventOutboxStuckTest'` and see the reds (6 min) → `git checkout 719e9c3`, run them green (4 min) → self-check (3 min).
