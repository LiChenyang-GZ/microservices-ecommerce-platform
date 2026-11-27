# ADR-34 · A stuck outbox is counted and alerts, it does not repeat forever

**English** | [中文](34-stuck-outbox-alerts.zh-CN.md)

**Status:** Accepted. Amends ADR-06 (a row that never goes out still holds back the rows after it, but it now alerts).

## Context
Two places could be stuck for good with nothing but repeated log lines (review 4b #7 and #8):

- **`OutboxProcessor`, non-money tasks** (old `FAILED` tasks that give stock back, `REFUNDED` refund emails). The work and its retry count shared one transaction. When `unholdProduct` failed it marked that transaction rollback-only, so the `retryCount + 1` was rolled back with the work: the task never reached `maxRetries`, stayed PENDING and logged an ERROR every 5 seconds, and the stock was never given back. No ALERT.
- **`EventOutboxPublisher`** (store and delivery). A row that can never be sent (bigger than Kafka's message limit, topic deleted) stops every round at itself, so every later order's events wait behind it. Only one WARN per round.

Reading the code for #7 turned up something the review had not: nothing has written a `FAILED` task since `7b654a5` (2025-12-09). After bank says no, the order stays PENDING_PAYMENT for Pay Now, and after 15 minutes `UnpaidOrderCanceller` cancels it and releases the stock. That path had the same shape: if the release fails every time (bad data), the whole cancel rolls back, an ERROR every minute, the stock stays held, no ALERT. Fixing only the outbox would have missed the path new orders actually take.

## Decision (Christy decided: option A for both)

- **`OutboxProcessor`**: a non-money task runs in one transaction (`runOtherTask`). When it fails (throws, gives up, or cannot commit because something inside marked it rollback-only), `recordFailure` adds 1 to its retry count in a transaction of its own (`REQUIRES_NEW`). At `maxRetries` (3) the task is FAILED and logs one `ALERT outbox_task_failed taskId= type= orderId= retries= lastError=`; for an old `FAILED` task it adds that the stock was not given back. The handlers throw instead of swallowing. `recordFailure` leaves a task that is no longer PENDING alone (the work did commit and the answer was lost, or another instance finished it; review 88).
- **`UnpaidOrderCanceller`**: counts failed cancels per order in memory; the 5th in a row (`unpaid-cancel.stuck-after-failures`) logs one `ALERT unpaid_cancel_stuck orderId= failures= lastError=`. The cancel is still retried every minute (order and stock untouched: no half cancel). The count is dropped when the cancel works or the order leaves PENDING_PAYMENT. No schema change.
- **`EventOutboxPublisher`, both services**: when the same row has failed `event-outbox.publisher.stuck-after-failures` (10) rounds in a row, or fails after waiting `…stuck-after-minutes` (5) since its `created_at`: one `ALERT event_outbox_stuck id= eventType= orderId= failures= waitingSeconds= error=`, with the innermost cause. When events move again (a send goes through, or the stuck row is no longer first because another instance sent it or someone removed it) one INFO `event_outbox_unstuck`. Order is still kept: the row still holds back the rest.
- When the error was caught inside and only showed up as a rollback on commit, `lastError` says so ("rolled back by an error caught inside … see the log lines before this") instead of Spring's "Transaction silently rolled back".
- Rejected: only the outbox (option B: misses `UnpaidOrderCanceller`); deleting `processPaymentFailed` and closing old `FAILED` tasks (option C: not without checking every environment's data first).

## Consequences
- None of the three can sit stuck without an ALERT any more; a deterministic failure ends as FAILED (outbox) or as one ALERT while it keeps retrying (cancel, events).
- Counts live in memory (cancel, events): a restart starts again and may alert once more. Each instance alerts on its own, so during a rolling update there can be two ALERTs for one stuck row; each instance also logs its own `unstuck`.
- A non-money task has about 15 seconds (3 rounds of 5 s, no back-off) before it is FAILED; a transient error can use that up. It is an email or an old task, and the ALERT asks a person to finish it.
- "Waiting 5 minutes" is measured from `created_at`: after downtime or a backlog, the first chance failure of an old row alerts at once (and `unstuck` follows a round later).
- Still only a WARN: a round that fails before sending (database trouble; readiness shows it), and "every send works but the commit fails". store's publisher thread dying on an `Error` is not caught (recorded for later).
- Local data was checked first: neither compose database had an old `FAILED` task or an unsent event.

## In the code
- store [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java) (`runOtherTask`, `recordFailure`), [UnpaidOrderCanceller.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/UnpaidOrderCanceller.java) (`noteFailure`), [EventOutboxPublisher.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java) (`noteFailure`, `noteHeadOfQueue`, `noteSent`); delivery [EventOutboxPublisher.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java)
- Tests: store `OutboxRetryCountIntegrationTest`, `UnpaidCancelStuckIntegrationTest`, `EventOutboxStuckIntegrationTest` (real Kafka, an oversized row), `EventOutboxStuckTest` (store and delivery); "bad data" is a Postgres trigger (`StoreIntegrationTest#breakStockRow`)
- [docs/learning/21-stuck-outbox-alerts.md](../learning/21-stuck-outbox-alerts.md)
