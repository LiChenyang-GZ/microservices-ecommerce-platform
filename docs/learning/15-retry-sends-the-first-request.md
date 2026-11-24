# 15 · A retried charge sends the first request again, not a fresh one

**English** | [中文](15-retry-sends-the-first-request.zh-CN.md)

> Commits (branch `fix/pay-unknown-no-account`): red `17aeade` → green `8705325`; after the review red `f71e8a4` → green `3c46b22`.
> How it was found: finding A#4 of the first review ("no bank account → FAILED, whatever the payment state").
> Replay time: **30 minutes**
> Before this: [01 · Idempotent payments](01-idempotent-payments.md) (same key on every retry; a timeout is not a failure).

---

## 1. Problem

**The key stayed the same between retries, but the request did not.** Before every send, `PaymentService#startPayAttempt` read the buyer's bank account again. If the read came back empty, the task was marked FAILED; FAILED shows Pay Now, and Pay Now charges with a new key. If the first send had charged (its answer lost in a timeout), the buyer paid twice. If the read came back with a *different* account, bank saw the same key with different content, answered `KEY_CONFLICT` (success = false), and store read that as a "no": FAILED again.

Tests that showed the wrong thing **really happened** before the fix (red), all in [PayRetryAccountIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PayRetryAccountIntegrationTest.java):
- PAY_UNKNOWN, account cleared, slow retry, Pay Now: bank received **two keys**, `PAY-x-1-…` and `PAY-x-2-…`
- account cleared or deleted after the first send: FAILED, in the slow retry and in the fast retries (payment still PENDING)
- account number changed: the same key went out with the new number
- from the review: order row deleted after the first send: bank called every round, the task never closed

---

## 2. What it is (framework level)

