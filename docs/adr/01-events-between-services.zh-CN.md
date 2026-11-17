# ADR-01 · 服务之间用 Kafka 事件传递状态变化

[English](01-events-between-services.md) | **中文**

**状态：** 已采纳

## 背景
课程版本里，store 用 HTTP 直接调用来编排所有事情，delivery 通过 webhook 把进度回调给 store。每多一个需要知道状态变化的服务，就要改调用方；某个服务刚好挂着的时候，这次更新就丢了。

## 决定
订单和配送的状态变化作为事件发到两个 Kafka topic：`order-events`（store 写）和 `delivery-events`（delivery 写）。需要的服务自己订阅，生产者不知道谁在读。

## 代价与影响
- 新增消费者不用改任何已有服务：tracking（ADR-11）就是这样加进来的。
- 事件会保留、可以重放；消费者挂了以后从上次提交的 offset 接着读。
- 系统变成最终一致，每个消费者都要能处理重复和迟到的消息（ADR-07）。
- delivery → store 的 webhook 删掉了。

## 代码在哪
- topic 名和事件信封：[Topics.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/Topics.java)、[EventEnvelope.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java)（ADR-19）
- store 记录订单事件：[OrderEventRecorder.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/OrderEventRecorder.java)；delivery 记录配送事件：[DeliveryEventRecorder.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/DeliveryEventRecorder.java)
- topic 由 [compose.yml](../../backend/compose.yml) 里的 `kafka-init` 创建：每个 3 个分区，各自再配一个 `.DLT`
