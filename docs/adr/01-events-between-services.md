# ADR-01 · State changes travel between services as Kafka events

**English** | [中文](01-events-between-services.zh-CN.md)

**Status:** Accepted

## Context
In the course version, store orchestrated everything with direct HTTP calls, and delivery reported progress back through a webhook into store. Every new reader of a state change meant changing the caller, and a service that was down at the wrong moment missed the update.

## Decision
Order and delivery state changes are published as events on two Kafka topics: `order-events` (written by store) and `delivery-events` (written by delivery). A service that needs to react subscribes; the producer does not know who reads.

## Consequences
- Adding a consumer changes no existing service: tracking (ADR-11) was added this way.
- Events are kept and can be replayed; a consumer that was down continues from its committed offset.
- The system is eventually consistent, and every consumer must cope with duplicates and late messages (ADR-07).
- The delivery → store webhook was removed.

## In the code
- Topic names and the event envelope: [Topics.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/Topics.java), [EventEnvelope.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java) (ADR-19)
- store records order events: [OrderEventRecorder.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/OrderEventRecorder.java); delivery records delivery events: [DeliveryEventRecorder.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/DeliveryEventRecorder.java)
- Topics are created by `kafka-init` in [compose.yml](../../backend/compose.yml): 3 partitions each, plus a `.DLT` topic for each