### 2.1 An idempotency key names a request, not just an attempt
bank stores the key with the request it came with and compares the next one ([BankService#previousTransferResult](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)). Same key, same request → the first answer. Same key, different request → conflict, because it might be someone else's request. Stripe does the same. So "retry with the same key" is only safe if the retry is **the same request**: same accounts, same amount.

### 2.2 Anything a retry reads again can change under it
The amount was safe (written once on `Payment`). The accounts were read live. The fix writes them down at the moment the key is first used: `payment_outbox.from_account` / `to_account`, set in the same transaction that adds 1 to `retryCount`, committed before bank is called. After that a retry reads nothing that can move.

### 2.3 "Was it sent?" is the question, not "what state is the payment in?"
`retryCount` is counted before the call, so 0 means bank never got this key. Only then is FAILED safe. The bug used no check at all; checking for PAY_UNKNOWN would still have missed the fast retries, where the payment is PENDING but the key has gone out.

### 2.4 Old rows have no record: hold, alert, and say what a person does
Tasks written before the columns existed and already sent cannot be resent safely if the account is gone (which account did the first send use?). They become PAY_UNKNOWN with one `ALERT payment_no_account`, then a WARN every 10 minutes. The comment on `PaymentService#holdUnknownWithoutAccount` gives the manual fix: copy the accounts from bank's transaction for that key.

### 2.5 An exception inside the result handler is a retry loop
With the accounts recorded, a task whose order row is gone still goes out; `onPaySucceeded` threw on the missing order, the transaction rolled back, the task stayed PENDING, and the next round sent again. A result handler must always end the task in a state it can leave: here SUCCESS plus `ALERT payment_no_order`, because bank said the money moved.

---

## 3. Request path

1. Place order → PAY task, `retryCount = 0`, accounts empty.
2. `OutboxProcessor#runPayTask` → `startPayAttempt`: reads the buyer's account and `bank.store.account`, records both, `retryCount = 1`, commit → call bank with key K.
3. Timeout → fast retries: each one `startPayAttempt` → accounts already recorded → the same K, the same accounts.
4. 5 timeouts → `PAY_UNKNOWN`, next look in 10 minutes → same request again → bank answers → `onPaySucceeded` (or `onPayRejected`).
5. Pay Now is a no-op while PENDING / PAY_UNKNOWN; only FAILED starts a new task, which records its own accounts.

---

## 4. Why this way

### 4.1 Record the accounts (Christy decided: option C)
Option A (do not mark a sent task FAILED when the account is missing) was smaller but left the changed-account case. Option B (record the accounts) makes every retry the same request; C is B plus A's rule for rows written before the columns existed.

### 4.2 Never sent and no account stays FAILED 🤖
Nothing can have been charged, and keeping FAILED lets the buyer try again after fixing the account.

### 4.3 What the review found
No must-fix. Should-fix: the order-row-gone loop (§2.5, fixed). Could-fix: the alert repeated every 10 minutes (fixed: once, then WARN), the PENDING half of the old-task branch had no test (added, and checked that it turns red when that branch is removed), and **old tasks whose account was changed can still charge twice** — written down; the general fix is that store must not read `KEY_CONFLICT` as a "no".

---

## 5. Interview topics here

**Conclusion**: the same key must carry the same request, so record what a retry would otherwise read again.

| Topic | Where | The call made here |
|---|---|---|
| ➕ Idempotency key = key + request fingerprint | `BankService#sameTransfer` | a different request under a used key is a conflict |
| ➕ Unknown is not failure; "sent" is the boundary | `startPayAttempt` (`retryCount`) | FAILED only when bank never got the key |
| ➕ Snapshot inputs at the first send | `PaymentOutbox#fromAccount` | written in the transaction that commits before the call |
| ➕ Poison task / retry loop | `onPaySucceeded` | a result handler never throws for missing data; it ends the task and alerts |
| ➕ Expand-only schema change | `ddl-auto=update`, ADR-21 | nullable columns; old rows handled explicitly; rollback-safe |
| ➕ Alert fatigue | `holdUnknownWithoutAccount` | alert once, then WARN; the runbook is in the comment |

Scenario anchors:
- "Our retries reused the idempotency key but rebuilt the request each time. If the buyer's bank account changed between attempts, the bank saw a conflict, we read it as a decline, and the user could pay again with a new key. I made the first send record the accounts, so every retry is byte-for-byte the same request."
- "The review found that recording the accounts created a retry loop when the order row was gone, because the success handler threw. Result handlers now always close the task."

---

## 6. Follow-up chain

1. **Why not just look the account up again on retry?** → It can change or disappear, and bank compares content: a different request under the same key is a conflict, not a duplicate.
2. **Why is "PAY_UNKNOWN?" the wrong check?** → During the fast retries the payment is still PENDING although the key has been sent.
3. **What if the process dies after recording the accounts but before calling bank?** → The next round sends the same request. Safe.
4. **What about tasks written before the change?** → They have no record. If the account is gone they wait as PAY_UNKNOWN with an alert; if it was changed they can still conflict, which is why `KEY_CONFLICT` should become "unknown".
5. **Two store replicas?** → Both could record accounts for the same task; today store runs one replica (ADR-17). Lock the task row or add `@Version` first.

---

## 7. How to say it

- "Same key, same request: anything a retry would read again, write down at the first send."
- "FAILED means the bank never got it or said no. Anything else is unknown."
- "A result handler must leave the task somewhere it can exit."

---

## 8. Further reading

- Stripe, *Idempotent requests* (a key reused with different parameters is an error): <https://docs.stripe.com/api/idempotent_requests>
- IETF HTTPAPI, *The Idempotency-Key HTTP Header Field* (draft): <https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/>

---

## 9. Self-check

1. The first charge timed out and actually succeeded. Before this change, how did a cleared bank account turn into a second charge? Name each step.
<details><summary>Answer</summary>
Slow retry → `startPayAttempt` reads no account → FAILED → the page shows Pay Now → `payNow` sees FAILED → attempt 2, new key → bank charges the new key as a new transfer.
</details>

2. Why is it enough to record the accounts and not the amount?
<details><summary>Answer</summary>
`Payment.amount` is written once in `createPayment` and never changed, and Pay Now does not change it. The accounts were the only inputs read live.
</details>

3. (Only answerable if you really understand it) An old task (no recorded accounts) was sent from account A; the buyer's account is now B. Follow the retry through bank and store, and say what change in store would stop the double charge.
<details><summary>Answer</summary>
store records B and sends key K with B → bank finds K used with A → `KEY_CONFLICT`, success = false → gRPC REJECTED → `onPayRejected` → FAILED → Pay Now charges with a new key. If store mapped `KEY_CONFLICT` to unknown (a new outcome in `bank.proto`), the payment would stay PAY_UNKNOWN with an alert instead of FAILED.
</details>

---

## 10. Replay time

**30 minutes**: read §1 and §2 (10 min) → `git checkout 17aeade`, run `./gradlew :storeService:test --tests '*PayRetryAccountIntegrationTest'` and see two keys in the first test (5 min) → `git checkout 3c46b22`, run it green (5 min) → self-check (10 min).
