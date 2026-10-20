# 06 · delivery-events on Kafka: delivery status no longer travels by webhook

**English** | [中文](06-kafka-delivery-events.zh-CN.md)

> Commits: `d1d5638 test(red)` → `f3483f0 feat` (wave 2 A · 4a, branch `claude/wave2-kafka-656c13`)
> Replay time: **60 minutes** (many concepts: outbox publishing, advisory lock, consumer groups, two kinds of failure + DLT)
> Before this: [00 · Event contract](00-event-contract.md) (envelope, `EventCodec`), [01 · Idempotent payments](01-idempotent-payments.md) (payment_outbox, lock order), [03 · Go tracking consumer](03-go-tracking-consumer.md) (consumer groups, offsets).
> Failure-scenario table: Obsidian `开发/电商项目/17-第2波A-Kafka-失败场景表.md` (groups P, A, D, E).

---

## 1. Problem

**Delivery reported status changes to store with a webhook (an HTTP callback queued on `notification_queue`). That path was unreliable, unordered, and could lose money.**

- Changing the status and sending the notification were not one transaction: the worker commits PICKED_UP, crashes before queueing the notification → store never finds out.
- Notifications for one order went through RabbitMQ and HTTP retries with **no ordering**, and store did not refuse to go backwards: a late "in transit" could undo "delivered".
- A user cancelling on the delivery page: the webhook only set the order to CANCELLED — **no refund, no stock back**.
- Reproducing tests (red `d1d5638`, 32 new tests, 31 red):
  - delivery publishes nothing to Kafka: [DeliveryEventPublishingIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryEventPublishingIntegrationTest.java)
  - store does not read Kafka: [DeliveryEventsConsumerIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsConsumerIntegrationTest.java)
  - bad messages / transient trouble: [DeliveryEventsFailureIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsFailureIntegrationTest.java), [DeliveryEventsErrorKindsIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsErrorKindsIntegrationTest.java)
  - old schema, new consumer group: [FreshEnvironmentIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/FreshEnvironmentIntegrationTest.java)

---

## 2. What it is (framework level)

### 2.1 Delivery: transactional outbox
**Plain words**: in the same transaction that changes the status, also insert a row into a "events to send" table; a scheduled publisher later sends those rows to Kafka. If the database committed, the event is in the table and cannot be lost.

