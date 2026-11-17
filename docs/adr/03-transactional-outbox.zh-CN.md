# ADR-03 · 事件和资金任务都先写进 outbox

[English](03-transactional-outbox.md) | **中文**

**状态：** 已采纳

## 背景
数据库提交和 Kafka 发送没法放在一个原子操作里。先提交、发之前崩了，事件就丢了；先发、提交又失败了，消费者就按一件没发生的事去处理。调 bank 也一样：在"订单改了"和"调了 bank"之间崩溃，两边就对不上。

## 决定
服务把"接下来要做的事"作为一行，和业务改动写在**同一个事务**里，再由单独的 worker 去执行：
- `event_outbox`（store 和 delivery）：待发布的事件，由 `EventOutboxPublisher` 发（ADR-06）。
- `payment_outbox`（store）：待发给 bank 的 PAY / REFUND 任务，由 `OutboxProcessor` 执行（ADR-04、ADR-05）。

事件和任务分两张表，因为处理方式不同：事件按顺序发一次；任务有自己的重试规则和状态。

## 代价与影响
- "订单改了"和"事件 / 任务存在"永远同时成立。
- 至少发一次：发完、标记已发之前崩溃，会再发一次。消费者负责去重（ADR-07）。
- 事件有轮询间隔带来的延迟（默认 1 秒）。
- Kafka 挂着也能正常付款，事件在表里等，Kafka 回来再发（场景 04）。

## 代码在哪
- store [EventOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/EventOutbox.java)、[PaymentOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentOutbox.java)、[OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java)
- delivery [EventOutbox.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/model/EventOutbox.java)
- [docs/learning/06-kafka-delivery-events.zh-CN.md](../learning/06-kafka-delivery-events.zh-CN.md)
