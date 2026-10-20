# deliveryService

[English](README.md) | **中文**

模拟配送。RabbitMQ 作业把每张配送单一步一步往前推
（CREATED → PICKED_UP → DELIVERING → RECEIVED，或者 LOST）；每次状态变化都发到 Kafka 的 `delivery-events`。

## 事件（`delivery-events`）

- 每次状态变化在**同一个事务**里往 `event_outbox` 写一行（`service/events/DeliveryEventRecorder`）。
  事件名和 payload 来自 `backend/contracts`（`DeliveryEventType`、`DeliveryEventPayload`）。
- `service/events/EventOutboxPublisher` 每秒把没发的行按 id 顺序发出去，key = orderId。
  每一轮先拿 `pg_try_advisory_xact_lock(5348)`，同一时间只有一个实例在发。
- 至少一次：发出去了但没来得及标记（崩溃）的行，会用同一个 `eventId` 再发一次。消费者自己去重。
- 设计和理由：[docs/learning/06-kafka-delivery-events.zh-CN.md](../../docs/learning/06-kafka-delivery-events.zh-CN.md)。

## 配置

| 键 | 默认 | |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9094` | compose 里是 `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| `event-outbox.publisher.interval-ms` | `1000` | 两轮投递之间等多久 |
| `delivery.simulation.step-delay-ms` | `10000` | 每一步配送等多久 |
| `delivery.simulation.loss-rate` | `0.0` | 每一步丢件的概率（`1.0` = 一定丢） |

## Kafka 被清空了（`docker compose down`）

compose 里的 Kafka 没挂数据卷，`down` 会把 topic 全清掉。事件还在 `event_outbox` 里。
想重新发一遍（消费者会跳过已经处理过的）：

```sql
-- 在 delivery_db 里
UPDATE event_outbox SET sent_at = NULL;
```
