# ADR-02 · Jobs go through RabbitMQ, events through Kafka

**Status:** Accepted (ADR-18 closes the gap this leaves)

## Context
Two kinds of messages exist. Some are facts ("order 12 was paid") that several services read and may want to replay. Others are work items ("move delivery 13 to the next step", "send this email") that one worker should do, with acknowledgement, redelivery, and a dead-letter queue when they keep failing.

## Decision
Facts go to Kafka (ADR-01). Work items stay on RabbitMQ:
- `delivery_processing_queue`: delivery's simulated courier. Each step is one job; after handling it, delivery puts the next job back on the queue (about 10 s later). Jobs that keep failing go to `delivery_dead_letter_queue`, which stores an alert row an admin can resolve or requeue (`/api/admin/dlq`).
- `email_queue`: store asks the email service to send a notification.

## Consequences
- Each broker does what it is built for: per-message acks and dead-lettering on RabbitMQ, an ordered, replayable log on Kafka.
- Two brokers to run.
- A job is sent right after the database commit, not through an outbox. If RabbitMQ is down at that moment the job is lost; ADR-18 adds the fallback.

## In the code
- [delivery RabbitMQConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/RabbitMQConfig.java), [DeliveryStatusUpdateService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java), [DeliveryDeadLetterService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryDeadLetterService.java)
- store [EmailAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/EmailAdapter.java) → email [EmailQueueConsumer.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailQueueConsumer.java)
