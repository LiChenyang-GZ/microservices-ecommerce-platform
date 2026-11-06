# 09 · 卡住的配送单：扫表补发（第 2 波集成发现 I1）

[English](09-stuck-delivery-job-sweeper.md) | **中文**

> 对应 commit：`493f384 test(red)` → `d903f15 fix`（第 2 波最终集成，分支 `claude/wave2-integration`）
> 怎么发现的：集成时关掉 RabbitMQ 跑场景 [05-rabbitmq-down-at-delivery.ps1](../../backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1)，配送单永远停在 CREATED。
> 预计回放时间：**30 分钟**
> 前置：[01 · 幂等扣款与退款](01-idempotent-payments.zh-CN.md)（outbox）、[06 · Kafka](06-kafka-delivery-events.zh-CN.md)（4b：OrderPaid 建单、作业提交后再发）。

---

## 1. 问题

**改数据库和发 RabbitMQ 是两步，中间断了没人补：RabbitMQ 挂的那一刻建的配送单，永远不会被配送。**

- 订单付了钱，delivery 从 Kafka 读到 OrderPaid，建配送单并提交，然后在 `afterCommit` 里把"开始模拟配送"的作业发进 RabbitMQ（[DeliveryService.java:96](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L96)）。
- RabbitMQ 挂着 → 发送抛异常 → Kafka 重投这条 OrderPaid → delivery 看到"这单已经有配送单了"，按重复消息跳过 → **再也没有东西会发作业**。用户付了钱，货不动，也没有告警。
- 同一类的第二处：worker 每推进一步、提交之后，再把作业放回队列（[DeliveryStatusUpdateService.java:123](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java#L123)）。这一刻 RabbitMQ 挂了，单子停在 PICKED_UP / DELIVERING。
- 复现测试（红灯 `493f384`）：[StuckDeliveryJobIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/StuckDeliveryJobIntegrationTest.java)
  - `jobLostWhenCreated_isSentAgain_afterRabbitComesBack`（第 60 行）：建单时 RabbitMQ 挂了
  - `jobLostBetweenSteps_isSentAgain_afterRabbitComesBack`（第 78 行）：推进一步之后 RabbitMQ 挂了
  - `finishedDelivery_isNeverSentAgain`（第 99 行）：守护测试，终态的单不能被补发

---

## 2. 是什么（框架层）

### 2.1 为什么数据库事务管不到 RabbitMQ
`@Transactional` 背后是 Postgres 的事务：同一个数据库连接上的写，一起提交或一起回滚。RabbitMQ 是另一个进程，Postgres 没法替它回滚，它也没法等 Postgres 提交再生效。所以"改数据 + 发消息"只能排先后：
- **先发、后提交**：消息出去了，提交失败 → 消费者拿到库里不存在的 id。就算提交成功，消费者也可能比提交快，查不到行就把作业丢了（第 1 波 B12）。
- **先提交、后发**（现在的写法，B12 的修法）：提交成功，发送失败 → 作业丢了（就是 I1）。

同一个方法里的 DeliveryCreated 事件没有这个问题：它不直接发 Kafka，而是先写进 `event_outbox` 表，和配送单同一个事务，再由投递程序发出去。**outbox 把"发消息"变成"写一行数据库"，所以能进同一个事务。**

### 2.2 `TransactionSynchronization.afterCommit`
在事务里注册一个回调，Spring 在**提交成功之后**调用它。适合"只有提交了才该做"的事。它已经在事务外面了：这里抛异常不会回滚已提交的数据，异常会冒给调用方（这里是 Kafka 监听器）。Spring 也有注解版：`@TransactionalEventListener`，默认就在 `AFTER_COMMIT` 阶段执行。

### 2.3 扫表补发：`StuckDeliveryJobSweeper`
[StuckDeliveryJobSweeper.java:54](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java#L54)，每 10 秒跑一次：
1. 找"不是终态（RECEIVED / LOST / CANCELLED 以外）、`update_time` 超过 60 秒没动"的配送单（第 58 行）。
2. **先认领**（第 64 行）：`UPDATE delivery SET update_time = now WHERE id = ? AND update_time = 读到的值`。改到 1 行才算抢到。这是一个**条件更新**（compare-and-set）：
   - 两个 delivery 实例同时扫到同一行，只有一个改得到；
   - 发送失败的行 `update_time` 已经变成 now，要再等 60 秒才会被重试，不会每一轮都去撞。
3. 再发作业（第 69 行）。发失败就打 `ALERT delivery_job_resend_failed`（第 74 行），这一轮停下：RabbitMQ 挂着时一次发送要卡约 20 秒（连接超时），没必要一行一行都卡一遍。

正常情况下作业还是 `afterCommit` 立刻发出去（快路径），扫表只在丢了的时候才起作用（慢路径，最多晚 70 秒）。

### 2.4 `@Scheduled` 和调度线程池
`@Scheduled(fixedDelayString = "${delivery.stuck-job.check-interval-ms:10000}")`：上一次跑完后隔 10 秒再跑。Spring Boot 默认的调度器**只有 1 个线程**，所有 `@Scheduled` 方法排队用它。delivery 里还有 Kafka 事件投递程序（每秒一次）。RabbitMQ 挂着时扫表一次发送卡 20 秒，就会把 Kafka 事件也拖住 20 秒。所以 [application.properties:43](../../backend/deliveryService/src/main/resources/application.properties#L43) 设了 `spring.task.scheduling.pool.size=2`。

### 2.5 `@ConditionalOnProperty` 开关
`@ConditionalOnProperty(name = "delivery.stuck-job.enabled", matchIfMissing = true)`（第 35 行）：配置里没写这个键时也创建这个 bean（`matchIfMissing` 默认是 false，这里改成 true）；写成 false 就不创建。加它是因为测试里踩了坑，见 §4.3。

### 2.6 重复作业怎么办
扫表补发是"至少一次"：作业只是慢、没丢的话，可能同时有两个。worker 保存时有 `@Version` 乐观锁：两个作业读到同一个版本，后保存的那个冲突、被丢弃（[DeliveryStatusUpdateService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeliveryStatusUpdateService.java) 的 `ObjectOptimisticLockingFailureException` 分支）。一前一后的话，单子会走快一步——状态还是按顺序前进，不会跳回去。

---

## 3. 请求路径导读（RabbitMQ 挂着时付款）

1. 用户下单 → store 扣款（gRPC）→ 订单 PAID → OrderPaid 写进 store 的 `event_outbox` → 投递程序发到 Kafka。
2. delivery 的 `OrderEventsConsumer` 读到 OrderPaid → `DeliveryService.createDelivery`：插配送单（CREATED）+ 写 DeliveryCreated，**提交**。
3. `afterCommit` 发作业 → RabbitMQ 连不上，约 20 秒后抛异常 → 冒到 Kafka 监听器 → Kafka 重投 → `createDelivery` 看到行已存在，跳过。
4. RabbitMQ 恢复。最多 10 秒后 `StuckDeliveryJobSweeper` 扫到这张 60 秒没动的单 → 认领 → 发作业。
5. `DeliveryStatusUpdateService.processDelivery` 收到作业 → 等 10 秒 → PICKED_UP，写 PickedUp 事件 → 放回队列 → … → RECEIVED。

真环境证据（compose 和 kind 都跑过）：重建镜像后，**前一天卡住的配送单 11 一启动就被捡起来**（日志 `Delivery 11 had not moved since 19:39:52`）；场景 05 在两边都 PASS。

---

## 4. 为什么这样用

### 4.1 三种修法（Christy 选了 A）
| 修法 | 管得住 | 管不住 | 代价 |
|---|---|---|---|
| **A 扫表补发**（选了） | 建单时丢、每一步之后丢、RabbitMQ 收了又丢 | — | 最多晚 70 秒；可能出现重复作业（靠 `@Version` 挡） |
| B 作业也走 outbox | 发不出去 | RabbitMQ 收了又丢；消费者处理一半挂了 | 多一张表、一个投递程序，第 7 步也要改 |
| C 不修 | — | 全部 | 付了钱不发货，没人知道 |

业界的做法通常是"**主路径尽量不丢 + 兜底一定能补**"，两层一起用：主路径用 outbox、Outbox + CDC（Debezium）或事务消息（RocketMQ 的半消息 + 回查）；兜底用对账 / 扫描。A 就是兜底那一层；现有的 `afterCommit` 当快路径。

### 4.2 更彻底的做法：工作流引擎 🤖
"每 10 秒推进一步"本质是一个**带定时器的多步流程**。现在靠"处理完把自己放回 RabbitMQ"转圈，每一圈都有一次提交后再发的缺口。真实系统会交给工作流引擎（Temporal、AWS Step Functions）：每一步和每个定时器都持久化，进程挂了、消息丢了都会从上次的位置继续，扫表也不需要了。**上云时的候选：AWS Step Functions**，用 Wait 状态做每步之间的等待（记在 Obsidian 09 待办）。

### 4.3 60 秒、10 秒、条件更新——这些是 🤖 选的
- **60 秒**：正常每步 10 秒，取 6 倍，正在推进的单不会被当成卡住。太短会产生很多重复作业；太长用户等得久。
- **条件更新而不是 `SELECT … FOR UPDATE`**：`FOR UPDATE` 要在发送期间一直持有行锁，RabbitMQ 挂着时会锁 20 秒，取消接口也会被卡住。条件更新先提交认领、再发，发送时不持锁。
- **测试里的坑**：`DeliveryLostEventIntegrationTest` 用 `@TestPropertySource` 改了丢件率，Spring 给它单独建一个应用上下文并**缓存着不关**，它的 `@Scheduled` 也一直在跑——等于同一个测试库上有第二个 delivery 实例。它抢先认领了别的测试的配送单，把作业发进它自己的假 RabbitMQ。所以加了 §2.5 的开关，在那个测试里关掉。这也顺便证明了：两个实例同时扫，条件更新只让一个抢到。

---

## 5. 八股在这里

**结论**：这一步没有为了凑八股去优化代码。真正用到的是 outbox 的追问（为什么不能放一个事务）、乐观锁挡重复、调度线程池。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 F4 Outbox（追问：为什么不在一个事务里） | [DeliveryService.java:96](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java#L96) | Postgres 事务管不到 RabbitMQ，只能排先后，两种顺序都有缺口。这里没把作业也改成 outbox，而是加兜底扫描（§4.1）。**不需要**的地方：store 发邮件，丢一封无所谓，直接发 + catch |
| 📒 E3 乐观锁 | worker 的 `@Version` + `ObjectOptimisticLockingFailureException` | 补发可能造成重复作业，冲突少见 → 乐观锁正合适，冲突了就丢掉后来的那个 |
| ➕ 条件更新（CAS）认领 | [StuckDeliveryJobSweeper.java:64](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/StuckDeliveryJobSweeper.java#L64) | `UPDATE … WHERE update_time = 读到的值`，改到 1 行才算抢到；多实例不重复，而且发送时不持锁。**不需要**：只有一个实例、也不在乎重复时，直接发就行 |
| ➕ Spring 调度池默认 1 线程 | [application.properties:43](../../backend/deliveryService/src/main/resources/application.properties#L43) | 一个会阻塞的 `@Scheduled` 会拖住其他所有定时任务 → 池子设成 2。**不需要**：定时任务都很快、不会阻塞时，1 个线程就够 |
| ➕ 对账 / 兜底扫描 | 整个 `StuckDeliveryJobSweeper` | 主路径（afterCommit）负责快，兜底负责一定能补。**不需要**：丢了无所谓的消息 |

英文场景锚点：
- "The commit and the RabbitMQ send were two steps. When RabbitMQ was down in between, the job was lost and the parcel never moved. I found it by stopping RabbitMQ in an end-to-end run."
- "I kept the fast path and added a sweeper as a safety net. It claims a stuck row with a conditional update, so two instances never resend the same delivery."

---

## 6. 面试追问链

1. **为什么配送单会卡住？** → 提交和发 RabbitMQ 是两步，中间 RabbitMQ 挂了，作业丢了；Kafka 重投的 OrderPaid 又被当成重复跳过。
2. **为什么不把发消息放进事务里？** → 数据库事务管不到 RabbitMQ；在事务里发会出现"消息到了、数据还没提交"（B12）。
3. **为什么不用 2PC / XA？** → RabbitMQ 不支持 XA；协调者会把锁拉长，挂了会卡住所有参与者。业界用 outbox / 本地消息表 + 幂等代替。
4. **扫表会不会重复发？两个实例呢？** → 条件更新认领，只有一个实例抢得到；作业只是慢没丢时可能重复，worker 的 `@Version` 挡住。
5. **如果流程更长、等待更久呢？** → 不在 MQ 里自己转圈，交给工作流引擎（Temporal / AWS Step Functions），定时器和每一步都持久化。

---

## 7. 英文说法

- "Writing to the database and sending to RabbitMQ can't be in one transaction. One of the two orders always has a gap."
- "Our fast path sends the job after commit. A sweeper is the safety net: it finds deliveries that haven't moved for a minute and sends their job again."
- "In the cloud I'd move this multi-step flow with timers to AWS Step Functions instead of looping through a queue."

---

## 8. 资料

- Transactional outbox 模式：<https://microservices.io/patterns/data/transactional-outbox.html>
- Spring 事务绑定的事件（`@TransactionalEventListener`，默认 `AFTER_COMMIT`）：<https://docs.spring.io/spring-framework/reference/data-access/transaction/event.html>
- Spring Boot 任务调度（调度器默认 1 个线程、`spring.task.scheduling.pool.size`）：<https://docs.spring.io/spring-boot/reference/features/task-execution-and-scheduling.html>
- `@ConditionalOnProperty` 和 `matchIfMissing`：<https://docs.spring.io/spring-boot/api/java/org/springframework/boot/autoconfigure/condition/ConditionalOnProperty.html>
- Spring 测试的上下文缓存（为什么 LostEvent 测试成了"第二个实例"）：<https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/ctx-management/caching.html>
- AWS Step Functions 的 Wait 状态：<https://docs.aws.amazon.com/step-functions/latest/dg/state-wait.html>

---

## 9. 自测题

1. 把 `afterCommit` 里的 `convertAndSend` 挪回事务里面，I1 就没了吗？会出现什么新问题？
<details><summary>答案</summary>
不会真的没了。发送抛异常会让事务回滚，Kafka 重投后再试；但 RabbitMQ 挂得久，重试次数用完，这条 OrderPaid 还是没建出单，要靠别的东西兜底。更糟的是 RabbitMQ 正常时会出现 B12：消息先到，worker 查不到还没提交的行，把作业丢掉，配送单照样卡在 CREATED。两种顺序都有缺口，所以要兜底。
</details>

2. 扫表为什么"先认领、后发送"，而不是"先发送、发成功再更新 update_time"？
<details><summary>答案</summary>
先发后更新的话，两个实例可能都读到同一行、都发出去（重复）；发送失败时 update_time 没变，下一轮（10 秒后）又会去撞，每次都卡 20 秒。先认领：只有一个实例改得到这一行；失败了也要等 60 秒才重试。
</details>

3. （只有真懂才答得出）为什么这个 bug 在第 1 波的测试和 4b 的测试里都没被发现，而是在集成时才发现？
<details><summary>答案</summary>
delivery 的集成测试把 RabbitTemplate 换成了什么都不做的假对象（基类注释："RabbitMQ 不是这一步要测的"），它永远不会失败；B12 的测试只检查"提交后才发、只发一次"，没有检查"发失败之后呢"。只有在真环境里关掉 RabbitMQ，"发送失败"这条路径才第一次被走到。所以集成要按失败场景跑，而不只是跑正常路径。
</details>

---

## 10. 预计回放时间

**30 分钟**：读 §1、§2.1–2.3（10 分钟）→ 跑红灯：`git checkout 493f384` 后 `./gradlew :deliveryService:test --tests '*StuckDeliveryJobIntegrationTest'`（5 分钟）→ 回到 `d903f15` 跑绿（5 分钟）→ 自测题（10 分钟）。想看真环境：compose 起来后跑 `pwsh backend/docker/scenarios/05-rabbitmq-down-at-delivery.ps1`。
