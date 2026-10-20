# 06 · delivery-events 上 Kafka：配送状态不再靠 webhook

[English](06-kafka-delivery-events.md) | **中文**

> 对应 commit：`d1d5638 test(red)` → `f3483f0 feat`（第 2 波 A · 4a，分支 `claude/wave2-kafka-656c13`）
> **第二部分（4b · order-events）**：`17bd8e0 test(red)` → `a9d6fc6 feat`，在本文末尾 [第二部分](#第二部分--4border-events-上-kafkastore-不再用-http-建配送单)。
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
- 调用它的地方：建单 [DeliveryService.java:79](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L79)、取消 `:150` `:181` `:278`（还有取消标记 `:270`，见第二部分）、worker 每推进一步 [DeliveryStatusUpdateService.java:114](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L114)。
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
| ➕ 测试上下文缓存 / 打桩竞争 | `DeliveryEventsErrorKindsIntegrationTest` 的 `@DirtiesContext`、`latest` | Spring 缓存的旧上下文里消费者还活着，会抢消息；后台线程在调的间谍不能边跑边打桩 |

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
<details><summary>答案</summary>Spring 测试会缓存上下文，前面测试类的上下文还活着，它们的消费者也在 "store" 组里，分走了分区。"埋 bug"的那条事件被别的上下文拿走（那边的仓库不是 spy，不抛异常），就看不到重试和 DLT。修法：这个类用自己的消费者组，并且 <code>@DirtiesContext</code>，跑完就关掉，免得它反过来抢后面测试的消息。<br>后来又踩一次：新组用 <code>earliest</code>，一启动就重放几十条旧事件；Mockito 给间谍打桩<b>不是线程安全的</b>，测试线程打桩时消费者线程正好在调同一个间谍，桩挂到了别的订单上。再修：这个组用 <code>latest</code>，并且等 3 个消费者都分到分区再发事件。</details>

---

## 10. 预计回放时间

**60 分钟**：checkout `d1d5638` 跑红（选两三个类，10 分钟）→ 读 diff 和第 2 节（20 分钟）→ checkout feat 跑绿（10 分钟）→ 第 5 节遮住"判断"自己说（10 分钟）→ 英文说一遍、做自测题（10 分钟）。


---
---

# 第二部分 · 4b：order-events 上 Kafka，store 不再用 HTTP 建配送单

> 对应 commit：`17bd8e0 test(red)` → `a9d6fc6 feat`（同一分支）
> 预计回放时间：**50 分钟**（投递程序第一部分讲过，这里新的是：消费者端去重靠唯一约束、取消标记、取消接口的四种回答、提交后再发作业）
> 失败场景表：17 号表的 B、C 组，加上 store 那份 P 组。

## 1. 问题

**扣款成功后，store 用 HTTP 调 `/api/deliveries/create` 建配送单；取消时只有在 store 已经知道 deliveryId 的情况下才去问 delivery。上了 Kafka 之后，这两件事都会出钱的问题。**

- delivery 重启 30 秒：store 的 HTTP 建单重试 3 次失败 → 写 `DELIVERY_FAILED` → 30 秒后"补偿"：退款 + 取消。**用户的单没了，只因为 delivery 重启了一下。**
- 付完款到 DeliveryCreated 回来之间，store 的 `deliveryId` 是 null。用户这时点取消 → 旧代码**不问 delivery** 直接退钱 → delivery 随后读到 OrderPaid 照样建单发货。**钱退了，货也发了。**
- 还没付款就取消，扣款又晚到成功：没有任何东西挡住 delivery 建单。
- delivery 的 `order_id` 没有唯一约束：同一个 OrderPaid 收到两次 → 两张配送单。
- delivery 建单时在**事务里**就把作业发进 RabbitMQ，而且发了两次（同一个队列名写了两行）：worker 比提交快就查不到这一行、丢掉作业 → 配送单永远停在 CREATED。
- 复现测试（红灯 `17bd8e0`，42 个新测试全红）：
  - store 不发事件：[OrderEventsPublishingIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/OrderEventsPublishingIntegrationTest.java)
  - store 取消不问 delivery：[StoreCancelIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/StoreCancelIntegrationTest.java)
  - delivery 挂了就退款：[DeliveryDownIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryDownIntegrationTest.java)、[DeliveryAccessArchitectureTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/DeliveryAccessArchitectureTest.java)
  - delivery 不读 order-events：[OrderEventsConsumerIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/OrderEventsConsumerIntegrationTest.java)
  - 新取消接口：[CancelByOrderApiIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/CancelByOrderApiIntegrationTest.java)
  - 旧表、新消费者组：[DeliveryFreshEnvironmentIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryFreshEnvironmentIntegrationTest.java)
  - 作业提交后再发：[DeliveryJobAfterCommitIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DeliveryJobAfterCommitIntegrationTest.java)
  - 旧接口删掉：[OldDeliveryEndpointsRemovedTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/OldDeliveryEndpointsRemovedTest.java)

## 2. 是什么（框架层）

### 2.1 store 这边：同一套 outbox，五种事件
- 表和投递程序照抄 delivery 的（第一部分 2.1、2.2）：[EventOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/EventOutbox.java)、[EventOutboxPublisher.java:98](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java#L98)。区别：store 的 `order_id` 是数字列，发的时候转成字符串当 key。
- 写事件：[OrderEventRecorder.java:32](../../backend/storeService/src/main/java/comp5348/storeservice/events/OrderEventRecorder.java#L32)，同样是 `MANDATORY`，还检查 payload 类型和事件类型对得上。
- 五个调用点，每个都在原来那个事务里：

| 事件 | 在哪写 | 注意 |
|---|---|---|
| OrderPlaced | [OrderProductService.java:108](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java#L108) | 和建订单、锁库存、写 PAY 任务同一个事务 |
| OrderPaid | [PaymentService.java:271](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L271) | 只在"订单还没取消"那个分支；payload 带齐建单要的字段，delivery 不回调 store |
| PaymentFailed | [PaymentService.java:285](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L285) | 只有 bank 明确拒绝才写；超时（PAY_UNKNOWN）**不写**（规则 7） |
| OrderCancelled | [OrderService.java:284](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L284) | 在 `finishCancel` 里：用户取消、超时自动取消、delivery 的 Cancelled 补完取消，三条路都经过它；reason 是 `USER_REQUEST` / `PAYMENT_TIMEOUT` |
| OrderRefunded | [PaymentService.java:374](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L374) | reason 在 [:442](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L442) 算：订单 LOST → `DELIVERY_LOST`；扣款晚于取消 → `PAID_AFTER_CANCEL`；其他 → `ORDER_CANCELLED` |

- **投递程序为什么不用 `@Scheduled`**：[EventOutboxPublisher.java:67](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java#L67) 实现 `SmartLifecycle`，自己开一个线程。Spring 默认的 `taskScheduler` **只有 1 个线程**，所有 `@Scheduled` 共用；OutboxProcessor 一轮调 bank 可能好几秒（快速重试），事件就得排在后面等。
  - 这是绿灯时才发现的：store 的测试底座把 `taskScheduler` 换成"永不触发"的假对象（为了让 OutboxProcessor 只在测试手动调时跑），照抄过来的 `@Scheduled` 投递程序在测试里一次都没跑。
  - 中间试过 `@Scheduled(scheduler = "自己的bean")`，又想到**只要自己注册一个 `TaskScheduler` bean，Spring Boot 就不再建默认的那个**（**待确认**：这是读 Boot 的 `TaskSchedulingConfigurations` 上 `@ConditionalOnMissingBean` 得出的，没在这个项目里测过），那生产里所有定时任务都会挤到这一个线程上。所以最后用的是不注册成 bean 的私有线程。

### 2.2 delivery 这边：去重靠唯一约束 + `ON CONFLICT DO NOTHING`
- 建单：[DeliveryService.java:60](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L60)
  ```sql
  INSERT INTO delivery (...) VALUES (..., 'CREATED', ...)
  ON CONFLICT (order_id) DO NOTHING
  ```
  插进去 1 行 → 新单，写 DeliveryCreated；插进去 0 行 → 这单已经有一行了（重复的 OrderPaid，或者取消先到留下的**取消标记**），什么都不做。
- 为什么不像 store 那样"状态不倒退"：delivery 收到的 OrderPaid 不是"进入某状态"，而是"去做一件事（建单）"。做两次就是两张单，所以要靠数据库的唯一约束，不能靠"先查一下有没有"——两个线程同时查都会说"没有"。
- 消费者：[OrderEventsConsumer.java:44](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/OrderEventsConsumer.java#L44)。OrderPaid → 建单；OrderCancelled → 走取消（下一节）；其他类型（OrderPlaced、PaymentFailed、OrderRefunded、以后新加的）读过去，不进 DLT。
- 消费配置和错误处理照抄 store：组 `delivery`、`earliest`、`ack-mode=record`、3 个线程，[KafkaConsumerConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/KafkaConsumerConfig.java) 的三种失败。

### 2.3 取消标记和取消接口的四种回答
- 代码：[DeliveryService.java:261](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L261)。**先插、再锁**：
  ```java
  int inserted = jdbc.update("INSERT ... VALUES (0, ?, 'CANCELLED', ...) ON CONFLICT (order_id) DO NOTHING", ...);
  Delivery d = deliveryRepository.findByOrderIdForUpdate(orderId).orElseThrow();   // SELECT ... FOR UPDATE
  ```

| 情况 | 做什么 | 回答 |
|---|---|---|
| 还没有这一行 | 插进去的就是**取消标记**（状态 CANCELLED，没有地址、邮箱）+ Cancelled（deliveryId 为 null） | 200 `CANCELLED_BEFORE_CREATED` |
| CREATED | 改 CANCELLED + Cancelled | 200 `CANCELLED` |
| 已经 CANCELLED | 什么都不做 | 200 `ALREADY_CANCELLED` |
| PICKED_UP 及以后 | 不改 | 409 `TOO_LATE` + 当前状态 |

- 为什么"先插再锁"：`SELECT ... FOR UPDATE` 锁不住**还不存在**的行。取消和 OrderPaid 同时到、两边都"查不到"，就会一个插标记、一个插配送单。先插：两个 INSERT 撞在唯一索引上，后来的那个**等前一个提交**，然后 DO NOTHING；接下来的 `FOR UPDATE` 一定能看到那一行。结局永远是一行、CANCELLED（测试 `cancelAndOrderPaid_atTheSameTime_oneRowEndsCancelled` 跑 10 个订单）。
- 接口：[InternalDeliveryController.java:35](../../backend/deliveryService/src/main/java/com/comp5348/delivery/controller/InternalDeliveryController.java#L35)，在 `/internal/*` 下，不经过用户 token 过滤器；**没有鉴权**（❓2 a，记在安全待办里）。
- OrderCancelled（未付款就取消）走同一个方法；如果回答是 TOO_LATE，说明两边状态不一致（store 以为没付款，货却已经在路上），打 `ALERT`，不改（[OrderEventsConsumer.java:49](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/OrderEventsConsumer.java#L49)）。

### 2.4 store 取消：PAID 就一定先问 delivery
- 代码：[OrderService.java:238](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L238)、[DeliveryAdapter.java:51](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java#L51)

| delivery 说 | store 做 |
|---|---|
| 三种 200 | 同一个事务：订单 CANCELLED、还库存、写 REFUND、写 OrderCancelled |
| 409 | 拒绝："已经揽收了" |
| 5xx / 没回答（5 秒超时） | 拒绝："稍后再试"——**不知道** delivery 那边停没停，不能先退钱 |

- 判断条件从"`deliveryId != null`"改成"订单是 PAID"。这是 4b 红灯的重点（C6）。
- 调 delivery 的时候订单行一直锁着（最多 5 秒）：同一订单的消费者会等。设计没要求改，记在 09（C8）。

### 2.5 启动时修 delivery 的旧表、作业提交后再发
- [DeliverySchemaUpgrade.java:43](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java#L43)：`DROP NOT NULL`（取消标记没有地址、邮箱）+ `CREATE UNIQUE INDEX IF NOT EXISTS`。和 store 的 `OrderStatusCheckUpgrade` 一样的时机。**建唯一索引失败就让启动失败**：没有它，去重会悄悄失效。
- [DeliveryService.java:84](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L84)：`TransactionSynchronization.afterCommit` 里才发 RabbitMQ 作业，只发一次。残余风险：提交后、发之前崩溃 → 这张单停在 CREATED（记在 09）。

### 2.6 删掉的东西
- store：`DeliveryAdapter.createDelivery`、`CompensationService`、OutboxProcessor 里的建单和 30 秒补偿、`DeliveryRequestDTO` / `DeliveryResponseDTO`。库里留下的旧 `SUCCESS` / `DELIVERY_FAILED` 任务被标成已处理并打 WARN（[OutboxProcessor.java:249](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L249)，❓4 a：升级前人工查一下有没有）。
- delivery：`/api/deliveries/create`、`/create/batch`、旧的 `/api/deliveries/cancel-by-order`，以及 token 过滤器里放行它们的白名单。`GET /{deliveryId}` 改成只匹配数字，否则 `POST /create` 会匹配到它、回 405 而不是 404。

## 3. 请求路径导读（付款成功 → 建配送单；付完款马上取消）

```
store · OutboxProcessor → bank 扣款成功 → PaymentService.onPaySucceeded            (:271)
  事务：锁订单行 → 订单 PAID、Payment SUCCESS → event_outbox 插 OrderPaid ── 提交
store · EventOutboxPublisher（自己的线程，每 1 秒）→ order-events，key=orderId
delivery · OrderEventsConsumer（组 delivery，3 线程）                              (OrderEventsConsumer.java:44)
  → DeliveryService.createDelivery @Transactional                                  (:60)
      INSERT … ON CONFLICT (order_id) DO NOTHING → 1 行
      event_outbox 插 DeliveryCreated；登记 afterCommit
  ← 提交 → afterCommit：RabbitMQ 发作业（一次）→ 提交 offset
delivery · EventOutboxPublisher → delivery-events → store 记下 deliveryId（第一部分）

用户这时点取消（store 可能还不知道 deliveryId）：
store · OrderService.cancelOrder                                                   (:238)
  锁订单行 → 订单是 PAID → POST /internal/deliveries/cancel-by-order/{id}
     delivery：INSERT 标记 ON CONFLICT → 0 行（配送单已经在了）→ FOR UPDATE → CREATED → CANCELLED + Cancelled
     ← 200 {"result":"CANCELLED"}
  finishCancel：还库存、CANCELLED、写 REFUND、写 OrderCancelled ── 提交
delivery 之后读到 OrderCancelled → ALREADY_CANCELLED，什么都不做
store 读到 Cancelled → 订单已经 CANCELLED（状态不倒退），什么都不做
```

## 4. 为什么这样用

| 方案 | 为什么没选 / 选了 |
|---|---|
| 留着 HTTP 建单，只是再加一个 Kafka 事件 | 两个建单入口：delivery 挂了 HTTP 路还会触发补偿退款；靠唯一约束才不会建两张 |
| delivery 读 OrderPaid 后回调 store 取地址、邮箱 | 又把同步依赖加回来了：store 挂了 delivery 就建不了单。契约让 OrderPaid 带齐字段 |
| delivery 建单前"先查一下有没有" | 两个线程同时查都说没有 → 两张单。必须是数据库唯一约束 |
| 取消时 `SELECT ... FOR UPDATE` 再决定插不插 | 锁不住不存在的行，取消和 OrderPaid 会各插一行 |
| 🤖 先 INSERT ON CONFLICT 再 FOR UPDATE（选了，设计的取消接口一节） | 让唯一索引来排队；代价：每次 ON CONFLICT 都会用掉一个自增 id，配送单号会跳号（真实运行里看到 2、5、8） |
| 🤖 超时 / 5xx 一律拒绝取消 | 不知道 delivery 停没停，先退钱可能"钱退了、货也发了"；代价：delivery 挂着时用户取消不了已付款的单 |
| 🤖 投递程序用私有线程，不用 `@Scheduled` | 默认调度池 1 个线程；自己注册 TaskScheduler bean 会让 Boot 不建默认的（待确认） |
| 🤖 `GET /{deliveryId:[0-9]+}` | 让删掉的 `POST /create` 回 404 而不是 405 |

🤖 AI 替你选的其他东西：取消接口回答的字段名（`result`、`status`）；唯一索引名 `ux_delivery_order_id`；PaymentFailed 只在 bank 拒绝时写（"没绑银行账户"那种确定失败不写）；OrderRefunded 的 reason 用订单状态和退款任务里的文字推出来；LOST / REFUNDED / CANCELLED_SYSTEM 的订单现在不能取消（以前能）。

## 5. 八股在这里

**结论：这一步真有要做的——"建单"从同步调用变成订阅事件，去重从"查一下"变成唯一约束，取消从"可能不问"变成"一定先问"。不需要分布式事务、不需要 Saga 编排器。**

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 F3 幂等 | `DeliveryService.java:60` 的 `ON CONFLICT` | 对"做一件事"的消息，幂等靠唯一约束；对"进入某状态"的消息（store 那边），靠状态不倒退 |
| 📒 F4 Outbox | `OrderEventRecorder.java:32` | store 和 delivery 各一份，表结构、投递逻辑一样 |
| 📒 F5 Saga / 补偿 | 删掉的 `CompensationService` | 旧的补偿是"建单失败就退款"——把暂时故障当成永久失败。现在 OrderPaid 留在 Kafka 里等，不需要补偿 |
| 📒 F9 事件驱动 | delivery 订阅 OrderPaid | store 不知道谁在建单；代价：取消要处理"事件还在路上"的窗口（取消标记） |
| 📒 E3 悲观锁 | `findByOrderIdForUpdate` | 锁已有的行；**锁不住不存在的行**，所以先插 |
| 📒 E5 索引（唯一索引） | `ux_delivery_order_id` | 两个 INSERT 撞唯一索引时后来的等前面提交——数据库替你排队 |
| 📒 D4 @Transactional | `afterCommit` 发 RabbitMQ | 副作用放到提交后；代价是提交后崩溃会丢这个副作用 |
| 📒 E6 schema 变更 | `DeliverySchemaUpgrade.java:43` | 第二次用幂等 SQL 了；再有第三次就该上 Flyway |
| ➕ `INSERT ... ON CONFLICT DO NOTHING` | `DeliveryService.java:66` | 返回影响行数 0/1，比先查再插少一次往返，还没有竞态 |
| ➕ Spring 调度池默认 1 线程 | `EventOutboxPublisher.java:67` | 自己注册 TaskScheduler bean 会让默认的消失（待确认，见 2.1） |

### 场景版讲解（3 条）

**① F3 幂等：同一个 OrderPaid 收到两次**
- 场景：store 的投递程序把 OrderPaid 发出去了，还没来得及标记"已发送"就重启了，下一轮又发一次。delivery 收到两条一样的 OrderPaid。
- 旧代码怎么错：`order_id` 没有唯一约束，两条都 `save(new Delivery(...))` → 两张配送单 → worker 推进两张 → 用户收到两份货的邮件，仓库发两次货。
- 新代码：[DeliveryService.java:66](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L66)
  ```sql
  INSERT INTO delivery (...) VALUES (...) ON CONFLICT (order_id) DO NOTHING
  ```
  第二条插进去 0 行，直接返回。
- 面试怎么答："Kafka 是至少一次，所以消费者必须幂等。建配送单是'做一件事'，我用 order_id 唯一约束加 ON CONFLICT DO NOTHING，测试里同一条发三次只有一张单。"
- 什么时候**不**用：消息本身是"进入某个状态"（store 收 PickedUp），状态不倒退就够了，不需要唯一约束；消息是"加 1"（积分），要用 eventId 去重表。

**② E3 悲观锁锁不住不存在的行：取消和 OrderPaid 同时到**
- 场景：用户付完款马上点取消。store 调 delivery 取消接口的同一毫秒，delivery 的消费者正在处理这单的 OrderPaid。
- 如果先 `SELECT ... FOR UPDATE`：两边都查不到这一行（还没建），锁不住任何东西 → 取消插一个标记、OrderPaid 插一张配送单 → 有唯一约束时其中一个报错，没有时两行都在 → 配送单照样发货。
- 新代码：[DeliveryService.java:263](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L263) 先 `INSERT ... ON CONFLICT DO NOTHING`，再 `findByOrderIdForUpdate`。两个 INSERT 撞上唯一索引，后来的等前一个提交。
- 面试怎么答："FOR UPDATE 只能锁已经存在的行。要防'两边都以为没有'，先插再锁，让唯一索引排队。我的测试让取消和 OrderPaid 用 CountDownLatch 同时放出去，跑 10 个订单都只有一行、都是 CANCELLED。"
- 什么时候**不**用：行一定已经存在（比如改库存），直接 FOR UPDATE 就行；或者用 SERIALIZABLE 隔离级别，代价是要处理重试。

**③ F5 补偿：把暂时故障当成永久失败**
- 场景：delivery 正在滚动更新，30 秒连不上。这期间有 5 个用户付了款。
- 旧代码怎么错：store 调 `/api/deliveries/create` 重试 3 次失败 → 写 DELIVERY_FAILED → 30 秒后 `CompensationService` 退款、订单 CANCELLED_SYSTEM → **5 个用户的单全被取消**，而 delivery 一分钟后就好了。
- 新代码：store 只写 OrderPaid（[PaymentService.java:271](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L271)）。事件留在 Kafka 里，delivery 起来后接着读。测试 `deliveryDown_orderPaidWaitsInKafka_noCompensation` 拨表 1 分钟：没有退款、订单还是 PAID。
- 面试怎么答："补偿应该用在业务上真的做不成的时候，不是对方暂时不在。换成事件以后，delivery 晚一分钟建单，用户不受影响。"
- 什么时候**要**用补偿：业务上永久失败——比如 bank 明确拒绝扣款、商品下架了没法发货，这时才退款或取消。

## 6. 面试追问链

1. delivery 怎么知道要建单？→ 订阅 OrderPaid。为什么不让 store 调？→ delivery 挂了 store 就得补偿。OrderPaid 里为什么带地址邮箱？→ 不回调 store。
2. OrderPaid 重复怎么办？→ 唯一约束 + ON CONFLICT。为什么不先查？→ 竞态。
3. 用户付完款马上取消，delivery 还没建单？→ 取消标记。标记和 OrderPaid 同时到？→ 先插再锁。
4. 取消时 delivery 超时了？→ 拒绝。为什么不先退钱？→ 不知道它停没停。代价？→ delivery 挂着时取消不了。
5. 建单后立刻发 RabbitMQ 作业有什么问题？→ 事务没提交 worker 读不到。afterCommit 有什么残余风险？→ 提交后崩溃，作业丢，单停在 CREATED。怎么补？→ 定时扫"CREATED 超过 N 分钟"的单重发（没做，记在 09）。

## 7. 英文说法

- "When a payment succeeds, store writes an OrderPaid event in the same transaction. Delivery consumes it and creates the delivery with an insert that does nothing on a duplicate order id."
- "If the user cancels before delivery has seen the payment, delivery leaves a cancel marker row, so a late OrderPaid finds the row and stops."
- "Store always asks delivery before cancelling a paid order. If delivery doesn't answer, store refuses, because refunding first could mean refunding a parcel that still ships."

## 8. 资料

- `INSERT ... ON CONFLICT`：[PostgreSQL · INSERT · ON CONFLICT Clause](https://www.postgresql.org/docs/current/sql-insert.html#SQL-ON-CONFLICT)
- 唯一索引上并发插入时后来者等待：[PostgreSQL · Index Uniqueness Checks](https://www.postgresql.org/docs/current/index-unique-checks.html)
- `FOR UPDATE` 只锁读到的行：[PostgreSQL · SELECT · The Locking Clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- `TransactionSynchronization.afterCommit`：[Spring Framework · TransactionSynchronization（Javadoc）](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/transaction/support/TransactionSynchronization.html)
- 默认调度池 1 个线程（原文 "uses one thread by default"）：[Spring Boot · Task Execution and Scheduling](https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html)
- `SmartLifecycle`：[Spring Framework · Startup and Shutdown Callbacks](https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor)

## 9. 自测题

1. 用户付完款立刻取消。store 调取消接口时 delivery 已经读到 OrderPaid、建好了单。之后 delivery 又读到 store 发的 OrderCancelled。delivery 这一行最后是什么状态？delivery-events 里这单有几条 Cancelled？
<details><summary>答案</summary>CANCELLED，只有 1 条 Cancelled。取消接口把 CREATED 改成 CANCELLED 并写了 Cancelled；之后的 OrderCancelled 走同一个方法，INSERT 0 行、FOR UPDATE 读到 CANCELLED → ALREADY_CANCELLED，不写事件。真实运行里订单 2、3 就是这样。</details>

2. 为什么 store 收到 delivery 的 5xx 时不先取消订单、等 Cancelled 事件来了再说？
<details><summary>答案</summary>5xx 说明不知道 delivery 那边做没做。如果 store 先取消并退钱，而 delivery 其实没取消，货照样发出去——钱货两失。拒绝取消让用户稍后再试，最坏情况是用户多点一次。反过来，如果 delivery 其实取消了（只是回答丢了），它发出的 Cancelled 会让 store 补完取消（第一部分的 A11），钱也会退。</details>

3. 绿灯时 store 的事件一条都没发出来，所有等事件的测试都超时，日志里连"投递失败"都没有。原因是什么？
<details><summary>答案</summary>投递程序是照 delivery 抄的 <code>@Scheduled</code>，而 store 的测试底座把 <code>taskScheduler</code> 换成了一个永不触发的 Mockito 假对象（为了让 OutboxProcessor 只在测试手动调时跑）。所以它在测试里从来没跑过。改成投递程序自己管一个线程（SmartLifecycle）。没用 <code>@Scheduled(scheduler=...)</code>，因为一注册 TaskScheduler bean，Boot 应该就不建默认的了（待确认）。</details>

## 10. 预计回放时间

**50 分钟**：checkout `17bd8e0` 跑红（`StoreCancelIntegrationTest` + `OrderEventsConsumerIntegrationTest`，3 分钟）→ 读 diff 和第 2 节（20 分钟）→ checkout `a9d6fc6` 跑绿（5 分钟）→ 第 5 节三条场景遮住"新代码"自己说（12 分钟）→ 英文说一遍、做自测题（10 分钟）。
