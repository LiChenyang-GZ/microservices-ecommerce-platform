# 21 · outbox 卡住了要告警

[English](21-stuck-outbox-alerts.md) | **中文**

> 提交（分支 `fix/outbox-stuck-alerts`）：红 `cbb8daf` → 绿 `719e9c3`；审查后红 `925139a` → 绿 `bcd2677`。
> 怎么发现的：第 2 波 Kafka 审查（Obsidian 26 号 #7、#8），2026-11-27 分级时列为第 1 档。
> 回放时间：**20 分钟**
> 先读：[01 · 幂等扣款](01-idempotent-payments.zh-CN.md)、[07 · Kafka 订单事件](07-kafka-order-events.zh-CN.md)。

---

## 1. 问题

**有三个循环会永远失败下去，只有日志：没有上限，没有 ALERT。**
- `OutboxProcessor` 把任务的重试次数和做事写在同一个事务里。还库存失败时事务回滚，计数也跟着回滚：重试次数永远是 0，每 5 秒一条 ERROR，库存一直占着。
- `UnpaidOrderCanceller` 也是这个形状，而现在付款失败的库存正是从这里还回去的（从 2025-12-09 起就没有代码再写 outbox 的 `FAILED` 任务）：每次都失败的取消每分钟回滚一次，只有 ERROR。
- `event_outbox` 里一条 Kafka 永远不收的事件（太大）挡住后面所有事件，store 和 delivery 都是，每秒一行 WARN。

修之前，证明出错的测试**真的跑出了错**（红灯）。"数据坏了"用 Postgres 触发器造：让改某一行库存的 UPDATE 报错（`StoreIntegrationTest#breakStockRow`）；卡住的事件是真 Kafka 上一行 1.2 MB 的数据：
- [OutboxRetryCountIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/OutboxRetryCountIntegrationTest.java)：失败一轮后 `retryCount` 还是 0；3 轮后没有 ALERT
- [UnpaidCancelStuckIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/UnpaidCancelStuckIntegrationTest.java)：取消失败 7 次，没有 ALERT
- [EventOutboxStuckIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/EventOutboxStuckIntegrationTest.java)：超大的行挡住了下一单的 `OrderPlaced`；276 行 WARN，没有 ALERT
- `EventOutboxStuckTest`（store、delivery 各一份，假 Kafka）：失败 10 轮、30 轮都没有 ALERT；审查后：卡住的行被别的实例发掉时，没有 `unstuck`

---

## 2. 是什么（框架层面）

### 2.1 回滚会带走同一个事务里的所有东西
Spring 会把嵌套的 `@Transactional` 调用合进同一个事务。里面的调用抛异常，就把整个事务标成只能回滚；catch 住异常也撤不掉这个标记，提交时就是 `UnexpectedRollbackException`。在这个事务里写的一切，包括"又失败了一次"，都没了。**失败要写在另一个事务里**（`REQUIRES_NEW`，或者等第一个事务结束以后）。

### 2.2 每个重试循环都要有上限和告警
暂时性错误再试一次就好了，确定性错误不会。分不清这两种的循环，至少要在 N 次以后停下来或者喊人。每几秒重复一次的日志不是告警：没人看。

### 2.3 队头阻塞是保序的代价
按 id 顺序发事件、遇到第一个失败就停，保住了每个订单的顺序（ADR-06）。代价是：一行坏的挡住后面所有行。顺序要保住，但要量出"挡住了"（同一行连续失败几轮、队头等了多久），然后告警。

### 2.4 多个实例时的内存状态
内存里的计数是每个进程一份。两个实例时，各数各的、各打各的 ALERT；"恢复了"也要每个实例自己发现：这里靠的是看到卡住的那一行已经不在最前面了，不管是谁发出去的。

---

## 3. 路径

1. `OutboxProcessor#processOutbox` → 非钱任务 → `runOtherTask`：在 `transactionTemplate` 里做事。做完 → PROCESSED。
2. 抛异常、放弃、或提交时失败 → `recordFailure` 在 `REQUIRES_NEW` 事务里：还是 PENDING？+1；到 3 次 → FAILED + `ALERT outbox_task_failed`。
3. `UnpaidOrderCanceller`（每分钟）→ `cancelIfStillUnpaid` 失败 → `noteFailure`：连续第 5 次 → `ALERT unpaid_cancel_stuck`。取消成功或订单离开 PENDING_PAYMENT 就清掉。
4. `EventOutboxPublisher#publishBatch`：拿锁 → 取一批 → `noteHeadOfQueue`（卡住的行已经不在最前面 → unstuck）→ 逐行发；失败 → `noteFailure`（同一行 +1；到 10 轮或已等 5 分钟 → `ALERT event_outbox_stuck`）；成功 → `noteSent`。

---

## 4. 为什么这样做

### 4.1 两条都选 A（Christy 定的）
#7：outbox 的计数要修，**15 分钟超时取消也要告警**，因为新订单走的是那条路。#8：两个服务的投递程序一起改，代码一样、后果一样。

