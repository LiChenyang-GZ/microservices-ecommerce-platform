# ADR-34 · outbox 卡住了要计数、要告警，不能无限重复

[English](34-stuck-outbox-alerts.md) | **中文**

**状态：** 已采纳。修订 ADR-06（发不出去的那一行仍然挡住后面的行，但现在会告警）。

## 背景
有两处会永远卡住，日志里只有一遍遍重复的行（4b 审查 #7、#8）：

- **`OutboxProcessor` 的非钱任务**（还库存的老 `FAILED` 任务、退款成功邮件 `REFUNDED`）。做事和重试计数在同一个事务里。`unholdProduct` 失败时把这个事务标成只能回滚，`retryCount + 1` 也跟着回滚：任务永远到不了 `maxRetries`，一直 PENDING，每 5 秒打一条 ERROR，库存永远不还。没有 ALERT。
- **`EventOutboxPublisher`**（store 和 delivery）。一行永远发不出去（超过 Kafka 消息上限、topic 被删），每一轮都停在它这里，之后所有订单的事件都在它后面等着。每轮只有一行 WARN。

读 #7 的代码时发现了审查没提到的事：从 `7b654a5`（2025-12-09）起就没有代码再写 `FAILED` 任务了。bank 拒绝之后，订单留在 PENDING_PAYMENT 等 Pay Now，15 分钟后由 `UnpaidOrderCanceller` 取消、还库存。这条路形状一样：还库存每次都失败（数据坏了）时，整个取消回滚，每分钟一条 ERROR，库存一直占着，没有 ALERT。只修 outbox，就漏掉了新订单真正走的那条路。

## 决定（Christy 定：两条都选 A）

- **`OutboxProcessor`**：非钱任务在一个事务里做（`runOtherTask`）。失败时（抛异常、放弃、提交时发现被里面标成只能回滚），`recordFailure` 在**另一个事务**（`REQUIRES_NEW`）里给重试次数 +1。到 `maxRetries`（3）标 FAILED，打一次 `ALERT outbox_task_failed taskId= type= orderId= retries= lastError=`；老 `FAILED` 任务再加一句"库存没还"。处理方法出错就抛，不再自己吞掉。`recordFailure` 不碰已经不是 PENDING 的任务（做事其实提交了、只是回答丢了，或者别的实例已经做完；审查 88）。
- **`UnpaidOrderCanceller`**：在内存里按订单数连续取消失败的次数，第 5 次（`unpaid-cancel.stuck-after-failures`）打一次 `ALERT unpaid_cancel_stuck orderId= failures= lastError=`。取消照样每分钟重试（订单、库存都不动：不能半取消）。取消成功或订单离开 PENDING_PAYMENT 就清掉计数。不改表。
- **两个服务的 `EventOutboxPublisher`**：同一行连续失败 `event-outbox.publisher.stuck-after-failures`（10）轮，或者失败时离它的 `created_at` 已经过了 `…stuck-after-minutes`（5）分钟：打一次 `ALERT event_outbox_stuck id= eventType= orderId= failures= waitingSeconds= error=`，写最里层的原因。事件又动起来时（有一行发出去了，或者卡住的那一行不在最前面了：别的实例发掉了、有人删了），打一行 INFO `event_outbox_unstuck`。顺序照旧：这一行仍然挡住后面的。
- 错误被里面 catch 掉、提交时才变成回滚时，`lastError` 写明这一点（"rolled back by an error caught inside … see the log lines before this"），不写 Spring 的 "Transaction silently rolled back"。
- 没选：只修 outbox（方案 B：漏掉 `UnpaidOrderCanceller`）；删掉 `processPaymentFailed`、直接关掉老 `FAILED` 任务（方案 C：没查过每个环境的数据之前不做）。

## 后果
- 这三处都不会再卡住却没有 ALERT；确定性的失败要么以 FAILED 结束（outbox），要么一边重试一边打一次 ALERT（取消、事件）。
- 计数放在内存里（取消、事件）：重启后重新数，可能再打一次。每个实例各打各的，滚动更新时同一行可能有两条 ALERT；每个实例也各打自己的 `unstuck`。
- 非钱任务大约有 15 秒（3 轮、每轮 5 秒、没有退避）就会 FAILED，暂时性错误也会用掉这几次。它只是邮件或老任务，ALERT 让人来补。
- "等了 5 分钟"从 `created_at` 算：停机或积压之后，老行偶然失败一次就会立刻告警（一轮后跟着 `unstuck`）。
- 仍然只有 WARN 的：发送之前整轮就失败（库出问题，readiness 能看到），以及"每次都发成功、提交却失败"。store 的投递线程遇到 `Error` 死掉没有兜住（记着以后做）。
- 先查过本机数据：两套 compose 库都没有老 `FAILED` 任务、没有没发的事件。

## 代码在哪
- store [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java)（`runOtherTask`、`recordFailure`）、[UnpaidOrderCanceller.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/UnpaidOrderCanceller.java)（`noteFailure`）、[EventOutboxPublisher.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java)（`noteFailure`、`noteHeadOfQueue`、`noteSent`）；delivery [EventOutboxPublisher.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java)
- 测试：store `OutboxRetryCountIntegrationTest`、`UnpaidCancelStuckIntegrationTest`、`EventOutboxStuckIntegrationTest`（真 Kafka、一行超大的事件）、`EventOutboxStuckTest`（store 和 delivery 各一份）；"数据坏了"用 Postgres 触发器造（`StoreIntegrationTest#breakStockRow`）
- [docs/learning/21-stuck-outbox-alerts.zh-CN.md](../learning/21-stuck-outbox-alerts.zh-CN.md)
