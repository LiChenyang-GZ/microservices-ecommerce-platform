# ADR-05 · A timeout is not a failure

**Status:** Accepted

## Context
When a call to bank times out, bank may already have moved the money. Marking the payment FAILED and letting the customer pay again with a new key would charge twice.

## Decision
- A call that gets no answer is retried with the **same** key, up to 5 times in a row (`payment.bank.fast-retries`, 500 ms apart).
- Still no answer: the payment becomes `PAY_UNKNOWN` (or `REFUND_UNKNOWN`), an `ALERT` line is logged, and the task is tried again every 10 minutes (`payment.bank.slow-retry-minutes`) until bank gives a definite answer.
- Only a definite "no" from bank (for example, not enough money) is a failure.
- While a payment is `PAY_UNKNOWN`, Pay Now does not start a new attempt.

## Consequences
- More states: `PAY_UNKNOWN`, `REFUND_PENDING`, `REFUND_UNKNOWN`.
- An unknown result needs an alert and, in a real shop, a person to look at it.
- If an order is cancelled while its charge is unknown and the charge later succeeds, the result handler finds the cancelled order and writes a refund.

## In the code
- [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java) (`runPayTask`, `runRefundTask`), [PaymentStatus.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentStatus.java)
- Tests: `PaymentTimeoutIntegrationTest`, `PayNowIntegrationTest`; scenario `03-bank-down-during-payment.ps1`
