# 06 · delivery-events on Kafka: delivery status no longer travels by webhook

**English** | [中文](06-kafka-delivery-events.zh-CN.md)

> Commits: `d1d5638 test(red)` → `f3483f0 feat` (wave 2 A · 4a, branch `claude/wave2-kafka-656c13`)
> **Part 2 (4b · order-events)**: `17bd8e0 test(red)` → `a9d6fc6 feat`, at the end: [Part 2](#part-2--4b-order-events-on-kafka-store-stops-creating-deliveries-over-http).
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
- Callers: create [DeliveryService.java:89](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L89), cancel `:150` `:181` `:278` (and the cancel marker `:270`, part 2), each worker step [DeliveryStatusUpdateService.java:114](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L114).
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
- Code: [DeliveryEventService.java:74](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L74)
  ```java
  if (rank(event) <= current) { logger.info("Skipping ..."); return; }
  ```
- Ranks: PAID(0) < PICKED_UP(1) < IN_TRANSIT(2) < final DELIVERED / LOST / CANCELLED / REFUNDED(3). An unpaid order is -1: a delivery event for it raises an ALERT and changes nothing (question 8).
- One rule covers two cases: a **duplicate** is never "above" itself, and a **late** old status is never above the newer one.
- One transaction: `SET LOCAL lock_timeout = '5s'` → lock the order row (`findByIdForUpdate`) → change status → for Lost, write a REFUND task ([:90](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L91)). The consumer **never calls bank** (rule 1, enforced by the architecture test).
- Cancelled arriving while the order is still PAID → `OrderService.finishCancel` (stock back, CANCELLED, REFUND task), the same code a user cancel runs.
- Emails go out in `afterCommit` ([:112](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L124)): a rolled-back, retried transaction does not send a second one.

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


---
---

# Part 2 · 4b: order-events on Kafka, store stops creating deliveries over HTTP

> Commits: `17bd8e0 test(red)` → `a9d6fc6 feat` (same branch)
> Replay time: **50 minutes** (the publisher is from part 1; new here: de-duplication by unique constraint on the consumer side, the cancel marker, the four answers of the cancel API, sending the job after commit)
> Failure-scenario table: groups B and C of table 17, plus store's copy of group P.

## 1. Problem

**After a successful charge, store called `/api/deliveries/create` over HTTP. On cancel it only asked delivery if store already knew the deliveryId. With Kafka in between, both lose money.**

- delivery restarts for 30 seconds: store's HTTP create fails 3 times → writes `DELIVERY_FAILED` → 30 seconds later "compensation" refunds and cancels. **The user's order is gone because delivery restarted.**
- Between payment and DeliveryCreated coming back, store's `deliveryId` is null. The user cancels then → the old code **does not ask delivery**, refunds → delivery reads OrderPaid afterwards and ships. **Money back, parcel shipped.**
- Cancelled before payment, and the charge lands late: nothing stops delivery from creating the delivery.
- delivery's `order_id` is not unique: the same OrderPaid twice → two deliveries.
- delivery sent the job to RabbitMQ **inside** the transaction, twice (the same queue name on two lines): a worker faster than the commit finds no row and drops the job → the delivery stays CREATED forever.
- Reproduced by (red `17bd8e0`, 42 new tests, all red):
  - store publishes no events: [OrderEventsPublishingIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/OrderEventsPublishingIntegrationTest.java)
  - store cancels without asking delivery: [StoreCancelIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/StoreCancelIntegrationTest.java)
  - delivery down means refund: [DeliveryDownIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryDownIntegrationTest.java), [DeliveryAccessArchitectureTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/DeliveryAccessArchitectureTest.java)
  - delivery does not read order-events: [OrderEventsConsumerIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/OrderEventsConsumerIntegrationTest.java)
  - the new cancel endpoint: [CancelByOrderApiIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/CancelByOrderApiIntegrationTest.java)
  - old table, new consumer group: [DeliveryFreshEnvironmentIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryFreshEnvironmentIntegrationTest.java)
  - job after commit: [DeliveryJobAfterCommitIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryJobAfterCommitIntegrationTest.java)
  - old endpoints removed: [OldDeliveryEndpointsRemovedTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/OldDeliveryEndpointsRemovedTest.java)

## 2. What it is (framework level)

### 2.1 store: the same outbox, five events
- Table and publisher copied from delivery (part 1, 2.1 and 2.2): [EventOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/EventOutbox.java), [EventOutboxPublisher.java:98](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java#L98). Difference: store's `order_id` is a number column, turned into a string for the key.
- Writing an event: [OrderEventRecorder.java:32](../../backend/storeService/src/main/java/comp5348/storeservice/events/OrderEventRecorder.java#L32), `MANDATORY` again, and it checks the payload type matches the event type.
- Five call sites, each inside the transaction that makes the change:

| Event | Where | Note |
|---|---|---|
| OrderPlaced | [OrderProductService.java:108](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java#L108) | same transaction as the order, the stock hold and the PAY task |
| OrderPaid | [PaymentService.java:271](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L271) | only on the "order not cancelled" branch; the payload carries everything delivery needs, so delivery never calls store |
| PaymentFailed | [PaymentService.java:285](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L285) | only when bank definitely said no; a timeout (PAY_UNKNOWN) is **not** one (rule 7) |
| OrderCancelled | [OrderService.java:286](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L286) | in `finishCancel`, which user cancel, the timeout and delivery's Cancelled all go through; reason `USER_REQUEST` / `PAYMENT_TIMEOUT` |
| OrderRefunded | [PaymentService.java:374](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L374) | reason worked out at [:442](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L442): order LOST → `DELIVERY_LOST`; charge after cancel → `PAID_AFTER_CANCEL`; else `ORDER_CANCELLED` |

- **Why the publisher is not `@Scheduled`**: [EventOutboxPublisher.java:67](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java#L67) implements `SmartLifecycle` and runs its own thread. Spring's default `taskScheduler` has **one thread**, shared by every `@Scheduled` method; an OutboxProcessor round calling bank can take seconds (fast retries), and events would wait behind it.
  - Found at green: store's test base replaces `taskScheduler` with a never-firing mock (so OutboxProcessor only runs when a test calls it). The copied `@Scheduled` publisher never ran in tests.
  - Tried `@Scheduled(scheduler = "my bean")` next, then realised that **registering any `TaskScheduler` bean makes Spring Boot skip its default one** (**to be confirmed**: read from `@ConditionalOnMissingBean` on Boot's `TaskSchedulingConfigurations`, not tested in this project), so in production every scheduled task would share that one thread. Hence a private thread that is not a bean.

### 2.2 delivery: de-duplicate with a unique constraint + `ON CONFLICT DO NOTHING`
- Creating: [DeliveryService.java:61](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L61)
  ```sql
  INSERT INTO delivery (...) VALUES (..., 'CREATED', ...)
  ON CONFLICT (order_id) DO NOTHING
  ```
  1 row inserted → new delivery, write DeliveryCreated; 0 rows → this order already has a row (a repeat OrderPaid, or the **cancel marker** an earlier cancel left), do nothing.
- Why not "status never goes backwards" like store: OrderPaid is not "entered a state", it is "go and do something (create a delivery)". Twice means two deliveries, so it needs the database's unique constraint, not "look first": two threads looking at once both see nothing.
- Consumer: [OrderEventsConsumer.java:44](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/OrderEventsConsumer.java#L44). OrderPaid → create; OrderCancelled → cancel (next section); other types (OrderPlaced, PaymentFailed, OrderRefunded, anything added later) are read past, no DLT.
- Consumer settings and error handling copied from store: group `delivery`, `earliest`, `ack-mode=record`, 3 threads, the three kinds of failure in [KafkaConsumerConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/KafkaConsumerConfig.java).

### 2.3 The cancel marker and the four answers of the cancel API
- Code: [DeliveryService.java:271](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L271). **Insert first, then lock**:
  ```java
  int inserted = jdbc.update("INSERT ... VALUES (0, ?, 'CANCELLED', ...) ON CONFLICT (order_id) DO NOTHING", ...);
  Delivery d = deliveryRepository.findByOrderIdForUpdate(orderId).orElseThrow();   // SELECT ... FOR UPDATE
  ```

| Case | Does | Answer |
|---|---|---|
| no row yet | the inserted row is the **cancel marker** (status CANCELLED, no address or email) + Cancelled (deliveryId null) | 200 `CANCELLED_BEFORE_CREATED` |
| CREATED | → CANCELLED + Cancelled | 200 `CANCELLED` |
| already CANCELLED | nothing | 200 `ALREADY_CANCELLED` |
| PICKED_UP or later | nothing | 409 `TOO_LATE` + current status |

- Why insert first: `SELECT ... FOR UPDATE` cannot lock a row that **does not exist yet**. A cancel and an OrderPaid arriving together would both find nothing; one inserts a marker, the other a delivery. Inserting first: the two INSERTs meet on the unique index, the second **waits for the first to commit**, then does nothing; the `FOR UPDATE` after it always sees the row. One row, CANCELLED, every time (`cancelAndOrderPaid_atTheSameTime_oneRowEndsCancelled` runs 10 orders).
- Endpoint: [InternalDeliveryController.java:35](../../backend/deliveryService/src/main/java/com/comp5348/delivery/controller/InternalDeliveryController.java#L35), under `/internal/*`, outside the user-token filter, **no authentication** (question 2, option a; on the security to-do list).
- OrderCancelled (cancelled before payment) uses the same method. If it answers TOO_LATE, the two services disagree (store thinks unpaid, the parcel is moving): `ALERT`, no change ([OrderEventsConsumer.java:49](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/OrderEventsConsumer.java#L49)).

### 2.4 store cancel: a PAID order always asks delivery first
- Code: [OrderService.java:243](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L243), [DeliveryAdapter.java:51](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java#L51)

| delivery says | store does |
|---|---|
| any of the three 200s | one transaction: order CANCELLED, stock back, REFUND task, OrderCancelled |
| 409 | refuse: "already picked up" |
| 5xx / no answer (5 s timeout) | refuse: "try later". It **does not know** whether delivery stopped, so it must not refund first |

- The condition changed from "`deliveryId != null`" to "the order is PAID". This is the heart of the 4b red (C6). **Changed again after review** (4b #1, see 2.7): decide by money. A SUCCESS payment, a known deliveryId, or status PAID / PROCESSING: any one means ask first.
- The order row stays locked while store asks delivery (at most 5 seconds): consumers for that order wait. The design did not ask to change this; noted in 09 (C8).

### 2.5 Fixing delivery's old table at startup; sending the job after commit
- [DeliverySchemaUpgrade.java:43](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java#L43): `DROP NOT NULL` (a cancel marker has no address or email) + `CREATE UNIQUE INDEX IF NOT EXISTS`. Same timing as store's `OrderStatusCheckUpgrade`. **If the unique index cannot be built, startup fails**: without it, de-duplication would quietly stop working.
- [DeliveryService.java:94](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L94): the RabbitMQ job goes out in `TransactionSynchronization.afterCommit`, once. What is left: a crash after commit and before the send leaves that delivery at CREATED (noted in 09).

### 2.6 Removed
- store: `DeliveryAdapter.createDelivery`, `CompensationService`, the create and 30-second compensation in OutboxProcessor, `DeliveryRequestDTO` / `DeliveryResponseDTO`. Leftover `SUCCESS` / `DELIVERY_FAILED` tasks in the database are closed with a WARN ([OutboxProcessor.java:249](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L249); question 4, option a: check for them by hand before upgrading).
- delivery: `/api/deliveries/create`, `/create/batch`, the old `/api/deliveries/cancel-by-order`, and their entries in the token filter's allow-list. `GET /{deliveryId}` now matches digits only; otherwise `POST /create` matched it and answered 405 instead of 404.

### 2.7 Fixes after the independent reviews (4a, 4b)
Each one: a failing test first, then the fix. Ids are from the failure-scenario table (Obsidian note 17).
| Review | Problem | Fix | Code |
|---|---|---|---|
| 4a #1 (A17) | A Cancelled after pick-up still finished the cancel and refunded (Cancelled is final, so "never go backwards" let it through) | Finish the cancel only while the order is PAID / PROCESSING and the delivery id matches; otherwise ALERT and skip | [DeliveryEventService.java:98](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L98) |
| 4a #2 (D10) | A lock timeout while returning stock was swallowed; the transaction was already aborted, the next error looked like a bug, 5 retries, then the DLT: no refund | Stop swallowing: `unholdProduct` / `recordOutTransaction` rethrow, `finishCancel` has no try/catch; the lock timeout is seen as transient and retried in place | [OrderService.java:269](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L269), [WarehouseService.java:353](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java#L353) |
| 4b #1 (C10) | The order status can be set back to "unpaid" through an old endpoint with no auth, then the cancel refunds without asking delivery | Decide by money whether to ask delivery (see 2.4) | [OrderService.java:239](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L239) |
| 4b #2 (B13) | A cancel marker arrived before payment and store dropped its Cancelled; the OrderPaid then hit the marker and the order stayed PAID forever | When OrderPaid hits a CANCELLED row, delivery publishes Cancelled again; store already handles repeats | [DeliveryService.java:77](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L77) |
| 4b #3 | The delivery id check compared the id with itself (filled in from the same event first) | Compare with the id store knew before this event; if it knew none, do not compare (one delivery per order) | [DeliveryEventService.java:70](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L70) |

## 3. Request path (payment succeeds → delivery created; cancel right after paying)

```
store · OutboxProcessor → bank charge succeeds → PaymentService.onPaySucceeded      (:271)
  tx: lock order row → order PAID, Payment SUCCESS → event_outbox gets OrderPaid ── commit
store · EventOutboxPublisher (own thread, every 1 s) → order-events, key=orderId
delivery · OrderEventsConsumer (group delivery, 3 threads)                          (OrderEventsConsumer.java:44)
  → DeliveryService.createDelivery @Transactional                                   (:60)
      INSERT … ON CONFLICT (order_id) DO NOTHING → 1 row
      event_outbox gets DeliveryCreated; afterCommit registered
  ← commit → afterCommit: RabbitMQ job (once) → offset committed
delivery · EventOutboxPublisher → delivery-events → store saves the deliveryId (part 1)

The user cancels now (store may not know the deliveryId yet):
store · OrderService.cancelOrder                                                    (:238)
  lock order row → order is PAID → POST /internal/deliveries/cancel-by-order/{id}
     delivery: INSERT marker ON CONFLICT → 0 rows (delivery exists) → FOR UPDATE → CREATED → CANCELLED + Cancelled
     ← 200 {"result":"CANCELLED"}
  finishCancel: stock back, CANCELLED, REFUND task, OrderCancelled ── commit
delivery later reads OrderCancelled → ALREADY_CANCELLED, nothing to do
store reads Cancelled → order already CANCELLED (never backwards), nothing to do
```

## 4. Why this way

| Option | Why not / why |
|---|---|
| Keep HTTP create and add a Kafka event too | Two ways to create: delivery down still triggers the refund compensation on the HTTP path; only the unique constraint stops two deliveries |
| delivery reads OrderPaid and calls store back for address and email | Brings the synchronous dependency back: store down means no deliveries. The contract makes OrderPaid carry everything |
| delivery "looks first" before creating | Two threads look at once, both see nothing → two deliveries. It has to be a database unique constraint |
| On cancel, `SELECT ... FOR UPDATE` first, then decide to insert | Cannot lock a missing row; cancel and OrderPaid each insert one |
| 🤖 INSERT ON CONFLICT first, then FOR UPDATE (chosen; the design's cancel API section) | The unique index does the queuing; cost: each ON CONFLICT uses up an identity value, so delivery ids skip (the real run showed 2, 5, 8) |
| 🤖 timeout / 5xx always refuse the cancel | Refunding without knowing could mean "money back, parcel shipped"; cost: while delivery is down a paid order cannot be cancelled |
| 🤖 the publisher has a private thread, not `@Scheduled` | default scheduler has one thread; registering a TaskScheduler bean makes Boot skip its own (to be confirmed) |
| 🤖 `GET /{deliveryId:[0-9]+}` | so the removed `POST /create` answers 404, not 405 |

🤖 Also chosen by the AI: the answer field names (`result`, `status`); the index name `ux_delivery_order_id`; PaymentFailed only on a bank rejection (not for "no bank account linked"); OrderRefunded's reason derived from the order status and the refund task's text; LOST / REFUNDED / CANCELLED_SYSTEM orders can no longer be cancelled (they could before).

## 5. Interview topics here

**Verdict: this step has real work — creating a delivery moves from a synchronous call to subscribing to an event, de-duplication from "look first" to a unique constraint, and cancel from "maybe ask" to "always ask first". No distributed transaction, no saga orchestrator.**

| Topic | Code | Judgement here |
|---|---|---|
| 📒 F3 Idempotency | `ON CONFLICT` at `DeliveryService.java:60` | For "do something" messages, a unique constraint; for "entered a state" messages (store's side), status never going backwards |
| 📒 F4 Outbox | `OrderEventRecorder.java:32` | one in store, one in delivery, same table and publisher |
| 📒 F5 Saga / compensation | the removed `CompensationService` | the old compensation turned a temporary failure into a permanent one. Now OrderPaid waits in Kafka, no compensation needed |
| 📒 F9 Event-driven | delivery subscribes to OrderPaid | store does not know who creates deliveries; cost: cancel must cover "event still on its way" (the cancel marker) |
| 📒 E3 Pessimistic lock | `findByOrderIdForUpdate` | locks existing rows; **cannot lock a missing row**, so insert first |
| 📒 E5 Indexes (unique index) | `ux_delivery_order_id` | two INSERTs on a unique index: the second waits for the first to commit; the database queues them |
| 📒 D4 @Transactional | RabbitMQ job in `afterCommit` | side effects after commit; cost: a crash after commit loses that side effect |
| 📒 E6 Schema change | `DeliverySchemaUpgrade.java:43` | second time with idempotent SQL; a third time and it is Flyway's turn |
| ➕ `INSERT ... ON CONFLICT DO NOTHING` | `DeliveryService.java:66` | returns 0/1 rows; one round trip fewer than look-then-insert, and no race |
| ➕ Spring's scheduler has one thread by default | `EventOutboxPublisher.java:67` | registering your own TaskScheduler bean removes the default (to be confirmed, see 2.1) |

### Scenarios (3)

**① F3 Idempotency: the same OrderPaid arrives twice**
- Scenario: store's publisher sends OrderPaid, restarts before marking it sent, sends it again next round. delivery gets two identical OrderPaid.
- Old code: `order_id` not unique, both `save(new Delivery(...))` → two deliveries → the worker moves both → the user gets emails for two parcels, the warehouse ships twice.
- New code: [DeliveryService.java:67](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L67)
  ```sql
  INSERT INTO delivery (...) VALUES (...) ON CONFLICT (order_id) DO NOTHING
  ```
  The second inserts 0 rows and returns.
- Interview answer: "Kafka is at-least-once, so the consumer must be idempotent. Creating a delivery is an action, so I used a unique order id with ON CONFLICT DO NOTHING; my test sends the same event three times and gets one delivery."
- When **not**: the message means "entered a state" (store receiving PickedUp): never going backwards is enough. The message means "add one" (points): you need an eventId table.

**② E3 A pessimistic lock cannot lock a missing row: cancel and OrderPaid at the same moment**
- Scenario: the user cancels right after paying. In the same millisecond store calls delivery's cancel API, delivery's consumer is handling this order's OrderPaid.
- If you `SELECT ... FOR UPDATE` first: neither side finds the row (not created yet), nothing gets locked → cancel inserts a marker, OrderPaid inserts a delivery → with a unique constraint one of them errors, without one both rows exist → the delivery ships.
- New code: [DeliveryService.java:273](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L273) `INSERT ... ON CONFLICT DO NOTHING` first, then `findByOrderIdForUpdate`. The two INSERTs meet on the unique index; the second waits for the first to commit.
- Interview answer: "FOR UPDATE only locks rows that exist. To stop both sides thinking the row is missing, insert first, then lock, and let the unique index queue them. My test releases the cancel and the OrderPaid together with a CountDownLatch; ten orders, each ends with one CANCELLED row."
- When **not**: the row always exists already (stock), plain FOR UPDATE is enough; or use SERIALIZABLE isolation, at the cost of handling retries.

**③ F5 Compensation: treating a temporary failure as permanent**
- Scenario: delivery is in a rolling update and unreachable for 30 seconds. Five users pay meanwhile.
- Old code: store's `/api/deliveries/create` fails 3 times → DELIVERY_FAILED → 30 seconds later `CompensationService` refunds and sets CANCELLED_SYSTEM → **all five orders cancelled**, and delivery was back a minute later.
- New code: store only writes OrderPaid ([PaymentService.java:271](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L271)). The event waits in Kafka; delivery reads it when it is back. `deliveryDown_orderPaidWaitsInKafka_noCompensation` moves the clock a minute: no refund, the order is still PAID.
- Interview answer: "Compensation is for things that really cannot happen, not for a service that is briefly away. With an event, delivery creates the delivery a minute later and the user never notices."
- When you **do** compensate: a permanent business failure, such as bank rejecting the charge or a product that can no longer ship.

## 6. Follow-up chain

1. How does delivery know to create a delivery? → It subscribes to OrderPaid. Why not have store call it? → delivery down would mean compensation. Why does OrderPaid carry address and email? → No call back to store.
2. OrderPaid twice? → Unique constraint + ON CONFLICT. Why not look first? → Race.
3. The user cancels right after paying, before delivery created anything? → Cancel marker. Marker and OrderPaid at once? → Insert first, then lock.
4. delivery times out on cancel? → Refuse. Why not refund first? → We do not know whether it stopped. Cost? → No cancelling while delivery is down.
5. What is wrong with sending the RabbitMQ job right after the insert? → The worker cannot see an uncommitted row. What does afterCommit leave? → A crash after commit loses the job; the delivery stays CREATED. How to cover it? → Periodically resend jobs for deliveries CREATED longer than N minutes (not done, in 09).

## 7. In English

- "When a payment succeeds, store writes an OrderPaid event in the same transaction. Delivery consumes it and creates the delivery with an insert that does nothing on a duplicate order id."
- "If the user cancels before delivery has seen the payment, delivery leaves a cancel marker row, so a late OrderPaid finds the row and stops."
- "Store always asks delivery before cancelling a paid order. If delivery doesn't answer, store refuses, because refunding first could mean refunding a parcel that still ships."

## 8. References

- `INSERT ... ON CONFLICT`: [PostgreSQL · INSERT · ON CONFLICT Clause](https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT)
- A concurrent insert on a unique index waits for the other: [PostgreSQL · Index Uniqueness Checks](https://www.postgresql.org/docs/current/index-unique-checks.html)
- `FOR UPDATE` locks the rows it reads: [PostgreSQL · SELECT · The Locking Clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- `TransactionSynchronization.afterCommit`: [Spring Framework · TransactionSynchronization (Javadoc)](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)
- The default scheduler has one thread ("uses one thread by default"): [Spring Boot · Task Execution and Scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)
- `SmartLifecycle`: [Spring Framework · Startup and Shutdown Callbacks](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor)

## 9. Self-check

1. The user cancels right after paying. When store calls the cancel API, delivery has already read OrderPaid and created the delivery. Later delivery reads store's OrderCancelled. What is the row's final status? How many Cancelled events does delivery-events have for this order?
<details><summary>Answer</summary>CANCELLED, one Cancelled. The cancel API turned CREATED into CANCELLED and wrote Cancelled; the later OrderCancelled goes through the same method, inserts 0 rows, FOR UPDATE reads CANCELLED → ALREADY_CANCELLED, no event. Orders 2 and 3 in the real run did exactly this.</details>

2. Why does store not cancel on a 5xx from delivery and wait for a Cancelled event to sort it out?
<details><summary>Answer</summary>A 5xx means we do not know what delivery did. If store cancels and refunds while delivery did not cancel, the parcel still ships: money and goods both gone. Refusing means the worst case is the user trying again. The other way round, if delivery did cancel and only the answer was lost, its Cancelled event makes store finish the cancel (part 1, A11), refund included.</details>

3. At green, store published no events at all; every test waiting for one timed out, and the log did not even show a failed send. Why?
<details><summary>Answer</summary>The publisher was copied from delivery with <code>@Scheduled</code>, but store's test base replaces <code>taskScheduler</code> with a never-firing Mockito mock (so OutboxProcessor only runs when a test calls it). It never ran in tests. Fixed by giving the publisher its own thread (SmartLifecycle). Not <code>@Scheduled(scheduler=...)</code>: registering a TaskScheduler bean should make Boot skip its default one (to be confirmed).</details>

## 10. Replay time

**50 minutes**: check out `17bd8e0` and run the red (`StoreCancelIntegrationTest` + `OrderEventsConsumerIntegrationTest`, 3 minutes) → read the diff and section 2 (20 minutes) → check out `a9d6fc6` and run green (5 minutes) → cover "new code" in the three scenarios of section 5 and say them yourself (12 minutes) → say it in English, do the self-check (10 minutes).
