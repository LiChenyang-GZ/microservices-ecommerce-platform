# ADR-03 · Events and money tasks are written through an outbox

**Status:** Accepted

## Context
A database commit and a Kafka send cannot be one atomic step. Commit first and crash before sending, and the event is lost; send first and the commit may fail, and consumers act on something that never happened. Calling bank has the same problem: a crash between "order changed" and "bank called" leaves the two disagreeing.

## Decision
A service writes what must happen next as a row, in the same transaction as the business change, and a separate worker carries it out:
- `event_outbox` (store and delivery): events waiting to be published, sent by an `EventOutboxPublisher` (ADR-06).
- `payment_outbox` (store): PAY and REFUND tasks waiting to be sent to bank, run by `OutboxProcessor` (ADR-04, ADR-05).

Events and tasks are in separate tables because they are handled differently: events are sent once in order; tasks have their own retry rules and states.

## Consequences
- "The order changed" and "the event or task exists" are always true together.
- Sending is at least once: a crash after sending but before marking the row sent sends it again. Consumers de-duplicate (ADR-07).
- Events are delayed by the publisher's polling interval (1 s by default).
- If Kafka is down, orders are still paid; events wait in the table and go out when Kafka is back (scenario 04).

## In the code
- store [EventOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/EventOutbox.java), [PaymentOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentOutbox.java), [OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java)
- delivery [EventOutbox.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/model/EventOutbox.java)
- [docs/learning/06-kafka-delivery-events.md](../learning/06-kafka-delivery-events.md)
