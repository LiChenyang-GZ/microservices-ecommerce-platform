# 09 · Stuck deliveries: a sweeper that resends the job (wave 2 integration finding I1)

**English** | [中文](09-stuck-delivery-job-sweeper.zh-CN.md)

> Commits: `493f384 test(red)` → `d903f15 fix` (wave 2 final integration, branch `claude/wave2-integration`)
> How it was found: scenario [05-rabbitmq-down-at-delivery.ps1](../../backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1) stops RabbitMQ during an order; the delivery stayed in CREATED for good.
> Replay time: **30 minutes**
> Before this: [01 · Idempotent payments](01-idempotent-payments.md) (outbox), [06 · Kafka](06-kafka-delivery-events.md) (4b: OrderPaid creates the delivery, the job is sent after commit).

---

## 1. Problem

**Writing to the database and sending to RabbitMQ are two steps. If RabbitMQ is down in between, nothing sends the job again, and the delivery is never shipped.**

- delivery reads OrderPaid from Kafka, creates the delivery and commits, then sends the "start simulating" job to RabbitMQ in `afterCommit` ([DeliveryService.java:96](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L96)).
- RabbitMQ is down → the send throws → Kafka redelivers OrderPaid → delivery sees the row already exists and skips it as a repeat → **nothing ever sends the job**. The customer paid, the parcel never moves, and no alert fires.
- The same gap a second time: after each step the worker commits, then puts the job back on the queue ([DeliveryStatusUpdateService.java:123](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L123)). RabbitMQ down at that moment leaves the delivery in PICKED_UP or DELIVERING.
- Tests (red `493f384`): [StuckDeliveryJobIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/StuckDeliveryJobIntegrationTest.java)
  - `jobLostWhenCreated_isSentAgain_afterRabbitComesBack` (line 60): RabbitMQ down when the delivery is created
  - `jobLostBetweenSteps_isSentAgain_afterRabbitComesBack` (line 78): RabbitMQ down after a step
  - `finishedDelivery_isNeverSentAgain` (line 99): guard, a finished delivery is never resent

---

## 2. What it is (framework level)

### 2.1 Why a database transaction cannot cover RabbitMQ
`@Transactional` is a Postgres transaction: writes on one database connection commit or roll back together. RabbitMQ is another process. Postgres cannot roll it back, and it cannot wait for Postgres to commit. So "change data + send a message" can only be ordered:
- **Send, then commit**: the message is out, the commit fails → the consumer gets an id that does not exist. Even if the commit succeeds, the consumer can be faster than the commit, find no row and drop the job (wave 1 B12).
- **Commit, then send** (the current code, the B12 fix): the commit succeeds, the send fails → the job is lost (this is I1).

DeliveryCreated, in the same method, does not have this problem: it is not sent to Kafka directly but written to the `event_outbox` table, in the same transaction as the delivery, and a publisher sends it later. **The outbox turns "send a message" into "write a row", so it can join the transaction.**

### 2.2 `TransactionSynchronization.afterCommit`
A callback registered inside a transaction; Spring calls it **after the commit succeeds**. It runs outside the transaction: an exception here cannot roll back committed data, it goes up to the caller (here the Kafka listener). The annotation form is `@TransactionalEventListener`, which runs in the `AFTER_COMMIT` phase by default.

