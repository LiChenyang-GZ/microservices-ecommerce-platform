# ADR-08 · Consumer failures: wait out an outage, move a bad message aside

**Status:** Accepted (refined during implementation into three cases)

## Context
A consumer can fail because something it depends on is briefly down (a retry will work) or because the message itself is wrong (a retry never will). Handling both the same way either loses events or blocks a partition for good.

## Decision
Three cases, the same in store, delivery and tracking:

| Failure | Handling |
|---|---|
| The message is broken: cannot be parsed, missing fields, or refers to an order that does not exist | No retry. Write it to `<topic>.DLT` with the reason in headers, log `ALERT`, continue with the next message |
| Known transient trouble: database down, lock timeout | Retry in place with exponential back-off for as long as it lasts. The partition waits, so order is kept |
| Anything else, most likely a bug | Retry 5 times, then `<topic>.DLT` with `ALERT` |

The first plan had only the first two cases. The third was added so that an unexpected exception neither blocks a partition forever nor drops the event silently.

Each topic has 3 partitions read by 3 consumer threads, so a waiting partition does not stop the others. Each `.DLT` topic has the same partition count, because Spring writes a dead record to the partition with the same number. The Go and Java consumers write the same DLT headers.

## Consequences
- Exceptions must be classified correctly; a wrong guess either blocks a partition or dead-letters too early.
- Orders on a waiting partition are delayed until the outage ends.

## In the code
- delivery [KafkaConsumerConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/KafkaConsumerConfig.java), store [KafkaConsumerConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/KafkaConsumerConfig.java)
- tracking [dlt.go](../../backend/trackingService/internal/consumer/dlt.go), [handler.go](../../backend/trackingService/internal/consumer/handler.go)
