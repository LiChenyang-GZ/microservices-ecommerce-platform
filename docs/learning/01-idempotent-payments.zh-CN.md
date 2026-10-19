# 01 · 修扣款和退款：超时不等于失败

[English](01-idempotent-payments.md) | **中文**

> 对应 commit：`49b4c70 test(red)`（15 行）、`67235f0 test(red)`（第 16 行）→ `ce8d34d feat`
> 预计回放时间：**60 分钟**
> 失败场景表（16 行）在 Obsidian：`开发/电商项目/03-第1波A-修钱-失败场景表.md`。这份文档讲**这一步学到的概念**。

---

## 1. 问题

**store 调 bank 扣款、退款时，只要遇到一次超时、一次连点、或者"扣款成功时订单刚好被取消"，钱就会扣两次、退不回来，或者状态乱掉。**

红灯阶段在旧代码上复现了 16 个问题，最典型的四个：

| 问题 | 复现的测试 | 旧代码为什么错 |
|---|---|---|
| 超时后重试扣了两次 | [PaymentTimeoutIntegrationTest.java:33](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PaymentTimeoutIntegrationTest.java#L33) | 每次扣款随机生成流水号；而且下一单会写**两条** PAY 任务 |
| 两个标签页同时点 Pay Now，扣两次 | [PayNowIntegrationTest.java:101](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PayNowIntegrationTest.java#L101) | Pay Now 绕过 outbox 直接调 bank，流水号带毫秒，没有锁 |
| 扣款成功时订单已取消，钱没退 | [PaymentResultIntegrationTest.java:57](../../backend/storeService/src/test/java/comp5348/storeservice/payment/PaymentResultIntegrationTest.java#L57) | 扣款结果回来不看订单状态，直接把订单改回 PAID |
| 同一账户同时转 10 笔，只扣了 1 笔的钱 | [BankConcurrencyIntegrationTest.java:64](../../backend/bankService/src/test/java/com/comp5348/bank/BankConcurrencyIntegrationTest.java#L64) | bank "读余额 → 内存里减 → 写回"，账户行没加锁（丢失更新） |

---

## 2. 是什么（框架层）

### 2.1 幂等键（idempotency key）
**大白话**：给每一笔"要做的事"起一个固定的名字。同一个名字的请求，不管来几次，bank 只做一次，后面几次都回答"第一次的结果"。
- 扣款：`PAY-{paymentId}-{attempt}`；退款：`REFUND-{paymentId}`。名字在**写任务的时候**就定下来，存在 `payment_outbox.idempotency_key` 里，之后每次重试都用它：[PaymentService.java:76](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L76)
- `attempt` 只在 bank **明确拒绝**之后、用户点 Pay Now 时才加 1：[PaymentService.java:121](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L121)
- bank 那边：`transactions.transaction_ref` 有唯一约束，存的就是这个键。先查键，查到就返回第一次的结果：[BankService.java:53](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L53)

### 2.2 三种结果：成功 / 拒绝 / 不知道
- 调一次 bank 只有三种结果：`SUCCEEDED`（成功）、`REJECTED`（bank 明确说不行，比如余额不足）、`UNKNOWN`（超时、连接断了、5xx，**不知道钱动没动**）：[BankCallResult.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankCallResult.java)
- 怎么判断：只有 bank 自己回的 `success=false`（HTTP 400 + JSON）才算拒绝；其他一切都算"不知道"：[BankAdapter.java:91](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L91)
- 旧代码的错：`RestTemplate` 遇到 4xx 会抛 `HttpClientErrorException`，旧的 `BankAdapter` 一律 catch 住、重试 3 次，最后说"bank 不可用"。所以"余额不足"被当成"bank 挂了"，"超时"又被当成"失败"。两个方向都错。
- `RestTemplate` 的异常分两类：`HttpClientErrorException`（4xx，对方回答了）和 `ResourceAccessException`（I/O 错误，包括超时，对方没回答）。后者是 `RestClientException` 的子类。

### 2.3 规则 7：超时不等于失败
- 结果 `UNKNOWN`：**同一个键**马上再试，最多 5 次；还是不知道 → `PAY_UNKNOWN`（退款是 `REFUND_UNKNOWN`）+ 告警，之后每 10 分钟用同一个键试一次：[OutboxProcessor.java:163](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L163)、[PaymentService.java:255](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L255)
- **绝对不能**标成 FAILED、也不能 attempt + 1：钱可能已经扣了，换新键再扣就是扣两次。
- "过 10 分钟"用的是 `nextAttemptAt` 字段 + 注入的 `Clock`，测试里拨表就行。

### 2.4 悲观锁：`SELECT … FOR UPDATE`
**大白话**：读这一行的时候就把它锁住，别的事务想改它只能排队，等我提交了再说。
- Spring Data JPA 写法：在 repository 方法上加 `@Lock(LockModeType.PESSIMISTIC_WRITE)`，Hibernate 生成的 SQL 末尾带 `FOR UPDATE`：[BankAccountRepository.java:28](../../backend/bankService/src/main/java/com/comp5348/bank/repository/BankAccountRepository.java#L28)、[PaymentRepository.java:26](../../backend/storeService/src/main/java/comp5348/storeservice/repository/PaymentRepository.java#L26)
- 锁在事务结束时（提交或回滚）自动释放。所以**锁住的那段事务里绝不能调外部服务**，否则一个慢请求就把所有人卡住。
- 用在三个地方：
  1. bank 转账/退款锁两个账户行（修 #16 的丢失更新）：[BankService.java:62](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L62)
  2. Pay Now 锁 Payment 行（修 #13 两个标签页）：[PaymentService.java:121](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L121)
  3. 扣款结果回来、取消订单时锁订单行（修 #7）：[PaymentService.java:208](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L208)、[OrderService.java:187](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L187)

### 2.5 按固定顺序加锁，防死锁
- 转账 A→B 锁 A 再锁 B，同时另一笔 B→A 锁 B 再锁 A，就会互相等，永远等下去。
- bank：两个账户永远**按账号排序**再加锁：[BankService.java:187](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L187)
- store：永远**先锁订单行、再锁 Payment 行**（取消、扣款结果、补偿都这样）。
- Postgres 发现死锁会让其中一个事务失败，但那是兜底，不是设计。

### 2.6 READ COMMITTED 下"锁完再查一次"
- Postgres 默认隔离级别是 READ COMMITTED：**每条语句**开始时看到的是"到这一刻为止已经提交的数据"。
- 同一个键的两个请求同时到 bank（#3）：两个都查到"没有这个键"，都去锁账户。第一个拿到锁、扣钱、提交；第二个等锁，拿到锁后**再查一次键**，这时能看到第一个已经提交的记录，于是返回第一次的结果：[BankService.java:64](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L64)
- 旧代码只靠唯一约束：第二个请求插入时撞上约束，事务被标成"只能回滚"，调用方拿到 `UnexpectedRollbackException`。钱没错，但 store 会以为"扣款失败"。
- 唯一约束现在还在，是兜底。

### 2.7 Outbox：资金操作只有一个入口（规则 1、规则 2）
- 下单、Pay Now、取消、丢件、补偿、退款接口，全部**只往 `payment_outbox` 写一条 PAY 或 REFUND 任务**，和业务改动在同一个事务里：[OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java)
- 全系统只有 `OutboxProcessor` 调 bank：[OutboxProcessor.java:171](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L171)。ArchUnit 测试保证以后也没人能绕过：[BankAccessArchitectureTest.java:42](../../backend/storeService/src/test/java/comp5348/storeservice/architecture/BankAccessArchitectureTest.java#L42)
- 好处：重试、超时、幂等键只需要在一个地方做对。代价：支付结果是异步的，前端要显示"支付处理中"。

### 2.8 `@Transactional` 的范围：为什么拆掉了大事务
- 旧的 `processOutbox()` 整个方法是一个 `@Transactional`：一轮里所有任务、包括等 bank 回答的那 5 秒，都在同一个事务里。
- 新的做法：`processOutbox()` 不加事务。每一步调 `PaymentService` 的一个 `@Transactional` 方法（另一个 bean，所以经过 Spring 代理，事务生效），每个事务都很短；**调 bank 的时候手里没有任何锁**。
- `@Transactional` 是 AOP 代理：只有"从外面调进来"的方法调用才会开事务，同一个类里自己调自己（self-invocation）不会。所以事务方法放在 `PaymentService`，由 `OutboxProcessor` 调用。
- 非资金类的旧事件（建配送单、发邮件、补偿）用 `TransactionTemplate`（编程式事务）包起来，每条一个事务：[OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124)

### 2.9 注入 `Clock`
- 代码问"现在几点"时用 `LocalDateTime.now(clock)`，`clock` 是一个 Spring bean：[ClockConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ClockConfig.java)
- 测试里换成可以手动拨的 `MutableClock`，"过 10 分钟""过 15 分钟"不用真等：[MutableClock.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/MutableClock.java)

### 2.10 告警 = 带固定标记的日志
- `ALERT payment_unknown …`、`ALERT refund_unknown …`、`ALERT refund_rejected …`：[PaymentService.java:262](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L262)、[:337](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L337)
- 运维按这个标记搜日志或配日志告警。测试用 Spring Boot 的 `OutputCaptureExtension` 抓控制台输出来断言。告警表留到以后。

### 2.11 测试工具（这一步新用到的）
| 工具 | 用来做什么 |
|---|---|
| Testcontainers + `@ServiceConnection` | 在 Docker 里起一次性的 Postgres；Spring 自动连它，不碰本机 webuser 库 |
| WireMock | 假 bank：`withFixedDelay` 制造超时、`Fault.CONNECTION_RESET_BY_PEER` 制造"没回答"、Scenario 制造"前 3 次超时，第 4 次成功" |
| ArchUnit | 读编译好的 class，检查"谁调用了谁" |
| `OutputCaptureExtension` | 抓日志，断言告警打出来了 |
| `CountDownLatch` | 发令枪：让多个线程同时开跑，制造并发 |
| 不触发的 `TaskScheduler` | 测试里 `@Scheduled` 任务不在后台自己跑，测试自己调 `processOutbox()` |
| React Testing Library + jest 假时钟 | 前端：点 Pay Now → 看到"处理中" → 拨 3 秒 → 自动刷新出 SUCCESS |

---

## 3. 请求路径导读

### 3.1 下单 → 扣款
```
POST /api/store/orders/create-with-payment
 └ StoreController → OrderProductService.createOrderWithPayment       （一个事务）
     ├ OrderService.createOrder            锁库存、建订单（不再写 PAY 任务）
     └ PaymentService.createPayment        建 Payment(attempt=1) + 写唯一一条 PAY 任务，键 PAY-{id}-1
                                                       ↓ 事务提交
OutboxProcessor.processOutbox()   每 5 秒，无事务
 └ runPayTask
     ├ PaymentService.startPayAttempt     （事务）锁 Payment 行；还值得发吗？retryCount + 1
     ├ BankAdapter.transfer               （无事务、无锁）HTTP POST /api/bank/transfer {idempotencyKey,…}
     │    └ bank: BankService.transfer    （事务）查键 → 按账号顺序锁两个账户 → 再查键 → 扣钱 → 记交易
     └ 按结果：
         SUCCEEDED → onPaySucceeded       （事务）锁订单行 → 锁 Payment 行 →
                                              订单待支付：PAID + 写 PAYMENT_SUCCESS（建配送单）
                                              订单已取消：REFUND_PENDING + 写 REFUND 任务
         REJECTED  → onPayRejected        FAILED，订单不动（等 Pay Now）
         UNKNOWN   → 同一个键再试，最多 5 次 → onPayStillUnknown：PAY_UNKNOWN + ALERT + 10 分钟后再试
```

### 3.2 取消已支付的订单 → 退款
```
POST /api/store/orders/{id}/cancel
 └ OrderService.cancelOrder               （一个事务）锁订单行 → 释放库存 → CANCELLED
     └ PaymentService.requestRefund       锁 Payment 行 → SUCCESS 才写 REFUND 任务（REFUND-{paymentId}）
OutboxProcessor → runRefundTask → BankAdapter.refund {idempotencyKey, originalTransactionId, reason}
 └ bank: BankService.refund               查键 → 锁两个账户 → 再查键 → 这笔转账退过没有 → 退钱
```

### 3.3 Pay Now
```
POST /api/store/orders/{id}/retry-payment
 └ OrderProductService.retryOrderPayment  订单是不是当前用户的？还在待支付吗？
     └ PaymentService.payNow              锁 Payment 行：
                                              FAILED → PENDING、attempt+1、写新 PAY 任务
                                              PENDING / PAY_UNKNOWN → 什么都不改
                                              SUCCESS → 已支付
前端 OrderDetailPage：显示"支付处理中"，每 3 秒读一次，直到 SUCCESS / FAILED / PAY_UNKNOWN
```

### 3.4 证据：一次真实下单的日志
真的 bank + 真的 store（临时 Postgres，delivery 故意不开，所以会走到补偿退款）：
```
15:28:22.698 OutboxService       PAY task written: taskId=1, orderId=1, key=PAY-1-1
15:28:25.567 BankAdapter         Calling bank transfer: key=PAY-1-1, amount=6999.00
15:28:25.628 BankService         Processing transfer: key=PAY-1-1, from=ACC-1792384095982, to=STORE_ACCOUNT_001
15:28:25.684 BankService         Transfer successful: txnId=1, key=PAY-1-1
15:28:25.713 BankAdapter         Bank transfer result: key=PAY-1-1, outcome=SUCCEEDED, txnId=1
15:28:25.732 PaymentService      Payment successful: orderId=1, key=PAY-1-1, bankTxnId=1
15:28:32.805 DeliveryAdapter     Delivery request failed after 3 attempts for orderId=1
15:29:02.891 OutboxProcessor     DELIVERY_FAILED event for orderId 1 has waited 30s. Triggering compensation (refund).
15:29:02.921 OutboxService       REFUND task written: taskId=4, orderId=1, key=REFUND-1
15:29:07.958 BankAdapter         Calling bank refund: key=REFUND-1, originalTransactionId=1
15:29:08.004 BankService         Refund successful: refundTxnId=2, key=REFUND-1, originalTxnId=1
15:29:08.020 PaymentService      Refund successful: orderId=1, key=REFUND-1, refundTxnId=2
```
之后手动把 `PAY-1-1` 再发给 bank 一次：回答 `"Transaction already processed successfully"`，交易号还是 1，余额不变。

---

## 4. 为什么这样用

| 选择 | 替代方案 | 为什么选它 / 代价 |
|---|---|---|
| bank 账户行用**悲观锁**（Christy 定） | 乐观锁 `@Version` | 商店账户是热点行：每一笔付款都要改它。乐观锁下并发的付款大多会冲突、重试。锁的范围很小（只有两次更新，没有外部调用），排队的代价低 |
| 锁完**再查一次键** 🤖 | 撞唯一约束后 catch 异常、新开事务读回第一次结果 | 锁已经让同键请求排队了，再查一次最简单；catch 方案要处理"事务已标记回滚"，容易写错。唯一约束保留当兜底 |
| 快速重试**在同一轮里连续做** 🤖 | 每轮（5 秒）试一次 | 测试和用户都更快拿到结果；代价：5 次都超时时这一轮要卡 25 秒（5 × 5 秒超时），别的任务要等 |
| `BankAdapter` 里**不再重试** 🤖 | 保留 adapter 里的 3 次重试 | 两层重试会相乘（3 × 5 = 15 次），而且 adapter 不知道键和状态。重试只在 OutboxProcessor 一处做 |
| 拆掉 `processOutbox` 的大事务 🤖 | 保留一个大事务 | 大事务在等 bank 时一直占着连接和锁；#7 里取消订单会被卡住，扣款结果事务还可能读到旧的订单状态 |
| 订单 → Payment 的加锁顺序 🤖 | 先 Payment 后订单 | 取消订单本来就先读订单，顺着它最自然；关键是全系统统一 |
| 从没发出过请求（`retryCount = 0`）且订单已取消 → 直接 FAILED，不调 bank 🤖 | 照样去扣款，再退款 | 没发过请求就一定没扣钱，不用白扣一次再退。计数在调 bank **之前**单独提交，所以 0 确实代表"从没发过" |
| 400 + `success=false` 才算拒绝，其他 4xx 也算"不知道" 🤖 | 所有 4xx 都算拒绝 | 404、401 这类说明的是配置坏了，不是 bank 的业务判断；当成"不知道"会重试并告警，比悄悄标 FAILED 安全 |
| bank 被拒绝的退款**不记录** 🤖 | 像转账一样记一条 FAILED | 拒绝退款要人工处理；修好原因后同一个键还能再退。被拒绝的转账照旧记录，因为 Pay Now 会换新键 |
| 退款也按"原交易"去重 🤖 | 只按键去重 | 防止同一笔钱用不同的键退两次（比如人工补退时写错键） |
| Pay Now 检查订单属于当前用户 🤖 | 不检查 | 旧代码是用**调用者**的账户付别人的订单。现在扣的是订单主人的账户，只有主人能触发 |
| 去掉 Pay Now 前的余额预检查 🤖 | 保留 | 预检查和真正扣款之间余额会变，不可靠；bank 的拒绝原因会显示在页面上 |
| bank 继续接受 `transactionRef`、继续返回 `success` 🤖 | 只用新字段 | 前端 Add Money 页面（不在这一步的范围里）还在用旧字段，不能弄坏它 |
| 告警 = 固定标记日志（Christy 定） | 告警表 + 管理页 | 先能搜、能配日志告警；告警表留到以后 |
| 前端在 PENDING / PAY_UNKNOWN 时隐藏 Pay Now；PAY_UNKNOWN 不轮询 🤖 | 一直显示按钮 / 一直轮询 | 按钮点了也不会做任何事，反而让用户以为没反应；结果未知时后端 10 分钟才重试一次，前端每 3 秒刷没有意义 |

**以后可以做**：
- **多个 store 实例**：现在假设只有一个 `OutboxProcessor` 在跑。两个实例可能同时处理同一条任务；钱因为幂等键不会错，但任务状态可能被写两次。用 `SELECT … FOR UPDATE SKIP LOCKED` 领任务，或者像 ADR-06 那样用 `pg_try_advisory_xact_lock`。
- **告警表**：像 delivery 的 DLQ 告警那样存表、在管理页显示、可以标记"已处理"。
- **超时卡住整轮**：把慢任务放到单独的线程池，或者每轮只做一次尝试。
- 第 6 步换 gRPC 时，`BankAdapter.post` 的三分类改成看 gRPC 状态码（`DEADLINE_EXCEEDED`、`UNAVAILABLE` → 不知道）。

---

## 5. 八股在这里

**结论**：这一步**有**真正需要的优化：bank 的账户行原来没有锁，并发转账会丢扣款（#16），必须加悲观锁。其他地方都**不需要**更重的工具：不需要 SERIALIZABLE、不需要 Redis 分布式锁、不需要 Java 里的 `synchronized`。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 E3 乐观锁 vs 悲观锁 | [BankAccountRepository.java:28](../../backend/bankService/src/main/java/com/comp5348/bank/repository/BankAccountRepository.java#L28) | **选了悲观锁**：商店账户是热点行，冲突是常态，乐观锁会大量失败重试；锁的范围很短。**什么时候换乐观锁**：冲突少、或者"读"和"写"隔着用户操作（比如管理员编辑商品，保留 `@Version`）。**哪里不需要**：只读的余额查询不加锁 |
| 📒 E3 追问 synchronized | [OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124) | 两个标签页是两个 HTTP 请求，可能落在不同实例上；`synchronized` 只管一个 JVM。锁必须放在大家共享的数据库里 |
| 📒 B6 死锁 | [BankService.java:187](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L187) | 按账号排序加锁，破坏"循环等待"。store 统一"先订单、后 Payment"。**不需要** tryLock + 超时：顺序固定就不会形成环 |
| 📒 E4 隔离级别 | [BankService.java:64](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java#L64) | 保持 Postgres 默认的 READ COMMITTED：锁住行之后的下一条 SELECT 能看到别人已提交的数据，所以"锁完再查一次键"成立。**不需要**把全局调成 SERIALIZABLE |
| 📒 F3 幂等 | [PaymentService.java:76](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L76) | 笔记里说"唯一约束拦住第二次插入"，这一步发现**光有它不够**：第二个请求拿到的是错误，调用方会以为失败（#3 红灯）。所以再加"锁 + 再查一次"，让重复请求拿到第一次的结果 |
| 📒 F4 Outbox | [OutboxService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OutboxService.java) | 退款也变成任务，和取消在同一个事务里写；bank 用幂等键处理"任务被执行两次"。笔记里的 SKIP LOCKED（多实例）这一步**还没做**，现在单实例 |
| 📒 F2 resilience | [BankAdapter.java:91](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L91) | 超时 → 同键重试 → 结果未知 → 低频重试 + 告警。去掉了 adapter 里的重试，避免两层重试相乘 |
| 📒 F5 Saga | [CompensationService.java:74](../../backend/storeService/src/main/java/comp5348/storeservice/service/CompensationService.java#L74) | 补偿（建配送单失败 → 退款）现在只写 REFUND 任务，补偿本身也能重试 |
| 📒 D4 @Transactional | [OutboxProcessor.java:124](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L124) | 去掉大事务；事务方法放在另一个 bean（PaymentService），避开 self-invocation 失效 |
| 📒 C3 监控 | [PaymentService.java:262](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L262) | 告警先用固定标记日志，测试会检查它；告警表留到以后 |
| 📒 G4 Testcontainers | [StoreIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/StoreIntegrationTest.java) | 行锁、唯一约束、READ COMMITTED 的行为都要真 Postgres 才测得准 |
| ➕ HTTP 4xx / 5xx / 超时怎么分 | [BankAdapter.java:98](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L98) | 对方回答了"不行"才是失败；没回答、回答看不懂都是"不知道" |
| ➕ 注入 Clock | [ClockConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ClockConfig.java) | 和时间有关的逻辑都能测，不用 sleep |
| ➕ Hibernate 一级缓存 + FOR UPDATE | [PaymentService.java:159](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java#L159) | 同一个事务里如果已经读过这个实体，再用 FOR UPDATE 查，拿到的可能是缓存里的旧对象。所以每个事务**第一次读就加锁** |

**英文场景锚点**：
- *"The store account is a hot row. Every payment touches it. So I lock it, briefly, instead of letting most writers fail and retry."*
- *"A unique constraint stops the second charge, but the second caller gets an error. I lock first, then check the key again, so it gets the first result."*
- *"There's no network call while a lock is held. Locks are for a few updates, not for waiting on the bank."*

---

## 6. 面试追问链

1. **重试的时候怎么保证不扣两次？** → 幂等键，写任务时就定下来，每次重试都用同一个；bank 按键去重。
2. **超时以后呢？标成失败让用户再付？** → 不行。超时 = 不知道。同一个键重试 5 次，还不知道就 PAY_UNKNOWN + 告警，每 10 分钟再试。只有 bank 明确拒绝才算失败，这时用户 Pay Now 才换新键（attempt + 1）。
3. **同一个键的两个请求同时到 bank 呢？** → 两个都查不到键。都去锁账户行，第二个等第一个提交；拿到锁后再查一次键，看到了，返回第一次的结果。唯一约束是兜底。
4. **为什么用悲观锁，不用乐观锁？** → 商店账户每笔付款都改，冲突是常态；锁的范围只有两次更新，没有网络调用。按账号排序加锁防死锁。
5. **锁住的时候调 bank 会怎样？** → 一个慢请求卡住所有人，连接池也会被占满。所以 `OutboxProcessor` 不加大事务，调 bank 时手里没有任何锁；结果回来再开一个短事务、锁订单行判断。
6. （更深）**多个 store 实例同时跑 OutboxProcessor 呢？** → 现在没做。钱不会错（幂等键），但任务会被处理两次。下一步用 `FOR UPDATE SKIP LOCKED` 领任务。

---

## 7. 英文说法

> Payments go through one place. The API only writes a task, and one processor calls the bank with a fixed key, like PAY-12-1.
> A timeout means unknown, not failed. I retry with the same key. After five tries I mark it unknown, alert, and retry every ten minutes.
> On the bank side, I lock the two account rows in a fixed order, then check the key again. That fixed a lost update I reproduced with ten concurrent transfers.

---

## 8. 资料

- 幂等键：[Stripe · Idempotent requests](https://docs.stripe.com/api/idempotent_requests)（同一个键重试返回第一次保存的结果）
- Outbox：[microservices.io · Transactional outbox](https://microservices.io/patterns/data/transactional-outbox.html)
- 行锁和死锁：[PostgreSQL · 13.3 Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)（"按一致的顺序加锁"是防死锁的最好办法）
- READ COMMITTED 下 FOR UPDATE 等锁后看到的是新版本：[PostgreSQL · 13.2 Transaction Isolation](https://www.postgresql.org/docs/current/transaction-iso.html)
- `@Lock`：[Spring Data JPA · Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)
- self-invocation 不开事务：[Spring · Using @Transactional](https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html)
- `TransactionTemplate`：[Spring · Programmatic Transaction Management](https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html)
- `Clock`：[Java 17 · java.time.Clock](https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/time/Clock.html)（官方建议注入 Clock，测试时换掉）
- 测试：[Spring Boot · Testcontainers](https://docs.spring.io/spring-boot/reference/testing/testcontainers.html)、[Spring Boot · OutputCaptureExtension](https://docs.spring.io/spring-boot/reference/testing/test-utilities.html#testing.utilities.output-capture)、[WireMock · Simulating Faults](https://wiremock.org/docs/simulating-faults/)、[WireMock · Stateful Behaviour](https://wiremock.org/docs/stateful-behaviour/)、[ArchUnit User Guide](https://www.archunit.org/userguide/html/000_Index.html)

---

## 9. 自测题

1. 用户点了 Pay Now，Payment 是 PAY_UNKNOWN。为什么不能给他换一个新键再扣一次？
<details><summary>答案</summary>PAY_UNKNOWN 的意思是不知道上一次钱动没动。如果 bank 其实已经扣了，新键会被当成一笔新的转账，就扣了两次。只有 FAILED（bank 明确拒绝，钱肯定没动）才能换新键。</details>

2. 同一个键的两个转账请求同时到 bank。旧代码钱是对的，为什么 #3 还是红？
<details><summary>答案</summary>唯一约束挡住了第二次插入，所以钱只扣了一次；但第二个请求的事务被标成只能回滚，调用方拿到 UnexpectedRollbackException。store 会以为扣款失败，其实钱已经扣了。新代码让第二个请求等锁，锁到手后再查一次键，返回第一次的结果。</details>

3. （只有真懂才答得出）如果把 `OutboxProcessor.processOutbox()` 重新加上 `@Transactional`，#7（扣款进行中用户取消）会发生什么？
<details><summary>答案</summary>整个一轮变成一个事务。startPayAttempt 锁了 Payment 行，这个锁要到整轮结束才释放，用户的取消在 requestRefund 那一步会被卡住，直到 bank 回答。而且同一个事务（同一个持久化上下文）里早先读过的实体可能是旧的，onPaySucceeded 看到的订单状态可能不是最新的。拆成短事务，调 bank 时不持有任何锁，就没有这两个问题。</details>

---

## 10. 预计回放时间

**60 分钟**：跑一遍红灯和绿灯（10 分钟）→ 按"安全 → 资源 → 并发 → 错误处理 → 性能"读钱的代码（30 分钟）→ 看 🤖 的选择和八股表（10 分钟）→ 英文说一遍、做自测题（10 分钟）。
