# ADR-16 · 幂等键带随机部分，bank 比对请求内容

[English](16-unguessable-idempotency-keys.md) | **中文**

**状态：** 已采纳（修订 ADR-04）

## 背景
最初的键是 `PAY-{paymentId}-{attempt}` 和 `REFUND-{paymentId}`，bank 只按键去重。审查发现这两点合在一起是个漏洞：键可以预测（paymentId 在界面上看得到），而 bank 对重复的键直接返回"第一次的结果"，不看内容。攻击者可以用受害者的下一个键，给自己转 0.01；store 真正扣款时拿到"已经成功"，订单变成 PAID，用户却一分钱没付。

## 决定
- store 给每个键加 16 位随机十六进制（`SecureRandom` 的 64 位）：`PAY-{paymentId}-{attempt}-{随机}`、`REFUND-{paymentId}-{随机}`。随机部分存在 payment 上，任何接口都不返回；每次新的扣款尝试生成一个新的。同一个任务的重试复用存好的键。
- bank 对重复的键比对内容（转账：类型、付款方、收款方、金额；退款：类型、原交易）。内容相同 → 返回第一次的结果。内容不同 → "键冲突"，拒绝，不动钱。

## 代价与影响
- 键没法提前猜到，一个被复用的键也不可能为另一个请求动钱。
- 日志里的键变长了。

## 代码在哪
- store [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java)（`payKey`、`refundKey`、`newKeyNonce`）
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)（`previousTransferResult`、`previousRefundResult`）
- 测试：`PaymentKeyIntegrationTest`、`BankKeyConflictIntegrationTest`；[docs/learning/01-idempotent-payments.zh-CN.md](../learning/01-idempotent-payments.zh-CN.md) §2.12
