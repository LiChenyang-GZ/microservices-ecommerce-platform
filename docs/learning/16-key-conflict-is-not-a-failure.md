# 16 · A key conflict is not a failure

**English** | [中文](16-key-conflict-is-not-a-failure.zh-CN.md)

> Commits (branch `fix/key-conflict-is-unknown`): red `31674d5` → green `f85ea0b`; after the review `d9786b4`.
> How it was found: the A#4 review, finding 2 ("an old task whose account changed can still charge twice").
> Replay time: **25 minutes**
> Before this: [15 · A retry sends the first request](15-retry-sends-the-first-request.md) and [05 · gRPC bank call](05-grpc-bank-call.md) (how outcomes and error statuses map).

---

## 1. Problem

**bank had two kinds of "no" and sent both as `REJECTED`.** Insufficient funds means no money moved. A key conflict (the key was already used for a different request) means no money moved *for this call*, but the key's first request may have moved money. store treated both as a definite failure: FAILED, `PaymentFailed`, and Pay Now with a new key, which can charge twice. A refund that met a conflict was given up.

Tests that showed the wrong thing **really happened** before the fix (red):
- [BankGrpcIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java): a reused transfer key with other content, and a reused refund key with another original transaction, answered `REJECTED`
- [KeyConflictIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/KeyConflictIntegrationTest.java): no specific alert, and the round sent the conflicting request 5 times

---

## 2. What it is (framework level)

### 2.1 What a conflict meant then and means now
ADR-16 added the content check against an attacker who used your guessable key first: their request moved their money, so "no" was right. With 64 random bits in the key and a service token in front of bank (ADR-23), the attacker case is gone. What is left is store sending two different requests under one key, and then the first one may have charged.

### 2.2 Asymmetric costs decide the default
Wrongly "no" → a double charge. Wrongly "unknown" → one order held for a person, auto-cancelled after 15 minutes. In money systems, when you cannot tell, pick the mistake you can repair.

### 2.3 A new enum value, and why old clients are safe
`Outcome.KEY_CONFLICT = 3`. protobuf keeps unknown enum numbers (Java reads them as `UNRECOGNIZED`), and store's `BankAdapter#fromOutcome` already maps anything it does not know to unknown (an old rule from the gRPC step). So a new bank with an old store is safe, and **bank ships first**. The reverse (new store, old bank) still gets `REJECTED`.

### 2.4 Status codes vs outcomes
The contract's rule: status OK means bank answered; an error status means no answer. A conflict is an answer, so it is an outcome, not `ALREADY_EXISTS`. Matching the reason text was the third option, and the most fragile.

### 2.5 Do not retry what cannot change
A timeout may clear in a second, so store retries 5 times fast. A conflict will be the same answer every time until someone changes the task, so the round stops at once and the next look is in 10 minutes. One alert per payment; later looks log a WARN.

---

## 3. Request path

1. store sends `PAY-x-1-…` with accounts B; bank finds the key used with A.
2. bank: `previousTransferResult` → `TransferResponse.keyConflict(...)` → gRPC `Outcome.KEY_CONFLICT`. No money moves.
3. store: `fromOutcome` → `BankCallResult.keyConflict` → `OutboxProcessor#runPayTask` stops the round → `PaymentService#onPayKeyConflict`: `PAY_UNKNOWN`, `ALERT payment_key_conflict`, next look in 10 minutes.
4. Pay Now does nothing while `PAY_UNKNOWN`. A person copies bank's accounts into the task; the next look gets bank's real answer.

Refunds take the same path to `onRefundKeyConflict` (`REFUND_UNKNOWN`, `ALERT refund_key_conflict`).

---

## 4. Why this way

### 4.1 Outcome, unknown, retry every 10 minutes (Christy decided)
All four gate-0 choices as recommended: a new outcome; charge and refund both held as unknown with one alert; keep retrying so a manual fix finishes the job without another step.

### 4.2 What the red showed 🤖
Once the proto had the new value, store's old default already held the payment as unknown; the money was safe because of a rule written months earlier for a different reason. The store change is about operating it: a specific alert, once, and no resending in the same round.

