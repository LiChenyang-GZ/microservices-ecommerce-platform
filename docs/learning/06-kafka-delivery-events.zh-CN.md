# 06 · delivery-events 上 Kafka：配送状态不再靠 webhook

[English](06-kafka-delivery-events.md) | **中文**

> 对应 commit：`d1d5638 test(red)` → `feat`（第 2 波 A · 4a，分支 `claude/wave2-kafka-656c13`）
> 预计回放时间：**60 分钟**（概念多：outbox 投递、咨询锁、消费者组、两种失败 + DLT）
> 前置：[00 · 事件契约](00-event-contract.zh-CN.md)（外壳、`EventCodec`）、[01 · 幂等扣款与退款](01-idempotent-payments.zh-CN.md)（payment_outbox、加锁顺序）、[03 · Go tracking 消费者](03-go-tracking-consumer.zh-CN.md)（消费者组、offset）。
> 失败场景表：Obsidian `开发/电商项目/17-第2波A-Kafka-失败场景表.md`（P、A、D、E 组）。

---

## 1. 问题

**配送状态从 delivery 传回 store 靠 webhook（经 `notification_queue` 发 HTTP 回调），这条路不可靠、不保序，还会漏钱。**

- 改状态和发通知不在一个事务里：worker 提交了 PICKED_UP，还没把通知放进队列就崩了 → store 永远不知道。
- 同一订单的多次通知走 RabbitMQ 和 HTTP 重试，**不保证顺序**；store 也不拒绝倒退，"运输中"晚到就能把"已送达"改回去。
- 用户在配送页直接取消：webhook 只把订单改成 CANCELLED，**不退款、不还库存**。
- 复现测试（红灯 `d1d5638`，32 个新测试，31 个红）：
  - delivery 不往 Kafka 发：[DeliveryEventPublishingIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryEventPublishingIntegrationTest.java)
  - store 不读 Kafka：[DeliveryEventsConsumerIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsConsumerIntegrationTest.java)
  - 坏消息 / 暂时故障怎么办：[DeliveryEventsFailureIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsFailureIntegrationTest.java)、[DeliveryEventsErrorKindsIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryEventsErrorKindsIntegrationTest.java)
  - 旧库、新消费者组：[FreshEnvironmentIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/FreshEnvironmentIntegrationTest.java)

---

## 2. 是什么（框架层）

### 2.1 delivery 这边：Transactional Outbox
**大白话**：改状态的同一个事务里，顺手往一张"待发事件"表写一行；另一个定时程序再把这张表的行发到 Kafka。数据库提交了，事件就一定在表里，不会丢。

