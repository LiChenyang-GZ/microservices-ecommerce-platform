# ADR-07 · Events are ordered per order, and consumers de-duplicate

**Status:** Accepted

## Context
Kafka keeps order only within a partition and delivers at least once, and the outbox (ADR-03) also sends at least once. Consumers will see duplicates, and the two topics have no order between them.

## Decision
- Every event uses `orderId` as its partition key, so the events of one order stay in order within their topic.
- Every event has a unique `eventId`.
- Consumers commit their offset only after their database transaction has committed.
- Each consumer de-duplicates in its own way:
  - delivery: one row per order (unique `order_id`, `INSERT … ON CONFLICT`); a repeated `OrderPaid` finds the row and does nothing.
  - tracking: `INSERT … ON CONFLICT (event_id) DO NOTHING`.
  - store: status never goes backwards. Delivery states rank CREATED < PICKED_UP < DELIVERING < final (RECEIVED / LOST / CANCELLED); an event that is not above the order's current rank is skipped, and a duplicate is never above its first copy.
- tracking sorts the timeline by `occurredAt` rather than by arrival, because the two topics have no order between them.

## Consequences
- Duplicates and late events cannot corrupt state.
- Each new consumer has to choose its own de-duplication rule.

## In the code
- store [DeliveryEventService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java) (`rank`), [DeliveryEventsConsumer.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/DeliveryEventsConsumer.java)
- delivery [DeliveryService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java), [DeliverySchemaUpgrade.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java) (unique index)
- tracking [store.go](../../backend/trackingService/internal/store/store.go)
