# ADR-18 · 扫表补发：重发卡住不动的配送任务

[English](18-stuck-delivery-sweeper.md) | **中文**

**状态：** 已采纳（补上 ADR-02 留下的缺口）

## 背景
场景 05 发现的（集成发现 I1）。delivery 在一个事务里建配送单、写 `DeliveryCreated` 事件，提交之后再把第一个快递任务发给 RabbitMQ。那一刻 RabbitMQ 挂着，提交后发送失败；Kafka 重投 `OrderPaid`，但 delivery 看到配送单已经存在就跳过了。于是这张配送单永远停在 CREATED：用户付了钱，货不动，也没人知道。之后的每一步都可能这样。

考虑过的做法：(A) 扫表，重发不动的配送单的任务；(B) 任务也像事件一样走 outbox；(C) 不修。B 只管"发不出去"，管不了"RabbitMQ 收了又丢了"，而且每一步都要改。

## 决定
选 A（Christy 2026-10-21 选的）。`StuckDeliveryJobSweeper` 每 10 秒扫一次，找出非终态、`update_time` 超过 60 秒没动的配送单。对每一张先**认领**：`UPDATE … SET update_time = now WHERE id = ? AND update_time = 读到的值`，改到 1 行的实例才继续，然后重发任务。发失败就打 `ALERT delivery_job_resend_failed`，这一轮停下。提交后直接发送仍然是快路径，扫表是慢路径。

## 代价与影响
- 一套机制管住每一步，连"RabbitMQ 收了又丢了"也管住了。
- RabbitMQ 恢复后，卡住的配送单最多约 70 秒就会重新动起来。
- 可能出现重复任务，配送单的 `@Version` 会丢掉第二次更新。
- 上云时可以用工作流引擎（比如 AWS Step Functions）整个替掉 RabbitMQ 里自己转圈的模拟快递。

## 代码在哪
- [StuckDeliveryJobSweeper.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java)；配置 `delivery.stuck-job.*`
- 测试 `StuckDeliveryJobIntegrationTest`；场景 `05-rabbitmq-down-at-delivery.ps1`
- [docs/learning/09-stuck-delivery-job-sweeper.zh-CN.md](../learning/09-stuck-delivery-job-sweeper.zh-CN.md)
