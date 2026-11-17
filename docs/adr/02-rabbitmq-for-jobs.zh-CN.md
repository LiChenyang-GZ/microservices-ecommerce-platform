# ADR-02 · 任务走 RabbitMQ，事件走 Kafka

[English](02-rabbitmq-for-jobs.md) | **中文**

**状态：** 已采纳（留下的缺口由 ADR-18 补上）

## 背景
消息有两种。一种是事实（"订单 12 已支付"），好几个服务要读，可能还要重放。另一种是要做的一件事（"把配送单 13 推进到下一步""发这封邮件"），只该由一个 worker 做一次，需要逐条确认、失败重投、一直失败就进死信队列。

## 决定
事实走 Kafka（ADR-01）。任务继续走 RabbitMQ：
- `delivery_processing_queue`：delivery 的模拟快递。每一步是一个任务；处理完，delivery 把下一步任务放回队列（约 10 秒后）。一直失败的任务进 `delivery_dead_letter_queue`，记成一条告警，管理员可以标记已处理或重新入队（`/api/admin/dlq`）。
- `email_queue`：store 让 email 服务发通知邮件。

## 代价与影响
- 两个中间件各做自己擅长的事：RabbitMQ 逐条确认和死信，Kafka 有序、可重放的日志。
- 要运维两套中间件。
- 任务是在数据库提交之后直接发的，没走 outbox。那一刻 RabbitMQ 挂着，任务就丢了；ADR-18 加了兜底。

## 代码在哪
- delivery [RabbitMQConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/RabbitMQConfig.java)、[DeliveryStatusUpdateService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java)、[DeliveryDeadLetterService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryDeadLetterService.java)
- store [EmailAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/EmailAdapter.java) → email [EmailQueueConsumer.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailQueueConsumer.java)