- 表：[EventOutbox.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/model/EventOutbox.java)。`payload` 存整个外壳的 JSON（写入时用契约的 `EventCodec` 生成），`sent_at` 为空 = 还没发。
- 写事件：[DeliveryEventRecorder.java:33](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/DeliveryEventRecorder.java#L33)
  ```java
  @Transactional(propagation = Propagation.MANDATORY)
  public void recordStatus(Delivery delivery) { ... outboxRepository.save(row); }
  ```
  `MANDATORY` = **必须**已经有一个事务，没有就抛异常。这样谁也不能"忘了开事务"、让事件单独提交。
- 调用它的地方：建单 [DeliveryService.java:59](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L59)、取消 `:116` `:147` `:250`、worker 每推进一步 [DeliveryStatusUpdateService.java:114](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L114)。
- worker 推进时如果撞上取消（`@Version` 冲突），整个事务回滚，**这一步的事件也一起没了**——不会发出"已揽收"而实际已取消（测试 `cancelDuringWorkerStep_onlyCancelledIsPublished`）。

### 2.2 投递程序 + 事务级咨询锁（`pg_try_advisory_xact_lock`）
- 代码：[EventOutboxPublisher.java:65](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java#L65)
  ```java
  Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
  if (!Boolean.TRUE.equals(locked)) return;          // 别的实例这一轮在发
  for (EventOutbox row : outboxRepository.findTop100BySentAtIsNullOrderByIdAsc()) {
      kafkaTemplate.send(row.getTopic(), row.getOrderId(), row.getPayload()).get(...);  // 等 Kafka 确认
      row.setSentAt(Instant.now());
  }
  ```
- **咨询锁**（advisory lock）：Postgres 提供的"按一个数字加锁"，锁的不是哪一行，而是"5348 这个号"。`try` = 拿不到立刻返回 false，不等。
- **事务级**（`_xact_`）：事务提交、回滚、连接断开都会自动释放。如果用会话级的锁，连接被连接池收回时锁还在，别的实例就永远拿不到。
- `JdbcTemplate` 和 JPA 在同一个事务里用的是同一个数据库连接（`JpaTransactionManager` 把连接暴露给 JDBC），所以锁和"标记已发送"在同一个事务里。
- 一条发送失败：停在这一行，前面已经确认的照样提交（Christy 同意的 ❓6 默认）。下一轮从失败的那行开始，顺序不乱。
- 发出去了但标记没提交（崩溃）：下一轮再发一次，同一个 `eventId`。这叫**至少一次**（at-least-once），消费者必须去重（2.5）。

### 2.3 Kafka 生产者配置（[application.properties](../../backend/deliveryService/src/main/resources/application.properties)）
| 配置 | 为什么 |
|---|---|
| `acks=all` + `enable.idempotence=true` | 等 broker 写好才算成功；生产者自己的网络重试不会写出两份 |
| `max.block.ms=5000`、`request.timeout.ms=5000`、`delivery.timeout.ms=10000` | Kafka 挂了时一次发送最多等 10 秒就失败。默认是 2 分钟，投递程序会拿着锁、开着事务干等 |
| key = `orderId` | 规则 5：同一订单进同一分区，分区内有序 |

### 2.4 store 这边：`@KafkaListener` 和消费者组
- 监听：[DeliveryEventsConsumer.java:39](../../backend/storeService/src/main/java/comp5348/storeservice/events/DeliveryEventsConsumer.java#L39)。只检查消息本身（能不能解析、类型和 payload 的 status 对不对得上、orderId 是不是数字），有问题抛 `InvalidEventException`。
- 配置：[application.properties:37](../../backend/storeService/src/main/resources/application.properties#L37)
  - `group-id=store`（契约）。同一个组里的消费者**分摊**分区；不同组各自读全部消息（tracking 是另一个组）。
  - `auto-offset-reset=earliest`：组第一次出现时从最早的消息读。Kafka 默认是 `latest`，会**跳过**组创建前就在 topic 里的事件（测试 `newConsumerGroup_readsEventsPublishedBeforeItStarted`）。
  - `ack-mode=record`：每条消息处理完（数据库事务已提交）就提交它的 offset。规则 4："先提交数据库，再提交 offset"。
  - `concurrency=3`：开 3 个消费者，每人一个分区。一个分区卡住，另外两个照常（09 · C 中 1，测试 `stuckPartition_doesNotBlockOtherPartitions`）。

### 2.5 状态不倒退 = store 的去重
- 代码：[DeliveryEventService.java:73](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L73)
  ```java
  if (rank(event) <= current) { logger.info("Skipping ..."); return; }
  ```
- 等级：PAID(0) < PICKED_UP(1) < IN_TRANSIT(2) < 终态 DELIVERED / LOST / CANCELLED / REFUNDED(3)。没付款的订单是 -1：收到配送事件就 ALERT，不改（❓8）。
- 一个规则管住两件事：**重复**的消息不会"高于"自己；**迟到**的旧状态也不会高于新状态。
- 一个事务里：`SET LOCAL lock_timeout = '5s'` → 锁订单行（`findByIdForUpdate`）→ 改状态 → Lost 时写 REFUND 任务（[:90](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L90)）。消费者**不调 bank**（规则 1，架构测试管着）。
- Cancelled 到了、订单还是 PAID → `OrderService.finishCancel`（还库存、CANCELLED、写 REFUND），和用户取消走同一段代码。
- 邮件在 `afterCommit` 里发（[:112](../../backend/storeService/src/main/java/comp5348/storeservice/service/DeliveryEventService.java#L112)）：事务回滚重试时不会多发一封。

### 2.6 两种失败 + DLT：`DefaultErrorHandler`
- 代码：[KafkaConsumerConfig.java:61](../../backend/storeService/src/main/java/comp5348/storeservice/events/KafkaConsumerConfig.java#L61)。Spring Boot 会把这个 bean 自动交给所有 `@KafkaListener` 的容器。
- 监听方法抛异常后，`DefaultErrorHandler` 决定：重试（把分区"倒回"到这条，等一会儿再读）还是交给 **recoverer**（这里是写 DLT）。

| 异常 | 处理 | 代码 |
|---|---|---|
| `InvalidEventException`、`UnknownOrderException`（消息本身有问题） | 不重试，直接写 DLT | `addNotRetryableExceptions` `:72` |
| 已知暂时性（`TransientDataAccessException`、连不上库、锁超时……） | 指数退避，最长 2 秒一次，**无限重试** | `setBackOffFunction` `:71`，`setMaxInterval` `:78` |
| 其他（多半是代码 bug） | 每 200ms 重试，共 5 次，然后写 DLT（Christy 选的 ❓1 b） | `new FixedBackOff(200L, 5)` `:70` |

- **DLT 名字**：`DeadLetterPublishingRecoverer` 默认写到 `<topic>-dlt`（Spring Kafka 文档原话），契约是 `<topic>.DLT`，所以显式给了一个"目标解析器"：`Topics.deadLetter(record.topic())`、同一个分区号。
- 写 DLT 本身失败（比如 DLT topic 不在）：recoverer 抛异常，这条消息**不提交**，会再来——不会悄悄丢（测试 `dltPublishFails_recordIsNotSkipped`）。
- 写 DLT 之前打 `ALERT kafka_dead_letter ...` 日志。DLT 里的消息带着 Spring 加的头：原 topic、异常信息。

### 2.7 启动时修旧的 CHECK 约束
- 代码：[OrderStatusCheckUpgrade.java:43](../../backend/storeService/src/main/java/comp5348/storeservice/config/OrderStatusCheckUpgrade.java#L43)
- Hibernate 6 第一次建表时给 `@Enumerated(STRING)` 列写 `CHECK (status IN (...))`；`ddl-auto=update` 以后**不会改它**。你 compose 库里的 `orders_status_check` 没有 LOST / REFUNDED，写进去就报错。测试库每次是新的，测不出来——所以有一个测试专门先造出旧表（`FreshEnvironmentIntegrationTest`）。
- 用 `@DependsOn("entityManagerFactory")` + `@PostConstruct`：在 Hibernate 建好表之后、Kafka 监听器启动之前跑（监听器要等所有 bean 都建好才启动）。
- 两条 SQL 放在一次 `execute` 里，Postgres 把它们当成一个隐式事务：中间没有"没有约束"的空档。

---

## 3. 请求路径导读（一单从揽收到送达）

```
delivery · RabbitMQ worker  DeliveryStatusUpdateService.processDelivery          (:114)
  事务：配送单 CREATED → PICKED_UP + event_outbox 插一行 PickedUp ── 提交
delivery · EventOutboxPublisher.publishPending（每 1 秒）                        (EventOutboxPublisher.java:65)
  事务：pg_try_advisory_xact_lock(5348) → 读未发送 → send(delivery-events, key=orderId) → 等确认 → sent_at
Kafka · delivery-events，分区 = hash(orderId) % 3
store · DeliveryEventsConsumer.onDeliveryEvent（3 个线程，组 store）            (DeliveryEventsConsumer.java:39)
  EventCodec.parse → 类型 / status 对得上 → orderId 是数字
  → DeliveryEventService.apply @Transactional                                  (DeliveryEventService.java:58)
      SET LOCAL lock_timeout = '5s'；SELECT … FOR UPDATE 订单行
      rank(PickedUp)=1 > rank(PAID)=0 → 订单 PICKED_UP
  ← 提交 → afterCommit 发邮件 → 提交 offset（ack-mode=record）
  抛异常 → DefaultErrorHandler：坏消息 → delivery-events.DLT；暂时故障 → 退避重试；bug → 5 次后 DLT
```

---

## 4. 为什么这样用

| 方案 | 为什么没选 / 选了 |
|---|---|
| 保留 webhook，补上重试和顺序 | 可靠性要自己从头写一遍（就是旧的 notification_queue + 死信），还是不保序 |
| 改完状态直接 `kafkaTemplate.send` | 数据库提交和发送不是原子的：中间崩了事件就丢；发了但事务回滚又多一条假事件（测试 `stateChange_andEvent_commitTogetherOrNotAtAll` 防这个） |
| Kafka 事务 / Debezium CDC | 正规但重：CDC 要多一套组件；Kafka 事务管不到 Postgres |
| 🤖 outbox 表 + 咨询锁投递（选了，设计 ADR-03/06） | 只用已有的 Postgres；代价是轮询延迟（1 秒）和"至少一次" |
| 🤖 `concurrency=3` | 设计规则 6 说"卡住这个分区"；Spring 默认 1 个线程拿 3 个分区，一条重试三个分区全停 |
| 🤖 "其他异常"重试 5 次后进 DLT（❓1 b） | 一个 bug 不能永远堵住 1/3 的订单；代价：同一订单后面的事件可能先被处理，DLT 里的要人工重放 |
| 🤖 启动时幂等 SQL 修约束（❓3 a） | 不引入 Flyway；代价：以后每加一个状态都靠这段代码同步 |

🤖 AI 替你选的其他东西：锁的数字 5348；投递每轮 100 条、每秒一轮；锁等待 5 秒；暂时故障退避 200ms 起、最长 2 秒；bug 重试间隔 200ms；"其他异常"里哪些算暂时性（`KafkaConsumerConfig.TRANSIENT` 那张表）。

顺带改的（失败场景表外，报告里说了）：
- delivery 的两个 `cancelDelivery` 以前允许 PICKED_UP 取消，现在只允许 CREATED，和设计的取消接口表一致。不然 store 会给已揽收的货"还库存"。
- 删掉了 delivery 的 `NotificationService`、`NotificationProcessingService`、`DeadLetterQueueService`、两个通知 DTO、`notification_queue` / `dead_letter_queue`；store 的 `DeliveryWebhookController`、`handleDeliveryUpdate`；compose 的 `DELIVERY_NOTIFICATION_URL`。

---

## 5. 八股在这里

**结论：这一步真有要做的——把"通知"从 HTTP 回调换成 outbox + Kafka，并把消费失败分类。不需要更重的东西：不用 Kafka 事务、不用 CDC、不用分布式锁组件。**

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 F4 Outbox | `DeliveryEventRecorder.java:33`、`EventOutboxPublisher.java:65` | 改状态和写事件同一个事务；投递程序只有一个在跑靠咨询锁。**不需要** SKIP LOCKED 多线程投递：一个订单的事件要按顺序，一个投递者够快 |
| 📒 F6 Kafka vs RabbitMQ | delivery-events 用 Kafka，配送作业仍用 RabbitMQ | "发生过的事"要多方订阅（store、tracking）+ 重放 → Kafka；"要做的一件事"要逐条确认 → 留在 RabbitMQ |
| 📒 F8 Webhook | 删掉的 `NotificationService` | webhook 适合对外通知第三方；内部服务之间要可靠、保序，用消息系统 |
| 📒 F9 事件驱动 | store 订阅、delivery 不知道谁在读 | 加 tracking 不改 delivery；代价：最终一致 + 消费者自己去重 |
| 📒 F3 幂等 | `DeliveryEventService.java:73` | store 不存"处理过的 eventId"，靠状态不倒退；**不需要**去重表，因为每个事件本身就是"进入某状态" |
| 📒 F2 resilience | `KafkaConsumerConfig.java:70` | 暂时故障退避重试，坏消息隔离（DLT）；**不需要**熔断器：消费者自己就是"慢慢来" |
| 📒 D4 @Transactional | `MANDATORY`、`afterCommit` | 传播行为当护栏用；邮件放到提交后，事务回滚不会多发 |
| 📒 A4 checked exception | `InvalidEventException` | 契约故意用 checked，监听方法签名上就写着"可能是坏消息" |
| 📒 E6 schema 变更 | `OrderStatusCheckUpgrade.java:43` | `ddl-auto=update` 只补列不改约束；这次用幂等 SQL，表结构再多变两次就该上 Flyway |
| 📒 B6 死锁 | `PaymentService.onRefundSucceeded` 先锁订单再锁 Payment | 新代码也守"先订单后 Payment"的顺序 |
| 📒 G4 Testcontainers | 真 Kafka + 真 Postgres | 分区、offset、消费者组、`lock_timeout` 都只有真东西测得出来 |
| ➕ 事务级咨询锁 | `pg_try_advisory_xact_lock` | 不锁行，锁一个数字；事务结束自动放 |
| ➕ `lock_timeout` | `DeliveryEventService.java:60` | 等锁超时变成可重试的异常，不会永远挂着占连接 |
| ➕ `auto.offset.reset` | `earliest` | 新组从头读；默认 latest 会丢启动前的事件 |
| ➕ DLT 命名 / 分区 | `KafkaConsumerConfig.java:61` | Spring 默认 `-dlt`、同分区号 → DLT 分区数不能少于原 topic |
| ➕ 测试上下文缓存 | `DeliveryEventsErrorKindsIntegrationTest` 的 `@DirtiesContext` | Spring 缓存的旧上下文里消费者还活着，会抢消息 |

英文场景锚点：
- "Delivery used to call store back over a webhook. If the call was lost, store never knew the parcel moved. Now the status change and its event commit in one transaction, and a publisher sends the event to Kafka."
- "On the store side, status never goes backwards. That one rule handles duplicates and late events."
- "A bad message goes straight to a dead-letter topic. A database hiccup is retried in place. A bug is retried five times, then dead-lettered, so it can't block a partition forever."

---

## 6. 面试追问链

1. 为什么不在改完状态后直接发 Kafka？→ 双写不原子。outbox 怎么保证不丢？→ 同一事务。怎么发出去？→ 定时投递。
2. 两个实例同时投递怎么办？→ 事务级咨询锁。为什么不用会话级？→ 连接池收回连接时锁不放。为什么不用 `SELECT … FOR UPDATE SKIP LOCKED`？→ 能并行，但同一订单的事件可能被两个实例乱序发出。
3. 发了但没标记就崩了？→ 重发，至少一次。消费者怎么去重？→ 状态不倒退。那 tracking 呢？→ eventId 唯一约束。
4. 消费失败怎么分？→ 坏消息 / 暂时性 / bug。为什么 bug 不也无限重试？→ 会卡死分区；代价是顺序。DLT 名字为什么要显式写？→ 默认 `-dlt`、自动建 topic 关着，名字错了根本写不进去。
5. 一个分区卡住会不会拖住所有订单？→ Spring 默认会，`concurrency=3` 一人一个分区。数据库锁住时呢？→ `lock_timeout` 5 秒，超时当暂时性退避重试。

---

## 7. 英文说法

- "Each delivery status change writes an event row in the same transaction. A scheduled publisher takes a transaction-level advisory lock, sends the rows in order, and marks them sent."
- "Store consumes delivery-events with three threads, one per partition. It commits the database first, then the offset, and skips any event that would move the order backwards."
- "A lost parcel only writes a refund task. The outbox processor is still the only thing that calls the bank."

---

## 8. 资料

- Transactional outbox：[microservices.io · Pattern: Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- 咨询锁（会话级 vs 事务级）：[PostgreSQL · 13.3.5 Advisory Locks](https://www.postgresql.org/docs/current/explicit-locking.html#ADVISORY-LOCKS)
- `lock_timeout`：[PostgreSQL · Client Connection Defaults · lock_timeout](https://www.postgresql.org/docs/current/runtime-config-client.html#GUC-LOCK-TIMEOUT)
- `DefaultErrorHandler`、`setBackOffFunction`、DLT 默认名 `-dlt`、recoverer 失败会重新投递：[Spring for Apache Kafka · Handling Exceptions](https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html)
- `concurrency`、`AckMode.RECORD`：[Spring for Apache Kafka · Message Listener Containers](https://docs.spring.io/spring-kafka/reference/kafka/receiving-messages/message-listener-container.html)
- `auto.offset.reset`（默认 latest）：[Apache Kafka 3.9 · Consumer Configs](https://kafka.apache.org/39/configuration/consumer-configs/)
- `enable.idempotence`：[Apache Kafka 3.9 · Producer Configs](https://kafka.apache.org/39/configuration/producer-configs/)
- 提交后再做事（`@TransactionalEventListener` 是 `afterCommit` 的声明式写法）：[Spring Framework · Transaction-bound Events](https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html)

---

## 9. 自测题

1. 投递程序发完第 3 条、标记 1–3 已发送，发第 4 条时 Kafka 挂了。这一轮结束后，表里哪些行 `sent_at` 有值？下一轮从哪条开始？
<details><summary>答案</summary>1–3 有值（这一轮的事务照样提交），4 及以后为空。下一轮从 4 开始，所以同一订单的顺序不乱。规则 3 原文写"回滚这一轮"也行，结果一样，只是 1–3 会重发一次。</details>

2. 为什么 store 不需要一张"处理过的 eventId"表也能去重？什么情况下这个办法不够？
<details><summary>答案</summary>每个配送事件的意思都是"进入某个状态"，同一状态再来一次不会"高于"当前状态，所以直接跳过。不够的情况：事件本身不是状态变化（比如"加一件商品""扣一次积分"），重复处理会累加——那就需要 eventId 去重表或唯一约束（tracking 就是这么做的）。</details>

3. 测试 `codeBug_retriedFewTimes_thenDlt` 单独跑能过，和其他测试一起跑却失败了。为什么？怎么修的？
<details><summary>答案</summary>Spring 测试会缓存上下文，前面测试类的上下文还活着，它们的消费者也在 "store" 组里，分走了分区。"埋 bug"的那条事件被别的上下文拿走（那边的仓库不是 spy，不抛异常），就看不到重试和 DLT。修法：这个类用自己的消费者组，并且 <code>@DirtiesContext</code>，跑完就关掉，免得它反过来抢后面测试的消息。</details>

---

## 10. 预计回放时间

**60 分钟**：checkout `d1d5638` 跑红（选两三个类，10 分钟）→ 读 diff 和第 2 节（20 分钟）→ checkout feat 跑绿（10 分钟）→ 第 5 节遮住"判断"自己说（10 分钟）→ 英文说一遍、做自测题（10 分钟）。
