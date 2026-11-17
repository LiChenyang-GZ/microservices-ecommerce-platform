# ADR-07 · 按订单保序，消费者自己去重

[English](07-order-by-order-dedupe-in-consumers.md) | **中文**

**状态：** 已采纳

## 背景
Kafka 只保证同一分区内有序，而且至少投递一次；outbox（ADR-03）也是至少发一次。所以消费者一定会看到重复消息，两个 topic 之间也没有顺序。

## 决定
- 所有事件用 `orderId` 做分区 key，同一订单的事件在各自的 topic 里有序。
- 每个事件有唯一的 `eventId`。
- 消费者先提交自己的数据库事务，再提交 offset。
- 各消费者用自己的方式去重：
  - delivery：每个订单一行（`order_id` 唯一，`INSERT … ON CONFLICT`）；重复的 `OrderPaid` 找到这一行就什么都不做。
  - tracking：`INSERT … ON CONFLICT (event_id) DO NOTHING`。
  - store：状态不倒退。配送状态排名 CREATED < PICKED_UP < DELIVERING < 终态（RECEIVED / LOST / CANCELLED）；名次不高于订单当前状态的事件直接跳过，重复的那一份永远不会比第一份"高"。
- tracking 按 `occurredAt`（事件发生时间）排时间线，不按到达顺序，因为两个 topic 之间没有顺序。

## 代价与影响
- 重复和迟到的事件不会把数据改错。
- 每个新消费者都要设计自己的去重方式。

## 代码在哪
- store [DeliveryEventService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java)（`rank`）、[DeliveryEventsConsumer.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/DeliveryEventsConsumer.java)
- delivery [DeliveryService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java)、[DeliverySchemaUpgrade.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java)（唯一索引）
- tracking [store.go](../../backend/trackingService/internal/store/store.go)
