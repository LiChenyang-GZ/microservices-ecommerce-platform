# ADR-06 · One outbox publisher at a time, by a transaction-scoped advisory lock

**Status:** Accepted

## Context
During a rolling update two pods of a service can run at once. If both publish `event_outbox`, events go out twice and possibly out of order.

## Decision
Each publishing round is one transaction that first runs `pg_try_advisory_xact_lock`. Only the instance holding the lock publishes; the others skip the round. A round takes up to 100 unsent rows in id order and sends them one by one, each waiting for Kafka's acknowledgement and marked sent in the same transaction. If a send fails, the round stops there and commits the rows already acknowledged; the next round starts from the failed row, so order is kept.

## Consequences
- No coordination service is needed: the lock lives in the PostgreSQL the service already uses.
- Commit, rollback or a dropped connection releases the lock, so it cannot stay held by a connection returned to the pool.
- Throughput is limited to one instance, and the lock is held while waiting for Kafka; rounds are capped at 100 rows for that reason.
- store's `OutboxProcessor` (the payment tasks) does **not** take this lock yet, which is why store runs a single replica (ADR-17).

## In the code
- store [EventOutboxPublisher.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java), delivery [EventOutboxPublisher.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java)
