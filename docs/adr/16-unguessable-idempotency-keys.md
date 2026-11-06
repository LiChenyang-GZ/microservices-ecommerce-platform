# ADR-16 · Idempotency keys carry a random part, and bank compares content

**Status:** Accepted (amends ADR-04)

## Context
The first key format was `PAY-{paymentId}-{attempt}` and `REFUND-{paymentId}`, and bank de-duplicated by key alone. A review found that the two together are a hole. The key was predictable (the payment id is visible in the UI), and bank answered a reused key with "the first result" without looking at the content. An attacker could use the victim's next key for a 0.01 transfer to themselves; store's real charge then got "already succeeded", and an order became PAID while the customer paid nothing.

## Decision
- store adds 16 random hex characters (64 bits from `SecureRandom`) to every key: `PAY-{paymentId}-{attempt}-{random}`, `REFUND-{paymentId}-{random}`. The random part is stored on the payment, never returned by any API, and a new one is made per charge attempt. Retries of the same task reuse the stored key.
- bank compares the content of a repeated key (transfer: type, from, to, amount; refund: type, original transaction). Same content → the first result. Different content → "key conflict", rejected, no money moves.

## Consequences
- Keys cannot be guessed in advance, and a reused key can never move money for a different request.
- Keys are longer in logs.

## In the code
- store [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java) (`payKey`, `refundKey`, `newKeyNonce`)
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java) (`previousTransferResult`, `previousRefundResult`)
- Tests: `PaymentKeyIntegrationTest`, `BankKeyConflictIntegrationTest`; [docs/learning/01-idempotent-payments.md](../learning/01-idempotent-payments.md) §2.12