### 2.3 The sweeper: `StuckDeliveryJobSweeper`
[StuckDeliveryJobSweeper.java:54](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java#L54), every 10 seconds:
1. Find deliveries that are not finished (not RECEIVED / LOST / CANCELLED) and whose `update_time` is more than 60 seconds old (line 58).
2. **Claim first** (line 64): `UPDATE delivery SET update_time = now WHERE id = ? AND update_time = <the value read>`. Only one updated row counts as a claim. This is a **conditional update** (compare-and-set):
   - two delivery instances that find the same row: only one can claim it;
   - a row whose send fails already has `update_time = now`, so it is tried again after another 60 seconds, not every round.
3. Then send the job (line 69). On failure, log `ALERT delivery_job_resend_failed` (line 74) and stop the round: with RabbitMQ down each send blocks for about 20 seconds (connection timeout), so there is no point blocking once per row.

Normally the job is still sent right away in `afterCommit` (the fast path). The sweeper only matters when a job was lost (the slow path, at most about 70 seconds late).

### 2.4 `@Scheduled` and the scheduler pool
`@Scheduled(fixedDelayString = "${delivery.stuck-job.check-interval-ms:10000}")`: run again 10 seconds after the previous run ends. Spring Boot's default scheduler has **one thread**, shared by every `@Scheduled` method. delivery also runs the Kafka event publisher every second. A sweeper send that blocks for 20 seconds while RabbitMQ is down would hold the Kafka events up for 20 seconds. So [application.properties:43](../../backend/deliveryService/src/main/resources/application.properties#L43) sets `spring.task.scheduling.pool.size=2`.

### 2.5 The `@ConditionalOnProperty` switch
`@ConditionalOnProperty(name = "delivery.stuck-job.enabled", matchIfMissing = true)` (line 35): the bean is created when the key is absent (`matchIfMissing` defaults to false; here it is true) and skipped when the key is false. It exists because of a test pitfall, see §4.3.

### 2.6 Duplicate jobs
Resending is at-least-once: if a job was only slow, not lost, two can exist. The worker saves with a `@Version` optimistic lock: two jobs that read the same version, the later save conflicts and is dropped (the `ObjectOptimisticLockingFailureException` branch in [DeliveryStatusUpdateService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java)). One after the other, the delivery moves a step sooner; the states still go forward in order.

---

## 3. Request path (paying while RabbitMQ is down)

1. The customer orders → store charges over gRPC → order PAID → OrderPaid goes into store's `event_outbox` → the publisher sends it to Kafka.
2. delivery's `OrderEventsConsumer` reads OrderPaid → `DeliveryService.createDelivery` inserts the delivery (CREATED) and DeliveryCreated, **commits**.
3. `afterCommit` sends the job → RabbitMQ unreachable, throws after about 20 seconds → up to the Kafka listener → Kafka redelivers → `createDelivery` finds the row, skips.
4. RabbitMQ comes back. Within 10 seconds `StuckDeliveryJobSweeper` finds the delivery that has not moved for 60 seconds → claims it → sends the job.
5. `DeliveryStatusUpdateService.processDelivery` gets the job → waits 10 seconds → PICKED_UP plus a PickedUp event → back on the queue → … → RECEIVED.

Evidence from the running system (compose and kind): after the new image started, **delivery 11, stuck since the day before, was picked up at once** (log `Delivery 11 had not moved since 19:39:52`); scenario 05 passes on both.

---

## 4. Why this way

### 4.1 Three options (Christy chose A)
| Option | Covers | Does not cover | Cost |
|---|---|---|---|
| **A sweeper** (chosen) | lost on create, lost after a step, RabbitMQ accepted then lost it | — | up to ~70 s late; duplicate jobs possible (stopped by `@Version`) |
| B the job goes through an outbox too | the send failing | RabbitMQ losing an accepted message; a consumer dying mid-job | another table and publisher; the step loop changes too |
| C leave it | — | everything | paid but never shipped, and nobody knows |

The common industry approach is **a main path that tries not to lose anything plus a safety net that always catches up**, both together. Main path: an outbox, outbox + CDC (Debezium), or transactional messages (RocketMQ half messages with a check-back). Safety net: reconciliation or sweeping. A is the safety net; the existing `afterCommit` stays as the fast path.

### 4.2 The more thorough option: a workflow engine 🤖
"Move one step every 10 seconds" is a **multi-step flow with timers**. Today it loops by putting itself back on RabbitMQ, and every loop has a commit-then-send gap. Real systems hand this to a workflow engine (Temporal, AWS Step Functions): every step and every timer is persisted, so a crashed process or a lost message resumes where it stopped, and no sweeper is needed. **The candidate if this moves to the cloud: AWS Step Functions**, with a Wait state between steps (noted in the Obsidian backlog 09).

### 4.3 60 s, 10 s, the conditional update: chosen by AI 🤖
- **60 seconds**: six times the normal 10-second step, so a delivery that is moving is not taken for stuck. Shorter means many duplicate jobs; longer means customers wait longer.
- **A conditional update instead of `SELECT … FOR UPDATE`**: `FOR UPDATE` holds the row lock during the send, 20 seconds while RabbitMQ is down, which would also block the cancel endpoint. The conditional update commits the claim first and sends without holding a lock.
- **The test pitfall**: `DeliveryLostEventIntegrationTest` changes the loss rate with `@TestPropertySource`, so Spring builds a separate application context for it and **keeps it cached and running**, `@Scheduled` included: a second delivery instance on the same test database. It claimed another test's delivery and sent the job to its own fake RabbitMQ. Hence the §2.5 switch, turned off in that test. It also showed that with two instances sweeping, the conditional update lets only one claim a row.

---

## 5. Interview topics here

**Conclusion**: nothing here was optimised for its own sake. What the step really uses: the outbox follow-up (why not one transaction), an optimistic lock against duplicates, and the scheduler pool.

| Topic | Where | The call made here |
|---|---|---|
| 📒 F4 Outbox (follow-up: why not one transaction) | [DeliveryService.java:96](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L96) | A Postgres transaction cannot cover RabbitMQ; either order has a gap. Instead of an outbox for the job, a safety-net sweep (§4.1). **Not needed** for store's emails: losing one does not matter, send and catch |
| 📒 E3 Optimistic lock | the worker's `@Version` + `ObjectOptimisticLockingFailureException` | Resending may duplicate a job; conflicts are rare → optimistic locking fits, the later one is dropped |
| ➕ Conditional update (CAS) to claim | [StuckDeliveryJobSweeper.java:64](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java#L64) | `UPDATE … WHERE update_time = <value read>`; one updated row = claimed; no duplicates across instances and no lock held during the send. **Not needed** with one instance that does not mind duplicates |
| ➕ Spring's scheduler has one thread by default | [application.properties:43](../../backend/deliveryService/src/main/resources/application.properties#L43) | One blocking `@Scheduled` method holds up all the others → pool of 2. **Not needed** when every scheduled job is quick and never blocks |
| ➕ Reconciliation / safety-net sweep | the whole `StuckDeliveryJobSweeper` | The main path is fast, the sweep makes sure it catches up. **Not needed** for messages that may be lost |

Scenario anchors:
- "The commit and the RabbitMQ send were two steps. When RabbitMQ was down in between, the job was lost and the parcel never moved. I found it by stopping RabbitMQ in an end-to-end run."
- "I kept the fast path and added a sweeper as a safety net. It claims a stuck row with a conditional update, so two instances never resend the same delivery."

---

## 6. Follow-up chain

1. **Why did the delivery get stuck?** → Commit and send are two steps; RabbitMQ died in between and the job was lost; Kafka's redelivered OrderPaid was skipped as a repeat.
2. **Why not send inside the transaction?** → The database transaction cannot cover RabbitMQ; sending inside it delivers the message before the data is committed (B12).
3. **Why not 2PC / XA?** → RabbitMQ does not support XA; a coordinator holds locks longer and blocks every participant when it fails. The industry uses an outbox / local message table plus idempotency instead.
4. **Can the sweeper send twice? With two instances?** → The conditional update lets only one instance claim a row; a job that was only slow may be duplicated, and the worker's `@Version` stops it.
5. **What if the flow were longer, with longer waits?** → Do not loop through a queue; use a workflow engine (Temporal / AWS Step Functions) where timers and steps are persisted.

---

## 7. How to say it

- "Writing to the database and sending to RabbitMQ can't be in one transaction. One of the two orders always has a gap."
- "Our fast path sends the job after commit. A sweeper is the safety net: it finds deliveries that haven't moved for a minute and sends their job again."
- "In the cloud I'd move this multi-step flow with timers to AWS Step Functions instead of looping through a queue."

---

## 8. Further reading

- Transactional outbox pattern: <https://microservices.io/patterns/data/transactional-outbox.html>
- Spring transaction-bound events (`@TransactionalEventListener`, `AFTER_COMMIT` by default): <https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html>
- Spring Boot task scheduling (one scheduler thread by default, `spring.task.scheduling.pool.size`): <https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>
- `@ConditionalOnProperty` and `matchIfMissing`: <https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnProperty.html>
- Spring test context caching (why the LostEvent test became a second instance): <https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html>
- AWS Step Functions Wait state: <https://docs.aws.amazon.com/step-functions/latest/dg/state-wait.html>

---

## 9. Self-check

1. Move the `convertAndSend` from `afterCommit` back inside the transaction. Is I1 gone? What new problem appears?
<details><summary>Answer</summary>
Not really. A failed send now rolls the transaction back and Kafka retries; but if RabbitMQ stays down long enough, the retries run out and this OrderPaid still has no delivery, so something else has to catch it. Worse, while RabbitMQ is up you get B12: the message arrives first, the worker cannot see the uncommitted row and drops the job, and the delivery is stuck in CREATED anyway. Both orders have a gap, hence the safety net.
</details>

2. Why does the sweeper claim first and send second, instead of sending first and updating `update_time` after a successful send?
<details><summary>Answer</summary>
Send-then-update lets two instances read the same row and both send (a duplicate); and a failed send leaves update_time unchanged, so the next round (10 s later) tries again and blocks for another 20 s. Claiming first: only one instance can update the row, and a failure waits 60 s before the next try.
</details>

3. (Only answerable if you really understand it) Why was this bug missed by the wave 1 tests and the 4b tests, and only found during integration?
<details><summary>Answer</summary>
delivery's integration tests replace RabbitTemplate with a fake that does nothing (the base class says "RabbitMQ is not what this step tests"), so a send never fails; the B12 test only checks "sent once, after commit", not "and if the send fails?". Only stopping RabbitMQ on the running system took the failed-send path for the first time. Integration has to run the failure scenarios, not only the happy path.
</details>

---

## 10. Replay time

**30 minutes**: read §1 and §2.1–2.3 (10 min) → run red: `git checkout 493f384`, then `./gradlew :deliveryService:test --tests '*StuckDeliveryJobIntegrationTest'` (5 min) → back to `d903f15`, run green (5 min) → self-check (10 min). To see it live: with compose up, run `pwsh backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1`.
