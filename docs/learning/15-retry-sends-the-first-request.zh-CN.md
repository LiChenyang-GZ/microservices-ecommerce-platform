# 15 · 扣款重试发的是第一次的请求，不是重新拼一个

[English](15-retry-sends-the-first-request.md) | **中文**

> 提交（分支 `fix/pay-unknown-no-account`）：红 `17aeade` → 绿 `8705325`；审查之后红 `f71e8a4` → 绿 `3c46b22`。
> 怎么发现的：第一次审查的 A#4（"没有银行账号 → FAILED，不管 payment 是什么状态"）。
> 回放时间：**30 分钟**
> 先读：[01 · 幂等扣款](01-idempotent-payments.zh-CN.md)（每次重试同一个键；超时不是失败）。

---

## 1. 问题

**重试之间键没变，请求却变了。** 每次发送之前，`PaymentService#startPayAttempt` 都重新读买家的银行账号。读到空的，任务就被标 FAILED；FAILED 会显示 Pay Now，Pay Now 会换新键再扣。如果第一次其实已经扣了（回答在超时里丢了），买家就付了两次。读到的是**另一个**账号，bank 看到同一个键、不同内容，回 `KEY_CONFLICT`（success = false），store 当成"拒绝"：又是 FAILED。

修之前这些测试**真的红了**，都在 [PayRetryAccountIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PayRetryAccountIntegrationTest.java)：
- PAY_UNKNOWN、账号被清空、低频重试、点 Pay Now：bank 收到**两个键** `PAY-x-1-…` 和 `PAY-x-2-…`
- 第一次发送之后账号被清空或删掉：FAILED，低频重试和快速重试（payment 还是 PENDING）都一样
- 账号换了号：同一个键带着新号发出去
- 审查发现的：第一次发送之后订单行被删：每轮都打一次 bank，任务永远关不掉

---

## 2. 是什么（框架层面）

