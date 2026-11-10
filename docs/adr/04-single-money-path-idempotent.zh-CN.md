# ADR-04 · 动钱只有一条路，每次调用都幂等

[English](04-single-money-path-idempotent.md) | **中文**

**状态：** 已采纳（键格式由 ADR-16 修订）

## 背景
课程版本里不止一个地方调 bank，每次扣款都现编一个随机流水号，下单还会写两条 PAY 任务。超时后重试可能扣两次；退款失败了也从不重试。

## 决定
- 只有 `OutboxProcessor` 调 bank。下单、Pay Now、取消、丢件都只写 PAY 或 REFUND 任务（ADR-03）。有一个架构测试管着这条。
- 每个任务带一个幂等键，写任务时就定下来，之后每次重试都用它：`PAY-{paymentId}-{attempt}-{随机}`、`REFUND-{paymentId}-{随机}`（ADR-16）。只有扣款**确定失败**、用户再点 Pay Now 时，`attempt` 才加 1。
- bank 对键加唯一约束。同一个键、内容相同，返回第一次的结果，不动钱；同一个键、内容不同，判为冲突并拒绝。
- bank 先锁两个账户行（`SELECT … FOR UPDATE`，按账号顺序），拿到锁后再查一次键，然后才动钱。所以同一个键的两个并发请求不可能都成功。
- Pay Now 先锁 payment 行，只有上一次是 FAILED 才开始新的一次。

## 代价与影响
- 任何扣款、退款都能安全重试，也能按键追查。
- 付款是异步的：任务跑完之前，前端显示"处理中"。
- bank 用悲观行锁，不用 `@Version`：每笔付款都要改商家账户这一行，用乐观锁的话大部分并发付款都会冲突重试。

## 代码在哪
- [OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java)、[PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java)
- [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)、[Transaction.java](../../backend/bankService/src/main/java/com/comp5348/bank/model/Transaction.java)（键的唯一约束）
- [BankAccessArchitectureTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/BankAccessArchitectureTest.java)
- [docs/learning/01-idempotent-payments.zh-CN.md](../learning/01-idempotent-payments.zh-CN.md)