### 4.3 What the review found
No must-fix or should-fix. Could-fix: the bank service-level conflict tests only checked `success == false` (now they check the flag, and turn red without it); comments and the contracts README still said "conflict = REJECTED"; the refund side had no manual steps; the release order was not written; a key whose first request had failed also waits for a person (kept, on purpose).

---

## 5. Interview topics here

**Conclusion**: when an answer can mean "maybe money moved", it is not a failure.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Failure vs unknown | `onPayKeyConflict` | unknown when the cost of being wrong is a double charge |
| ➕ Evolving a protobuf enum | `bank.proto`, `fromOutcome` | add a value; old readers map unknown values to the safe default; ship the producer first |
| ➕ Status codes vs payload outcomes | `BankGrpcService#outcome` | an answer is an outcome; an error status means no answer |
| ➕ Retry policy per error type | `OutboxProcessor#runPayTask` | transient → fast retries; deterministic → stop, slow retry, alert |
| ➕ Threat model drift | ADR-16 → ADR-29 | a rule designed for an attacker became a correctness bug once the attacker was locked out |
| ➕ Alert once | `alertOnce` | the first conflict is an ALERT; repeats are WARN |

Scenario anchors:
- "Our bank answered a reused idempotency key with different content as a rejection. That was right when keys were guessable, but after we made them random and authenticated the caller, a conflict could only be our own retry with a changed request, so the money might have moved. I added a KEY_CONFLICT outcome and store now holds it as unknown."
- "The red test surprised me: the store side already handled it safely, because an older rule mapped unknown enum values to unknown. That is why bank could ship first."

---

## 6. Follow-up chain

1. **Why not keep REJECTED?** → A conflict says nothing about whether the first request moved money; REJECTED tells store it did not.
2. **Why not let bank return the first transaction's result?** → Then "same key, different request" becomes a way to get an answer, which ADR-16 forbids; a person resolves it instead.
3. **What if store ships before bank?** → The old bank still says REJECTED for a conflict; that is why bank goes first.
4. **Why stop the fast retries?** → The answer cannot change until the request changes.
5. **Does the refund side risk a double refund?** → No: the refund key stays the same, bank refuses before moving money, and bank refuses a second refund of the same original transaction.

---

## 7. How to say it

- "If it might have moved money, it is not a failure."
- "Add enum values; let old readers fall back to the safe default; ship the producer first."
- "Retry what can change; alert on what cannot."

---

## 8. Further reading

- Protocol Buffers, *Enum behavior* (open enums, unknown values kept): <https://protobuf.dev/programming-guides/enum/>
- gRPC, *Status codes and their use*: <https://grpc.github.io/grpc/core/md_doc_statuscodes.html>

---

## 9. Self-check

1. Why was "conflict = rejected" correct when ADR-16 was written, and why is it wrong now?
<details><summary>Answer</summary>
Then, a conflict meant an attacker used your guessable key for their own request, so your money had not moved. Now keys are random and only store can call bank, so a conflict means store sent two different requests under one key, and the first may have charged.
</details>

2. Which deploys first, bank or store, and why?
<details><summary>Answer</summary>
bank. An old store maps the unknown outcome value to unknown, which is safe. A new store with an old bank still receives REJECTED for a conflict and would mark FAILED.
</details>

3. (Only answerable if you really understand it) The red test for store failed only on the alert line. Explain why the status assertions already passed, and what the store change adds.
<details><summary>Answer</summary>
After adding `KEY_CONFLICT = 3` to the proto, store's generated enum knew the value but `fromOutcome` had no case for it, so the `default` branch returned unknown: fast retries, then `PAY_UNKNOWN`. The change adds a dedicated outcome path: stop the round after one send, `ALERT payment_key_conflict` once, and the refund equivalent.
</details>

---

## 10. Replay time

**25 minutes**: read §1 and §2 (10 min) → `git checkout 31674d5`, run `./gradlew :bankService:test --tests '*BankGrpcIntegrationTest'` and see REJECTED (5 min) → `git checkout f85ea0b`, run it and `*KeyConflictIntegrationTest` green (5 min) → self-check (5 min).