### 2.1 幂等键代表的是一个请求，不只是一次尝试
bank 把键和它带来的请求一起存下，下一次来了就比对（[BankService#previousTransferResult](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)）。同一个键、同一个请求 → 返回第一次的结果。同一个键、不同请求 → 冲突，因为那可能是别人的请求。Stripe 也是这么做的。所以"同一个键重试"只有在重试是**同一个请求**（同样的账号、同样的金额）时才安全。

### 2.2 重试时重新读的东西，都可能在中间变掉
金额没问题（在 `Payment` 上只写一次）。账号是现读的。修法是在键第一次用的那一刻把账号记下：`payment_outbox.from_account` / `to_account`，和 `retryCount` 加 1 在同一个事务里，调 bank 之前提交。之后重试什么会变的东西都不读。

### 2.3 该问"发过没有"，不是"payment 是什么状态"
`retryCount` 是在调用之前数的，0 就是 bank 从没收到过这个键。只有这时标 FAILED 才安全。原来的代码什么都没查；改成查 PAY_UNKNOWN 也会漏掉快速重试——那时 payment 还是 PENDING，键却已经发出去了。

### 2.4 老数据没有记录：停住、告警、写明人怎么处理
这两列出现之前写的、已经发过的任务，账号没了就没法安全重发（第一次用的是哪个账号？）。它们变成 PAY_UNKNOWN，打一次 `ALERT payment_no_account`，之后每 10 分钟一条 WARN。`PaymentService#holdUnknownWithoutAccount` 的注释写了人工处理：从 bank 那个键的交易里把账号抄过来。

### 2.5 结果处理里抛异常，就是一个重试死循环
账号记下以后，订单行没了的任务照样会发出去；`onPaySucceeded` 找不到订单就抛异常，事务回滚，任务还是 PENDING，下一轮再发。结果处理必须让任务落到一个能离开的状态：这里是 SUCCESS 加 `ALERT payment_no_order`，因为 bank 说钱动了。

---

## 3. 请求路径

1. 下单 → PAY 任务，`retryCount = 0`，账号是空的。
2. `OutboxProcessor#runPayTask` → `startPayAttempt`：读买家账号和 `bank.store.account`，都记下，`retryCount = 1`，提交 → 用键 K 调 bank。
3. 超时 → 快速重试：每次 `startPayAttempt` → 账号已记 → 同一个 K、同样的账号。
4. 5 次超时 → `PAY_UNKNOWN`，10 分钟后再看 → 还是同一个请求 → bank 回答 → `onPaySucceeded`（或 `onPayRejected`）。
5. PENDING / PAY_UNKNOWN 时 Pay Now 什么都不做；只有 FAILED 才开新任务，新任务记它自己的账号。

---

## 4. 为什么这样做

### 4.1 记下账号（Christy 定的：方案 C）
方案 A（发过的任务读不到账号时不标 FAILED）改动更小，但"换了号"没修。方案 B（记下账号）让每次重试都是同一个请求；C 是 B 加上 A 的规则，给这两列出现之前写的任务用。

### 4.2 从没发过、读不到账号，照旧 FAILED 🤖
不可能扣过钱，保留 FAILED 能让买家把账号弄好以后重来。

### 4.3 审查发现了什么
没有必须修的。建议修：订单行没了的死循环（§2.5，修了）。可以修：告警每 10 分钟刷一次（修了：只打一次，之后 WARN）；老任务分支里 PENDING 那一半没有测试（补了，并确认删掉那个分支时它会变红）；**老任务换了号仍然可能扣两次**——写明了，通用的修法是 store 不能把 `KEY_CONFLICT` 当成"拒绝"。

---

## 5. 这里的面试点

**结论**：同一个键必须带同一个请求，所以重试时本来要重新读的东西，第一次发送时就记下来。

| 考点 | 在哪 | 这里的选择 |
|---|---|---|
| ➕ 幂等键 = 键 + 请求指纹 | `BankService#sameTransfer` | 用过的键带来不同请求就是冲突 |
| ➕ 未知不是失败；"发过"是分界线 | `startPayAttempt`（`retryCount`） | 只有 bank 从没收到这个键才 FAILED |
| ➕ 第一次发送时给输入拍快照 | `PaymentOutbox#fromAccount` | 在调用之前提交的那个事务里写 |
| ➕ 毒任务 / 重试死循环 | `onPaySucceeded` | 结果处理不因为缺数据抛异常；关任务、告警 |
| ➕ 只加不改的表结构变更 | `ddl-auto=update`、ADR-21 | 可空列；老数据单独处理；可以回滚 |
| ➕ 告警疲劳 | `holdUnknownWithoutAccount` | 只告警一次，之后 WARN；处理步骤写在注释里 |

场景锚点：
- "我们的重试复用了幂等键，但每次都重新拼请求。如果买家的银行账号在两次之间变了，bank 看到冲突，我们当成拒绝，用户就能用新键再付一次。我让第一次发送记下账号，之后每次重试都是一模一样的请求。"
- "审查发现，记下账号以后，订单行没了会变成重试死循环，因为成功处理抛了异常。现在结果处理一定会把任务关掉。"

---

## 6. 追问链

1. **为什么不在重试时再查一次账号？** → 它可能变、可能没了，而 bank 比对内容：用过的键带来不同请求是冲突，不是重复。
2. **为什么查 PAY_UNKNOWN 不对？** → 快速重试期间 payment 还是 PENDING，但键已经发出去了。
3. **记下账号之后、调 bank 之前进程挂了怎么办？** → 下一轮发同一个请求。安全。
4. **改动之前写的任务呢？** → 没有记录。账号没了就以 PAY_UNKNOWN 等着并告警；换了号还是会冲突，所以 `KEY_CONFLICT` 应该改成"未知"。
5. **store 两个副本呢？** → 两个都可能给同一条任务记账号；现在 store 只跑一个副本（ADR-17）。要先锁任务行或加 `@Version`。

---

## 7. 怎么说

- "同一个键，同一个请求：重试时要重新读的东西，第一次发送时就记下来。"
- "FAILED 的意思是 bank 没收到或者说了不。其他都是未知。"
- "结果处理必须让任务落到一个能离开的状态。"

---

## 8. 延伸阅读

- Stripe，*Idempotent requests*（同一个键带不同参数会报错）：<https://docs.stripe.com/api/idempotent_requests>
- IETF HTTPAPI，*The Idempotency-Key HTTP Header Field*（草案）：<https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/>

---

## 9. 自测

1. 第一次扣款超时，其实成功了。改动之前，一个被清空的银行账号是怎么变成第二次扣款的？说出每一步。
<details><summary>答案</summary>
低频重试 → `startPayAttempt` 读不到账号 → FAILED → 页面显示 Pay Now → `payNow` 看到 FAILED → 第 2 次、新键 → bank 把新键当成新的一笔扣款。
</details>

2. 为什么只记账号、不记金额就够了？
<details><summary>答案</summary>
`Payment.amount` 只在 `createPayment` 写一次，之后不改，Pay Now 也不改。账号是唯一现读的输入。
</details>

3. （真懂才答得出）一条老任务（没记账号）第一次是从账号 A 发的；现在买家的账号是 B。顺着重试走一遍 bank 和 store，说出 store 改哪里能挡住第二次扣款。
<details><summary>答案</summary>
store 记下 B，用 B 发键 K → bank 发现 K 用 A 用过 → `KEY_CONFLICT`、success = false → gRPC REJECTED → `onPayRejected` → FAILED → Pay Now 用新键扣款。如果 store 把 `KEY_CONFLICT` 映射成"未知"（`bank.proto` 加一个 outcome），payment 会停在 PAY_UNKNOWN 并告警，而不是 FAILED。
</details>

---

## 10. 回放时间

**30 分钟**：读 §1、§2（10 分钟）→ `git checkout 17aeade`，跑 `./gradlew :storeService:test --tests '*PayRetryAccountIntegrationTest'`，看第一个测试里出现两个键（5 分钟）→ `git checkout 3c46b22`，跑绿（5 分钟）→ 自测（10 分钟）。
