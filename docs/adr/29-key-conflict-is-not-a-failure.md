# ADR-29 · A key conflict is not a failure: bank says KEY_CONFLICT, store holds it as unknown

**English** | [中文](29-key-conflict-is-not-a-failure.zh-CN.md)

**Status:** Accepted. Amends ADR-16 (a different request under a used key was "rejected") and ADR-28 (its "next item").

## Context
bank compares a reused idempotency key with the request it first came with (ADR-16). A different request got `success = false`, which the gRPC layer sent as `REJECTED`, the same outcome as insufficient funds. store reads `REJECTED` as "no money moved": a charge becomes FAILED, a `PaymentFailed` event goes out, and Pay Now starts a new attempt with a new key; a refund is given up.

ADR-16 was written against **someone else using your key first**. Then the key was guessable and anyone could call bank; the other party's request moved their money, not yours, so "no" was right. Neither holds now: keys carry 64 random bits and never leave store, and bank only accepts store's service token (ADR-23).

So today a conflict almost always means **store itself sent a different request under one key**: a task that was on its way when ADR-28 shipped and whose buyer account changed, two store instances some day, a regression. The key's first request may have charged or refunded. Reading that as "no" charges twice. Finding 2 of the A#4 review.

The two mistakes do not cost the same: "no" when money moved is a double charge; "unknown" when nothing moved is one order held until a person looks (it is cancelled after 15 minutes anyway).

## Decision (Christy decided, all as recommended)

### bank answers `KEY_CONFLICT`
`bank.proto`'s `Outcome` gains `KEY_CONFLICT = 3`. `BankService` marks its two conflict answers (`previousTransferResult`, `previousRefundResult`) and `BankGrpcService` sends that outcome. Real rejections (insufficient funds, unknown account, bad amount, nothing to refund) stay `REJECTED`: bank returns them before any money moves.

Rejected: a gRPC error status (`ALREADY_EXISTS`) would need no proto change, because store already reads every error status as unknown, but it breaks the contract's rule that status OK means "bank answered" and error means "no answer"; store matching the reason text would break silently the day the wording changes.

### A charge that meets a conflict is unknown
`PaymentService#onPayKeyConflict`: `PAY_UNKNOWN`, no FAILED, no `PaymentFailed`, Pay Now does nothing. `ALERT payment_key_conflict` once per payment, then a WARN. The round stops at once (resending now gets the same answer), and the same key goes out again every 10 minutes, so once a person has fixed the task's request the next look gets bank's real answer by itself.

What a person does: compare the task's request with bank's transaction for the key (`transaction_ref`) and copy bank's accounts into the task's `from_account` / `to_account` (ADR-28).

### A refund that meets a conflict is unknown too
`PaymentService#onRefundKeyConflict`: `REFUND_UNKNOWN`, `ALERT refund_key_conflict` once, the task stays and retries every 10 minutes. It used to be given up (`ALERT refund_rejected`, task FAILED).

What a person does: find bank's transaction for the refund key. It refunded this payment's real charge → set `payment.bank_txn_id` to that original transaction, and the next look gets bank's success. It refunded another transaction → the key collided: give the payment a new refund key; bank's check for an earlier refund of the same original transaction stops a second refund.

## Consequences
- **Ship bank before store**, or both in one rollout. A store that does not know an outcome value treats it as unknown (`BankAdapter#fromOutcome`'s default), so a new bank with an old store is safe: the payment becomes `PAY_UNKNOWN` with the generic `ALERT payment_unknown`. A new store with an old bank still gets `REJECTED` for a conflict.
- **Held orders need a person.** A conflict never resolves by itself: the task keeps retrying every 10 minutes until someone fixes its request. One alert per payment.
- **Deliberately cautious when the first request had failed.** If the key's first request was itself rejected at bank (insufficient funds) but store never saw the answer, a later different request still gets `KEY_CONFLICT`, and the order waits for a person instead of becoming FAILED. Telling store what the first request did would turn "same key, different request" into a way to get an answer, which ADR-16 rules out.
- The red tests showed that `fromOutcome`'s old rule ("an unknown outcome is unknown") already kept the money safe once the proto had the new value; store's change adds the specific alert, alerts once, and stops resending in the same round.

## In the code
- contract [bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto) (`Outcome.KEY_CONFLICT`), [contracts README](../../backend/contracts/README.md)
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java) (`previousTransferResult`, `previousRefundResult`), [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java) (`outcome`), `TransferResponse` / `RefundResponse` (`keyConflict`)
- store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java) (`fromOutcome`), [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java) (`runPayTask`, `runRefundTask`), [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java) (`onPayKeyConflict`, `onRefundKeyConflict`, `alertOnce`)
- Tests: bank `BankGrpcIntegrationTest`, `BankKeyConflictIntegrationTest`; store `KeyConflictIntegrationTest`
- [docs/learning/16-key-conflict-is-not-a-failure.md](../learning/16-key-conflict-is-not-a-failure.md)
