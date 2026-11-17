# ADR-18 · A sweeper re-sends delivery jobs that stopped moving

**English** | [中文](18-stuck-delivery-sweeper.zh-CN.md)

**Status:** Accepted (closes the gap left by ADR-02)

## Context
Found by scenario 05 (integration finding I1). delivery creates a delivery row and its `DeliveryCreated` event in one transaction, then sends the first courier job to RabbitMQ after the commit. If RabbitMQ is down at that moment, the send fails after the commit; Kafka redelivers `OrderPaid`, but delivery sees the row already exists and skips it. The delivery then stays CREATED forever: the customer has paid, nothing ships, and nobody is told. The same can happen after every later step.

Options considered: (A) a sweeper that re-sends jobs for deliveries that have not moved; (B) jobs through an outbox like events; (C) leave it. B only covers "could not send", not "RabbitMQ accepted and then lost it", and would change every step.

## Decision
Option A. `StuckDeliveryJobSweeper` runs every 10 s and finds non-final deliveries whose `update_time` is older than 60 s. For each one it first **claims** the row with a conditional update (`UPDATE … SET update_time = now WHERE id = ? AND update_time = <value read>`; only the instance that changes one row continues), then re-sends the job. A failed send logs `ALERT delivery_job_resend_failed` and ends the round. The sending right after commit stays as the fast path; the sweeper is the slow path.

## Consequences
- One mechanism covers every step and also covers jobs RabbitMQ lost after accepting them.
- A stuck delivery moves again at most about 70 s after RabbitMQ is back.
- Duplicate jobs are possible; the delivery's `@Version` drops the second update.
- On a cloud deployment, a workflow engine (for example AWS Step Functions) could replace the courier loop on RabbitMQ altogether.

## In the code
- [StuckDeliveryJobSweeper.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java); settings `delivery.stuck-job.*`
- Test `StuckDeliveryJobIntegrationTest`; scenario `05-rabbitmq-down-at-delivery.ps1`
- [docs/learning/09-stuck-delivery-job-sweeper.md](../learning/09-stuck-delivery-job-sweeper.md)
