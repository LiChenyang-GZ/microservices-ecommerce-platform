# ADR-04 · One path moves money, and every call is idempotent

**English** | [中文](04-single-money-path-idempotent.zh-CN.md)

**Status:** Accepted (key format amended by ADR-16)

## Context
In the course version, more than one place called bank, each charge made up a random reference, and placing an order wrote two PAY tasks. A retry after a timeout could charge twice, and a failed refund was never retried.

## Decision
- Only `OutboxProcessor` calls bank. Placing an order, Pay Now, cancelling and a lost parcel only write PAY or REFUND tasks (ADR-03). An architecture test enforces this.
- Every task carries an idempotency key, fixed when the task is written and reused by every retry: `PAY-{paymentId}-{attempt}-{random}` and `REFUND-{paymentId}-{random}` (ADR-16). `attempt` only goes up when a charge has definitely failed and the customer presses Pay Now.
- bank stores the key under a unique constraint. The same key with the same content returns the first result and moves no money; the same key with different content is rejected as a conflict.
- bank locks both account rows (`SELECT … FOR UPDATE`, in account-number order), then checks the key again before moving money, so two concurrent requests with one key cannot both succeed.
- Pay Now locks the payment row first and starts a new attempt only if the last one FAILED.

## Consequences
- Any charge or refund can be retried safely and traced by its key.
- Payment is asynchronous: the frontend shows "processing" until the task has run.
- bank uses pessimistic row locks rather than `@Version`: the store account is changed by every payment, so optimistic locking would make most concurrent payments conflict and retry.

## In the code
- [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java), [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java)
- [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java), [Transaction.java](../../backend/bankService/src/main/java/com/comp5348/bank/model/Transaction.java) (unique key)
- [BankAccessArchitectureTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/BankAccessArchitectureTest.java)
- [docs/learning/01-idempotent-payments.md](../learning/01-idempotent-payments.md)