### 4.2 红灯里看到的 🤖
新测试都红在该红的那一步。改绿时发现两件事：ALERT 打出了 `error={}`（最后一个参数是 `Throwable` 时，SLF4J 把它当异常栈，不填占位符；现在用最里层原因的 `toString()`）；全量跑时 stuck 的 ALERT 打了三次（缓存了好几个 Spring 上下文，各有一个投递程序；每个实例打一次，可以接受）。

### 4.3 审查发现了什么
0 必须修、0 建议修、7 可以修（Obsidian 88 号）。
- **修了**（红 `925139a` → 绿 `bcd2677`）：打过 ALERT 的实例，即使那一行是别的实例发掉的，也会打 `unstuck`；`recordFailure` 不碰已经不是 PENDING 的任务（不会有假的 FAILED / ALERT）；"里面吞掉、提交时才回滚"的错误在 `lastError` 里写明。
- **补了测试**：R5b 钉住原来 #7 的机制（错误在里面被 catch、提交时回滚）；R2 / R7 检查 ALERT 那一行自己的 `lastError`。
- **写明**：非钱任务大约 15 秒（3 轮）就会 FAILED；"等了 5 分钟"从 `created_at` 算，停机后老行偶然失败一次就会告警。

---

## 5. 这里的面试题

**结论**：失败要计在失败的那个事务之外；每个重试循环都要有上限和告警。

| 题目 | 在哪 | 这里怎么做的 |
|---|---|---|
| ➕ 事务传播、只能回滚 | `OutboxProcessor#runOtherTask` / `recordFailure` | 计数写在 `REQUIRES_NEW` 里，不写在做事的事务里 |
| ➕ 重试上限和告警 | `recordFailure`、`UnpaidOrderCanceller#noteFailure` | 3 次 → FAILED + ALERT；取消失败 5 次 → ALERT，继续重试 |
| ➕ 队头阻塞 | `EventOutboxPublisher#noteFailure` | 顺序保住，10 轮或 5 分钟后告警 |
| ➕ 跨实例的状态 | `noteHeadOfQueue` | "恢复了"由每个实例自己发现，不只是发出去的那个 |

场景句：
- "一个清理任务的重试计数和做事写在同一个事务里。做事失败时回滚把计数也撤了，于是它每 5 秒重试一次、永远停不下来。我把计数挪到单独的事务里，到上限打告警。"
- "我们的事件 outbox 为了保序，停在第一行失败的地方。一条超大的事件就能让所有事件停住、只有 WARN。我保留了顺序，但同一行失败 10 轮或等了 5 分钟就告警。"

---

## 6. 追问链

1. **为什么不直接 catch 住异常？** → catch 撤不掉只能回滚的标记；提交照样失败，计数照样被带走。
2. **为什么不跳过卡住的那一行？** → 后面的行可能是同一订单之后的事件，跳过就破坏了每个订单内的顺序。由人来决定（修好或删掉）。
3. **为什么放在内存，不加一列？** → 卡住时库里什么都没变；重启后再打一次 ALERT 可以接受；不用改表。
4. **两个 pod 就两条 ALERT？** → 滚动更新时是的；各自也会打自己的恢复。

---

## 7. 怎么说

- "别把失败记在失败的那个事务里。"
- "没有上限的重试循环，就是一次会打日志的故障。"
- "顺序要保住，但要知道它卡住了。"

---

## 8. 延伸阅读

- Spring Framework 参考文档，事务传播（`REQUIRED`、`REQUIRES_NEW`、rollback-only）：<https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/tx-propagation.html>
- Chris Richardson，Transactional outbox 模式：<https://microservices.io/patterns/data/transactional-outbox.html>

---

## 9. 自测

1. 重试次数为什么一直不涨？
<details><summary>答案</summary>
它和做事写在同一个事务里。还库存失败把这个事务标成只能回滚，提交时计数也跟着回滚了。
</details>

2. 为什么只修 `OutboxProcessor`，修不好"付款失败后库存不还"？
<details><summary>答案</summary>
现在已经没有代码写 outbox 的 `FAILED` 任务了；付款失败的库存是 `UnpaidOrderCanceller` 还的，它也是"失败就无限重试"的形状。
</details>

3. （真懂才答得出）两个实例时，为什么一条 ALERT 可能永远等不到配对的 `unstuck`？怎么修的？
<details><summary>答案</summary>
只有打过 ALERT 的那个实例会打 `unstuck`，而以前它只在自己发出一行时才打。如果下一行被另一个实例发掉了，打过 ALERT 的那个就没东西可发了。现在每个实例看到卡住的那一行不在最前面了，就自己打。
</details>

---

## 10. 回放时间

**20 分钟**：读 §1、§2（7 分钟）→ `git checkout cbb8daf`，跑 `./gradlew :storeService:test --tests '*OutboxRetryCountIntegrationTest' --tests '*EventOutboxStuckTest'` 看红灯（6 分钟）→ `git checkout 719e9c3` 跑绿（4 分钟）→ 自测（3 分钟）。
