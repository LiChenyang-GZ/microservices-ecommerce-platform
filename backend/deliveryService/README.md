# deliveryService

**English** | [中文](README.zh-CN.md)

Simulated shipping. A RabbitMQ job moves each delivery one step at a time
(CREATED → PICKED_UP → DELIVERING → RECEIVED, or LOST); every status change is
published to the Kafka topic `delivery-events`.

## Events (`delivery-events`)

- Each status change writes a row into `event_outbox` in the **same transaction**
  (`service/events/DeliveryEventRecorder`). Event names and payloads come from
  `backend/contracts` (`DeliveryEventType`, `DeliveryEventPayload`).
- `service/events/EventOutboxPublisher` sends unsent rows every second, in id order,
  key = orderId. Each round takes `pg_try_advisory_xact_lock(5348)` first, so only one
  instance publishes at a time.
- Delivery is at-least-once: a row sent but not marked (crash) is sent again with the
  same `eventId`. Consumers de-duplicate.
- Design and reasoning: [docs/learning/06-kafka-delivery-events.md](../../docs/learning/06-kafka-delivery-events.md).

## Settings

| Key | Default | |
|---|---|---|
| `spring.kafka.bootstrap-servers` | `localhost:9094` | compose sets `SPRING_KAFKA_BOOTSTRAP_SERVERS=kafka:9092` |
| `event-outbox.publisher.interval-ms` | `1000` | pause between publishing rounds |
| `delivery.simulation.step-delay-ms` | `10000` | wait per delivery step |
| `delivery.simulation.loss-rate` | `0.0` | chance a step loses the parcel (`1.0` = always) |

## Kafka was wiped (`docker compose down`)

Kafka in compose has no volume, so `down` throws the topics away. The events are still
in `event_outbox`. To send them again (consumers ignore what they already applied):

```sql
-- in delivery_db
UPDATE event_outbox SET sent_at = NULL;
```
