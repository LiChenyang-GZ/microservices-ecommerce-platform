# ADR-28 · A retried charge sends exactly the accounts of its first send

**English** | [中文](28-a-retry-sends-the-first-request.zh-CN.md)

**Status:** Accepted. Amends ADR-16 (bank compares content, so store must send the same content).

## Context
A PAY task can be sent several times: up to 5 fast retries in one round, then one try every 10 minutes while the result is unknown (ADR-05). Every send used the same key, but `PaymentService#startPayAttempt` read the buyer's bank account again before each one. Two things went wrong when that read changed between sends:
- **The account could not be read** (cleared, row deleted, order row gone): the task was marked FAILED. FAILED means "bank said no", so the page showed Pay Now, and Pay Now starts a new attempt with a new key. If the first send had in fact charged, the buyer paid twice. This also happened in the fast retries, while the payment was still PENDING, so "is it PAY_UNKNOWN?" was the wrong question; the right one is "was this key sent?".
- **The account had changed**: the same key went out with another `fromAccount`. bank compares content (ADR-16) and answers `KEY_CONFLICT` with success = false, which store reads as a "no": FAILED again, and Pay Now again.

Today no endpoint clears or changes a buyer's bank account, so this needed the data changed by hand. It was finding A#4 of the first review.

## Decision

### The first send records the accounts; every retry sends those (Christy decided: option C)
- `payment_outbox` gets `from_account` and `to_account`. Before a task's first send, `startPayAttempt` reads the buyer's account and `bank.store.account`, writes both onto the task, and adds 1 to `retryCount`, all in one transaction that commits before bank is called.
- A task that has them never reads the buyer's account again. The amount comes from `Payment.amount`, which is written once. So **one key always carries one request**, which is what makes "retry with the same key" safe.
- A new attempt (Pay Now after FAILED) is a new task: it reads the account again and records it for its own key.

### Never sent and no account: FAILED, as before
`retryCount` is counted before the call, so 0 means bank never got this key. No money can have moved, and Pay Now may start again. The same reasoning already covered "cancelled before the first send".

### Sent before the columns existed and no account now: hold it
Tasks written before this change have empty columns. If one was already sent and the buyer's account cannot be read, store does not send (it does not know which account the first send used) and does not mark it FAILED: the payment becomes `PAY_UNKNOWN`, `ALERT payment_no_account` is logged once (then a WARN every 10 minutes), and the task is looked at again in 10 minutes.

What a person does with that alert: look the key up at bank (`transaction_ref`). Found → copy its accounts into the task's `from_account` / `to_account`; the next look sends the identical request and gets bank's real answer. Not found → the charge never reached bank, and marking the payment FAILED is safe. Linking the buyer a new bank account is the wrong fix: same key, different account, `KEY_CONFLICT`.

### bank says charged but the order row is gone: record it and stop
Found in review: with the accounts recorded, a task whose order row was deleted still goes out. `onPaySucceeded` used to throw on the missing order, roll back and resend every round. Now it records the payment as SUCCESS, closes the task and logs `ALERT payment_no_order`. There is nothing to ship; whether to refund is a person's call.

### Rejected
| Option | Why not |
|---|---|
| A only: do not mark a sent task FAILED when the account is missing | leaves the "account changed" case, which ends at `KEY_CONFLICT` and FAILED |
| treat `KEY_CONFLICT` as unknown instead | right, and still to do (below), but on its own it leaves every changed-account retry stuck as unknown when the original request could simply be sent again |

## Consequences
- **Old tasks with a changed account can still charge twice.** A task that was on its way when this was deployed has no recorded accounts; if the buyer's account was changed (not cleared) in the meantime, the retry sends the new one. The fix that covers every "same key, different request" source (old tasks, several store replicas some day, a future regression) is in store's reading of bank's answer: `KEY_CONFLICT` must not count as a "no". It needs a new outcome in `bank.proto`. Next item.
- Two columns on `payment_outbox`, added by `ddl-auto=update` (nullable, no default: Postgres only changes the catalogue). No startup SQL was needed (ADR-21). Rolling back is safe: older code ignores the columns.
- A task can have its accounts recorded and never be sent (the process stopped between the commit and the call). The next round sends that same request, which is safe; it just will not be marked FAILED for a missing account later.
- With two `OutboxProcessor`s, both could read a task before either recorded the accounts. store runs one replica (ADR-17), so this cannot happen today.

## In the code
- store [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java) (`startPayAttempt`, `holdUnknownWithoutAccount`, `onPaySucceeded`), [PaymentOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentOutbox.java) (`fromAccount`, `toAccount`)
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java) (`previousTransferResult`, `sameTransfer`: why a different request under the same key is a conflict)
- Tests: store `PayRetryAccountIntegrationTest`
- [docs/learning/15-retry-sends-the-first-request.md](../learning/15-retry-sends-the-first-request.md)
