# ADR-05 · 超时不等于失败

[English](05-timeout-is-not-failure.md) | **中文**

**状态：** 已采纳

## 背景
调 bank 超时的时候，bank 可能已经把钱转了。如果标成 FAILED，再让用户用新键付一次，就扣了两次。

## 决定
- 没拿到回答的调用，用**同一个键**马上重试，最多连续 5 次（`payment.bank.fast-retries`，间隔 500 毫秒）。
- 还是没有回答：付款标成 `PAY_UNKNOWN`（退款是 `REFUND_UNKNOWN`），打一行 `ALERT` 日志，之后每 10 分钟（`payment.bank.slow-retry-minutes`）再试一次，直到 bank 给出确定答复。
- 只有 bank 明确说"不行"（比如余额不足）才算失败。
- 付款是 `PAY_UNKNOWN` 时，Pay Now 不会开始新的一次。

## 代价与影响
- 多了几个状态：`PAY_UNKNOWN`、`REFUND_PENDING`、`REFUND_UNKNOWN`。
- 结果未知需要告警，真实的店里还需要人去看。
- 扣款结果未知时订单被取消、之后扣款又成功了：结果处理时看到订单已取消，会自己写一条退款任务。

## 代码在哪
- [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java)（`runPayTask`、`runRefundTask`）、[PaymentStatus.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentStatus.java)
- 测试：`PaymentTimeoutIntegrationTest`、`PayNowIntegrationTest`；场景 `03-bank-down-during-payment.ps1`