- Table: [EventOutbox.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/model/EventOutbox.java). `payload` holds the whole envelope JSON (made by the contract's `EventCodec` at write time); `sent_at` null = not sent yet.
- Writing the event: [DeliveryEventRecorder.java:33](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/DeliveryEventRecorder.java#L33)
  ```java
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordStatus(Delivery delivery) { ... outboxRepository.save(row); }
  ```
  `MANDATORY` = a transaction **must** already exist, otherwise it throws. Nobody can "forget the transaction" and let the event commit on its own.
- Callers: create [DeliveryService.java:59](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L59), cancel `:116` `:147` `:250`, each worker step [DeliveryStatusUpdateService.java:114](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L114).
- If a worker step collides with a cancel (`@Version` conflict), the whole transaction rolls back **and so does that step's event** — no "picked up" goes out for a cancelled delivery (test `cancelDuringWorkerStep_onlyCancelledIsPublished`).

### 2.2 Publisher + transaction-level advisory lock (`pg_try_advisory_xact_lock`)
- Code: [EventOutboxPublisher.java:65](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java#L65)
  ```java
  Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
  if (!Boolean.TRUE.equals(locked)) return;          // another instance is publishing this round
  for (EventOutbox row : outboxRepository.findTop100BySentAtIsNullOrderByIdAsc()) {
      kafkaTemplate.send(row.getTopic(), row.getOrderId(), row.getPayload()).get(...);  // wait for Kafka's ack
      row.setSentAt(Instant.now());
  }
  ```
- **Advisory lock**: Postgres lets you lock "a number" rather than a row — here the number 5348. `try` = return false at once instead of waiting.
- **Transaction-level** (`_xact_`): released on commit, rollback, or a dropped connection. A session-level lock would survive the connection going back to the pool, and other instances could never get it.
- `JdbcTemplate` and JPA share the same connection inside one transaction (`JpaTransactionManager` exposes it to JDBC), so the lock and "mark sent" are in the same transaction.
- One send fails: stop at that row, commit the ones already acked (Christy's default for question 6). The next round starts from the failed row, so order is kept.
- Sent but not marked (crash): sent again next round with the same `eventId`. That is **at-least-once**; consumers must de-duplicate (2.5).

### 2.3 Producer settings ([application.properties](../../backend/deliveryService/src/main/resources/application.properties))
| Setting | Why |
|---|---|
| `acks=all` + `enable.idempotence=true` | success only after the broker has it; the producer's own network retries never write a second copy |
| `max.block.ms=5000`, `request.timeout.ms=5000`, `delivery.timeout.ms=10000` | with Kafka down, a send fails within 10 s. The default is 2 minutes, with the publisher holding its lock and transaction open |
| key = `orderId` | rule 5: one order, one partition, ordered within it |

### 2.4 Store: `@KafkaListener` and consumer groups
- Listener: [DeliveryEventsConsumer.java:39](../../backend/storeService/src/main/java/comp5348/storeservice/events/DeliveryEventsConsumer.java#L39). It only checks the message (parses, type matches the payload status, orderId is a number); otherwise `InvalidEventException`.
- Settings: [application.properties:37](../../backend/storeService/src/main/resources/application.properties#L37)
  - `group-id=store` (contract). Consumers in one group **share** the partitions; different groups each read everything (tracking is its own group).
  - `auto-offset-reset=earliest`: a group seen for the first time reads from the oldest message. Kafka's default `latest` **skips** events published before the group existed (test `newConsumerGroup_readsEventsPublishedBeforeItStarted`).
  - `ack-mode=record`: commit each record's offset once its handler (and database transaction) is done. Rule 4: database first, then offset.
  - `concurrency=3`: three consumers, one partition each. One stuck partition does not stop the other two (review C medium 1, test `stuckPartition_doesNotBlockOtherPartitions`).

### 2.5 Status never goes backwards = store's de-duplication
- Code: [DeliveryEventService.java:73](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L73)
  ```java
  if (rank(event) <= current) { logger.info("Skipping ..."); return; }
  ```
- Ranks: PAID(0) < PICKED_UP(1) < IN_TRANSIT(2) < final DELIVERED / LOST / CANCELLED / REFUNDED(3). An unpaid order is -1: a delivery event for it raises an ALERT and changes nothing (question 8).
- One rule covers two cases: a **duplicate** is never "above" itself, and a **late** old status is never above the newer one.
- One transaction: `SET LOCAL lock_timeout = '5s'` → lock the order row (`findByIdForUpdate`) → change status → for Lost, write a REFUND task ([:90](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L90)). The consumer **never calls bank** (rule 1, enforced by the architecture test).
- Cancelled arriving while the order is still PAID → `OrderService.finishCancel` (stock back, CANCELLED, REFUND task), the same code a user cancel runs.
- Emails go out in `afterCommit` ([:112](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L112)): a rolled-back, retried transaction does not send a second one.

### 2.6 Two kinds of failure + DLT: `DefaultErrorHandler`
- Code: [KafkaConsumerConfig.java:61](../../backend/storeService/src/main/java/comp5348/storeservice/events/KafkaConsumerConfig.java#L61). Spring Boot hands this bean to every `@KafkaListener` container.
- When the listener throws, `DefaultErrorHandler` decides: retry (seek the partition back to this record and read it again after a pause) or give it to the **recoverer** (here: write to the DLT).

| Exception | Handling | Code |
|---|---|---|
| `InvalidEventException`, `UnknownOrderException` (the message itself is bad) | no retry, straight to the DLT | `addNotRetryableExceptions` `:72` |
| known transient (`TransientDataAccessException`, cannot connect, lock timeout, …) | exponential back-off up to 2 s, **retry forever** | `setBackOffFunction` `:71`, `setMaxInterval` `:78` |
| anything else (most likely a bug) | retry every 200 ms, 5 times, then the DLT (Christy's choice 1b) | `new FixedBackOff(200L, 5)` `:70` |

- **DLT name**: `DeadLetterPublishingRecoverer` writes to `<topic>-dlt` by default (per the Spring Kafka docs). The contract says `<topic>.DLT`, so a destination resolver sets it explicitly: `Topics.deadLetter(record.topic())`, same partition number.
- If writing the DLT itself fails (e.g. the DLT topic is missing), the recoverer throws and the record is **not committed** — it comes back, it is not silently lost (test `dltPublishFails_recordIsNotSkipped`).
- Before writing the DLT it logs `ALERT kafka_dead_letter ...`. DLT records carry Spring's headers: original topic and exception message.

### 2.7 Fixing the old CHECK constraint at startup
- Code: [OrderStatusCheckUpgrade.java:43](../../backend/storeService/src/main/java/comp5348/storeservice/config/OrderStatusCheckUpgrade.java#L43)
- Hibernate 6 writes `CHECK (status IN (...))` for an `@Enumerated(STRING)` column when it first creates the table; `ddl-auto=update` **never changes it** later. The compose database's `orders_status_check` has no LOST / REFUNDED, so writing them fails. Test databases are new every time and cannot show this — hence a test that builds the old table first (`FreshEnvironmentIntegrationTest`).
- `@DependsOn("entityManagerFactory")` + `@PostConstruct`: runs after Hibernate has built the tables and before the Kafka listeners start (they start only once every bean exists).
- Both statements go in one `execute`; Postgres runs them as one implicit transaction, so there is no moment without a constraint.

---

## 3. Request path (one order from pick-up to delivered)

```
delivery · RabbitMQ worker  DeliveryStatusUpdateService.processDelivery          (:114)
  tx: delivery CREATED → PICKED_UP + one event_outbox row PickedUp ── commit
delivery · EventOutboxPublisher.publishPending (every second)                    (EventOutboxPublisher.java:65)
  tx: pg_try_advisory_xact_lock(5348) → read unsent → send(delivery-events, key=orderId) → await ack → sent_at
Kafka · delivery-events, partition = hash(orderId) % 3
store · DeliveryEventsConsumer.onDeliveryEvent (3 threads, group store)          (DeliveryEventsConsumer.java:39)
  EventCodec.parse → type / status match → orderId is a number
  → DeliveryEventService.apply @Transactional                                  (DeliveryEventService.java:58)
      SET LOCAL lock_timeout = '5s'; SELECT … FOR UPDATE on the order row
      rank(PickedUp)=1 > rank(PAID)=0 → order PICKED_UP
  ← commit → afterCommit email → commit offset (ack-mode=record)
  on exception → DefaultErrorHandler: bad message → delivery-events.DLT; transient → back off and retry; bug → DLT after 5
```

---

## 4. Why this way

| Option | Why not / why |
|---|---|
| Keep the webhook, add retries and ordering | rebuilds reliability by hand (that was the old notification_queue + dead letters), still unordered |
| `kafkaTemplate.send` right after changing the status | not atomic: crash in between and the event is lost; send then roll back and a false event goes out (test `stateChange_andEvent_commitTogetherOrNotAtAll`) |
| Kafka transactions / Debezium CDC | proper but heavy: CDC is another system to run; Kafka transactions cannot include Postgres |
| 🤖 outbox table + advisory-lock publisher (chosen, design ADR-03/06) | only uses the Postgres we already have; costs a polling delay (1 s) and at-least-once |
| 🤖 `concurrency=3` | design rule 6 says "block **this** partition"; Spring's default one thread for three partitions blocks all three on one retry |
| 🤖 other exceptions: 5 retries then DLT (question 1b) | one bug must not block a third of all orders forever; cost: a later event of the same order may be handled first, and DLT records need a human replay |
| 🤖 idempotent SQL at startup for the constraint (question 3a) | no Flyway; cost: every new status relies on this code to stay in sync |

🤖 Other choices the AI made: lock number 5348; 100 rows per round, one round per second; 5 s lock wait; transient back-off from 200 ms up to 2 s; 200 ms between bug retries; which exceptions count as transient (the `KafkaConsumerConfig.TRANSIENT` list).

Also changed (outside the table, reported):
- Delivery's two `cancelDelivery` methods used to allow PICKED_UP; now only CREATED, matching the design's cancel table. Otherwise store would "release stock" for goods already picked up.
- Deleted delivery's `NotificationService`, `NotificationProcessingService`, `DeadLetterQueueService`, two notification DTOs, `notification_queue` / `dead_letter_queue`; store's `DeliveryWebhookController`, `handleDeliveryUpdate`; compose's `DELIVERY_NOTIFICATION_URL`.

---

## 5. Interview topics here

**Verdict: this step had real work — replace HTTP callbacks with outbox + Kafka, and classify consumer failures. Nothing heavier is needed: no Kafka transactions, no CDC, no separate lock service.**

| Topic | Where | The call made here |
|---|---|---|
| 📒 F4 Outbox | `DeliveryEventRecorder.java:33`, `EventOutboxPublisher.java:65` | status and event in one transaction; one publisher at a time via an advisory lock. **No** SKIP LOCKED parallel publishing: one order's events must stay in order, and one publisher is fast enough |
| 📒 F6 Kafka vs RabbitMQ | delivery-events on Kafka, delivery jobs stay on RabbitMQ | facts many services subscribe to and may replay → Kafka; a job that needs per-message ack → RabbitMQ |
| 📒 F8 Webhook | the deleted `NotificationService` | webhooks suit notifying third parties; between our own services, reliable and ordered means a message system |
| 📒 F9 Event-driven | store subscribes; delivery does not know who reads | adding tracking changed nothing in delivery; cost: eventual consistency + consumer de-dup |
| 📒 F3 Idempotency | `DeliveryEventService.java:73` | no "processed eventIds" table: each event means "entered a status", so status-never-backwards is enough |
| 📒 F2 Resilience | `KafkaConsumerConfig.java:70` | back off on transient trouble, isolate bad messages (DLT); **no** circuit breaker: a consumer already just waits |
| 📒 D4 @Transactional | `MANDATORY`, `afterCommit` | propagation as a guard rail; email after commit so a rollback does not send it |
| 📒 A4 checked exception | `InvalidEventException` | the contract made it checked on purpose: the listener signature says "this may be a bad message" |
| 📒 E6 schema changes | `OrderStatusCheckUpgrade.java:43` | `ddl-auto=update` adds columns but never changes constraints; idempotent SQL now, Flyway if the schema keeps changing |
| 📒 B6 Deadlock | `PaymentService.onRefundSucceeded` locks the order, then the payment | new code keeps the "order before payment" order |
| 📒 G4 Testcontainers | real Kafka + real Postgres | partitions, offsets, consumer groups, `lock_timeout` only behave for real |
| ➕ transaction-level advisory lock | `pg_try_advisory_xact_lock` | locks a number, not a row; released when the transaction ends |
| ➕ `lock_timeout` | `DeliveryEventService.java:60` | a lock wait becomes a retryable error instead of hanging on a connection |
| ➕ `auto.offset.reset` | `earliest` | a new group reads from the start; the default latest drops earlier events |
| ➕ DLT naming / partition | `KafkaConsumerConfig.java:61` | Spring's default is `-dlt` and the same partition → a DLT needs at least as many partitions |
| ➕ test context caching / stubbing race | `@DirtiesContext` and `latest` on `DeliveryEventsErrorKindsIntegrationTest` | cached contexts keep their consumers alive and steal messages; never stub a spy that background threads are calling |

Scenario anchors:
- "Delivery used to call store back over a webhook. If the call was lost, store never knew the parcel moved. Now the status change and its event commit in one transaction, and a publisher sends the event to Kafka."
- "On the store side, status never goes backwards. That one rule handles duplicates and late events."
- "A bad message goes straight to a dead-letter topic. A database hiccup is retried in place. A bug is retried five times, then dead-lettered, so it can't block a partition forever."

---

## 6. Follow-up chain

1. Why not send to Kafka right after changing the status? → dual write is not atomic. How does the outbox avoid loss? → same transaction. How does it get out? → a scheduled publisher.
2. Two instances publishing at once? → transaction-level advisory lock. Why not session-level? → the pool keeps the connection, the lock stays. Why not `SELECT … FOR UPDATE SKIP LOCKED`? → it parallelises, but two instances could send one order's events out of order.
3. Sent, then crashed before marking? → sent again, at-least-once. How does the consumer de-dup? → status never backwards. And tracking? → unique eventId.
4. How are failures split? → bad message / transient / bug. Why not retry bugs forever? → it would block the partition; the cost is ordering. Why name the DLT explicitly? → default `-dlt`, auto-create is off, a wrong name cannot be written at all.
5. Can one stuck partition stall all orders? → with Spring's default yes; `concurrency=3` gives each partition its own thread. A locked row? → `lock_timeout` 5 s, then a transient back-off retry.

---

## 7. How to say it

- "Each delivery status change writes an event row in the same transaction. A scheduled publisher takes a transaction-level advisory lock, sends the rows in order, and marks them sent."
- "Store consumes delivery-events with three threads, one per partition. It commits the database first, then the offset, and skips any event that would move the order backwards."
- "A lost parcel only writes a refund task. The outbox processor is still the only thing that calls the bank."

---

## 8. References

- Transactional outbox: [microservices.io · Pattern: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- Advisory locks (session vs transaction level): [PostgreSQL · 13.3.5 Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- `lock_timeout`: [PostgreSQL · Client Connection Defaults · lock_timeout](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-LOCK-TIMEOUT)
- `DefaultErrorHandler`, `setBackOffFunction`, default DLT name `-dlt`, a failed recoverer means redelivery: [Spring for Apache Kafka · Handling Exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- `concurrency`, `AckMode.RECORD`: [Spring for Apache Kafka · Message Listener Containers](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- `auto.offset.reset` (default latest): [Apache Kafka 3.9 · Consumer Configs](https://kafka.apache.org/39/configuration/consumer-configs/)
- `enable.idempotence`: [Apache Kafka 3.9 · Producer Configs](https://kafka.apache.org/39/configuration/producer-configs/)
- Doing work after commit (`@TransactionalEventListener` is the declarative form of `afterCommit`): [Spring Framework · Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)

---

## 9. Self-check

1. The publisher sent rows 1–3 and marked them, then Kafka went down on row 4. After this round, which rows have `sent_at`? Where does the next round start?
<details><summary>Answer</summary>1–3 have it (the round's transaction still commits); 4 and later are null. The next round starts at 4, so one order's order is kept. Rule 3's wording "roll back the round" would also work — same outcome, 1–3 would just be sent again.</details>

2. Why does store not need a "processed eventIds" table to de-duplicate? When would that not be enough?
<details><summary>Answer</summary>Every delivery event means "entered a status"; the same status again is never above the current one, so it is skipped. Not enough when an event is not a state change ("add one item", "add points"): processing it twice adds twice — then you need an eventId table or unique constraint (as tracking does).</details>

3. `codeBug_retriedFewTimes_thenDlt` passed alone but failed with the other tests. Why, and how was it fixed?
<details><summary>Answer</summary>Spring caches test contexts. Earlier test classes' contexts were still alive and their consumers were also in group "store", so they took some partitions. The "bug" event went to another context (whose repository was not the spy and did not throw), so no retries or DLT appeared. Fix: this class uses its own consumer group and <code>@DirtiesContext</code>, so its context closes afterwards and does not steal later tests' messages.<br>Then a second trap: the new group started at <code>earliest</code> and replayed dozens of old events; Mockito stubbing of a spy is <b>not thread-safe</b>, and while the test thread was stubbing, a consumer thread called the same spy, so the stub landed on another order. Second fix: this group starts at <code>latest</code>, and the test waits until all 3 consumers have partitions before publishing.</details>

---

## 10. Replay time

**60 minutes**: check out `d1d5638` and run a few red classes (10 min) → read the diff and section 2 (20 min) → check out the feat commit, run green (10 min) → cover the "call made" column in section 5 and say it yourself (10 min) → say section 7 aloud, do the self-check (10 min).
