# 05 · store 调 bank 从 HTTP 换成 gRPC：只换协议，钱的规则一条不变

[English](05-grpc-bank-call.md) | **中文**

> 对应 commit：`b83b3ff test`（安全网，HTTP 上绿）→ `840199a test(red)` → `427c3e0 feat`
> 预计回放时间：**30 分钟**
> 失败场景表（31 行）在 Obsidian：`开发/电商项目/18-第2波B-gRPC-失败场景表.md`。这份文档讲**这一步学到的概念**。

---

## 1. 问题

**设计（ADR-10）要求服务之间用 gRPC。但扣款这条路上，第 1 波刚修好"超时不等于失败"。换协议时只要把一个 gRPC 错误码映射错，就会重新出现扣两次钱。**

这一步属于"换实现、行为不变"：没有 bug 要复现，测试是安全网。分三步走：

| 步骤 | commit | 测试状态 |
|---|---|---|
| 1. 先补 6 条和协议无关的测试，在**旧的 HTTP 实现**上跑绿 | `b83b3ff` | store 28 个全绿 |
| 2. 假 bank 从 WireMock（HTTP）换成真的 gRPC 服务器，加 gRPC 专属测试 | `840199a` | store 37 红、bank 8 红：store 还在走 HTTP，bank 不监听 gRPC |
| 3. 换实现 | `427c3e0` | store 46、bank 20 全绿 |

最能说明问题的三条测试：

| 会出什么错 | 测试 |
|---|---|
| bank 内部出错被当成"拒绝" → Payment FAILED → Pay Now 换新键 → 扣两次 | [BankCallIntegrationTest.java:37](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankCallIntegrationTest.java#L37)、[BankGrpcIntegrationTest.java:206](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L206) |
| deadline 设在启动时 → 5 秒后所有付款都超时 | [BankCallIntegrationTest.java:78](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankCallIntegrationTest.java#L78) |
| store 放弃等待，bank 其实已经扣了 | [BankGrpcIntegrationTest.java:241](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L241) |

---

## 2. 是什么（框架层）

### 2.1 gRPC 和 `.proto`
**大白话**：先在 `.proto` 文件里写好"有哪些方法、传什么字段"，工具按它生成两边的代码。客户端调 `bank.transfer(request)`，看起来像本地方法，背后是一次网络调用（HTTP/2 + protobuf 二进制）。
- 契约：[bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto)（第 0 波就定好了，这一步没改）。
- `contracts` 模块用 `com.google.protobuf` Gradle 插件生成 `BankServiceGrpc`、`TransferRequest` 等类。store 和 bank 这一步才加上 `implementation project(':contracts')`。
- 生成的 `BankServiceGrpc.BankServiceImplBase`：服务端继承它，重写 `transfer` / `refund`。`BankServiceGrpc.newBlockingStub(channel)`：客户端，调用时线程阻塞，等到回答或者出错。

### 2.2 一次调用的三种结局 → `BankCallResult`
- **状态 OK + `outcome`**：bank 的回答。`SUCCEEDED` → 成功，`REJECTED` → 确定失败。
- **gRPC 错误状态**（`StatusRuntimeException`，unchecked）：`DEADLINE_EXCEEDED`、`UNAVAILABLE`、`INTERNAL`……**全部** → `UNKNOWN`。
- **状态 OK，但 `outcome` 没设**：→ `UNKNOWN`（新行为，见 2.5）。
- 代码：[BankAdapter.java:134](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L134)（`fromOutcome`）、[BankAdapter.java:147](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L147)（`unknown`）。
- 为什么连 `UNIMPLEMENTED`、`INVALID_ARGUMENT` 这种"听起来肯定没成功"的码也算未知：判成失败的代价是可能扣两次钱，判成未知的代价只是同键多试几次（安全）。宁可后者。测试把所有 16 个错误码都跑了一遍：[BankGrpcMappingIntegrationTest.java:43](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankGrpcMappingIntegrationTest.java#L43)
- 官方文档对 `DEADLINE_EXCEEDED` 也是这么写的：会改状态的操作，即使已经成功了也可能返回这个码。

### 2.3 deadline（截止时间）≠ 超时时长
**大白话**：HTTP 的超时是"等多久"，gRPC 的 deadline 是"到几点为止"。
- `stub.withDeadlineAfter(5, SECONDS)` 算的是"从**现在**起 5 秒"，生成的是一个绝对时间点。
- 所以它必须**每次调用前现设**：[BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80)。如果在构造函数里设一次，启动 5 秒以后每一次调用都会立刻 `DEADLINE_EXCEEDED`。我做过改坏检查：改成设在 stub 上，`deadlineIsPerCall` 立刻变红。
- deadline 到了，store 不再等，但**bank 不会自动停**（官方文档：停止服务端的工作是服务端程序自己的责任）。bank 可能照常扣款、提交。这就是"超时不等于失败"，测试见 1 节第三条。
- 时长和以前一样：`bank.grpc.deadline-ms=5000`。以前 HTTP 是"连接 5 秒 + 读 5 秒"，现在一共 5 秒。

### 2.4 Channel：一条长连接，和它的重连
- `ManagedChannel` 是到 bank 的连接（HTTP/2，一条连接上可以同时跑很多调用）。整个 store 只建一个，所有调用共用：[BankAdapter.java:63](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L63)。`@PreDestroy` 关机时关掉。
- `usePlaintext()`：不用 TLS。容器网络内部，和原来的 REST 调用一样。
- **断线重连**：连接断了，gRPC 按退避时间（大约 1 秒、1.6 秒、2.6 秒……）重连。默认（fail-fast）在等重连的时候，新调用**立刻失败**。
- `withWaitForReady()`：连不上时把调用排队，等到连接好了再发，最多等到 deadline。Christy 定的：加上。
  - 实测（`bankBriefRestart_paymentStillCompletes`，bank 停 1.5 秒）：不加的时候，先白费 2–3 次 UNKNOWN，再成功；加了以后，第一次调用就成功。
  - 测试第一版只断言"最后付成功了"，去掉 waitForReady 也是绿的。审查（#5）之后加了一条：这个键的日志里不能出现"结果未知"。只数假 bank 收到几次不够：fail-fast 的调用根本到不了 bank。改坏检查 3 次都红（有一次直接掉进 PAY_UNKNOWN）。
  - 代价：bank 长时间挂掉时，每次调用都要等满 5 秒，一个 PAY 任务 5 次大约 27 秒（和 09 里的 A#5 同一类问题）。
- `disableRetry()`：gRPC 自带重试，可以按配置自动重发。这里关掉，让 OutboxProcessor 成为唯一一层重试（5 次快速，然后每 10 分钟）。测试 `noAnswer_bankSeesExactly5CallsPerRound` 断言一轮正好 5 次。
- **关机时的中断**（审查 #2，红 `eebc1bc` → 绿）：store 关机时 Spring 会中断定时任务线程。HTTP 的 `HttpURLConnection` 不理中断；gRPC 的 blocking stub 会理，正在等的调用立刻变成 `CANCELLED`（= 未知）。中断标志还在，后面的 `Thread.sleep` 和调用也立刻返回 → 5 次快速重试几毫秒烧完 → PAY_UNKNOWN、一条假告警、重启后等 10 分钟。修法：每次得到"未知"后先看线程是不是被中断了，是就停手，什么都不标记（[OutboxProcessor.java:235](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L235)）。任务还是 PENDING、键不变，重启后第一轮（5 秒）就用同一个键补上。测试 `interruptedDuringCall_doesNotBurnFastRetries`。
  - 没做的：让这次调用不响应中断、等到 bank 回答（像 HTTP 那样）。现在的做法放弃了在途那次的结果，靠重启后同键重试拿回来，钱一样安全。Spring 关机时到底是不是 `shutdownNow()` 中断线程，**待确认**（测试里是手动中断的）。

### 2.5 proto3 的默认值：没有 null
- proto3 的字段没有"没设"这一说：字符串默认 `""`，枚举默认是编号 0 的那个值（这里是 `OUTCOME_UNSPECIFIED`）。
- 三个后果：
  1. **生成的 setter 不收 null**，`setReason(null)` 直接抛 `NullPointerException`。适配器先把 null 转成 `""`：[BankAdapter.java:158](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L158)。不转的话，异常飞出适配器，OutboxProcessor 只记一条日志，任务每 5 秒重试，**永远不进 PAY_UNKNOWN、也不告警**。
  2. 成功但流水号是 `""` → 存成 null，和 HTTP 版一样。
  3. bank 忘了写 `outcome` → 收到的是 0 = `OUTCOME_UNSPECIFIED`。旧的 HTTP 版遇到"没写 success"当成**拒绝**；现在当成**未知**（Christy 2026-10-20 定）。
- 金额是字符串（bank.proto 规定，不用 float）。`BigDecimal.toString()` 遇到 `1E+2` 会原样输出科学计数法，所以用 `toPlainString()`：[BankAdapter.java:153](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L153)

### 2.6 bank 这边：业务的"不"和程序出错要分开
- [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java)：继承生成的基类，调**原来的** `BankService`。幂等、行锁、键冲突检查一行没动，REST 和 gRPC 走同一套逻辑。
- `BankService` 回 `success=false` → OK + `REJECTED` + reason。
- 金额字符串解析不了 → 也是 OK + `REJECTED`（[BankGrpcService.java:45](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L45)）。不用 `INVALID_ARGUMENT`：store 会当成未知，每 10 分钟重试一次，永远不会成功。
- `BankService` 抛异常 → `INTERNAL`（[BankGrpcService.java:92](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L92)），细节只写 bank 自己的日志，不把 SQL、表名发给调用方。**绝不 catch 成 REJECTED。**
- 回答发不出去（store 已经放弃了）→ 忽略。钱已经提交，store 用同一个键重试会拿到同一个结果：[BankGrpcService.java:102](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java#L102)

### 2.7 `SmartLifecycle`：gRPC 服务器什么时候启动、什么时候关
- Spring Boot 自己会起 Tomcat（REST），但不会起 gRPC 服务器。[GrpcServerLifecycle.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java) 实现 `SmartLifecycle`：
  - `start()`：在所有 bean（`BankService`、数据库连接池）都准备好之后才调。端口被占就抛异常，bank 直接启动失败，不会出现"bank 起来了但没有 gRPC"。
  - `stop()`：在那些 bean 销毁**之前**调。先 `shutdown()` 不收新调用，给在途的调用 5 秒，再 `shutdownNow()`。
- 端口：`bank.grpc.port=${BANK_GRPC_PORT:9090}`。测试里用 0（让系统随便分一个），不会和本机已经在跑的 bank 撞。

### 2.8 测试替身：真的 gRPC 服务器当假 bank
- [FakeBankGrpc.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/FakeBankGrpc.java)：在本机一个端口上起一个**真的** Netty gRPC 服务器，按剧本回答（"第 1 次慢 6 秒、之后成功"），并记下收到的每个请求。
- 为什么不用 Mockito 直接 mock 掉 stub：那样 deadline、断线、重连都是假的，第 13、16 行这种问题根本测不出来。
- 测试体不知道底下是 HTTP 还是 gRPC：它们只调 `bankTransferSucceeds()`、`transferRequests()` 这些剧本方法。所以同一批测试体先在 HTTP 上绿，换协议后照样绿。
- 每个结果是"未知"的测试，都先断言"bank 真的收到了请求"。否则 store 根本没调到 gRPC bank 时，结果也是未知，测试会**假绿**。

---

## 3. 请求路径导读

一笔扣款（用户已下单，PAY 任务在 `payment_outbox` 里）：

1. `OutboxProcessor.runPayTask` 每 5 秒取任务，调 `bankAdapter.transfer(...)`（这一步**没改**）。
2. `BankAdapter.transfer`：拼 `TransferRequest`（null → `""`，金额 `toPlainString`）→ `oneCall()` 给这一次调用设 5 秒 deadline + waitForReady → 通过 channel 发到 `bank:9090`。
3. bank 的 Netty 收到 → `BankGrpcService.transfer` → 解析金额 → `BankService.transfer`（查键 → 锁两个账户行 → 再查键 → 扣钱 → 提交）。
4. 回 `TransferResponse`（OK + SUCCEEDED + transaction_id）。
5. store：`fromOutcome` → `BankCallResult.succeeded` → OutboxProcessor → `paymentService.onPaySucceeded`（锁订单行，订单 PAID）。
6. 出错时：第 4 步变成错误状态 → `unknown(...)` → OutboxProcessor 同键重试，最多 5 次 → PAY_UNKNOWN + `ALERT payment_unknown`。

开户、按邮箱查账户：proto 里没有，继续走 REST（`bank.service.url`）。（`getBalance` 没有调用方，删掉了。）

---

## 4. 为什么这样用

| 选择 | 替代方案 | 取舍 |
|---|---|---|
| 🤖 朴素的 grpc-java + 自己写 `SmartLifecycle`（Christy 同意） | Spring 官方的 spring-grpc 自动配置 | 多写约 60 行，但每一行都讲得清；spring-grpc 代码更少，背后启动了什么看不见 |
| 所有错误码 → 未知 | 按码细分（比如 `UNIMPLEMENTED` 当失败） | 细分能早一点发现配置错误，但只要分错一个码就可能扣两次。同键重试是安全的，所以不细分 |
| `waitForReady`（Christy 定） | 默认 fail-fast | bank 短暂重启时不浪费重试；bank 长时间挂掉时每次调用等满 5 秒 |
| 🤖 `disableRetry()` | 用 gRPC 的重试策略 | 只有一层重试，次数和时间好算；gRPC 自己的"请求根本没发出去时重发"（透明重试）也一起关了 |
| 🤖 bank 出错回 `INTERNAL`，描述只写 "bank internal error" | 把异常信息发回去 | 调试时少了点信息（要看 bank 日志），但不往外泄露 SQL 和表名 |
| 只有 transfer / refund 走 gRPC | 开户这些也搬到 gRPC | proto 里没有这些方法，契约不许改；它们不碰钱，HTTP 够用 |

---

## 5. 八股在这里

**结论**：这一步没有需要"换成优化版"的地方。值得讲的是**超时、重试、幂等怎么配合**，以及 gRPC 和 REST 的取舍。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 F2 怎么让系统更 resilient | [BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80) | 超时 + 重试 + 幂等三件套。**每次调用**有 deadline；重试只有一层（OutboxProcessor）。不需要再加熔断器：只有一个调用方，而且已经有"5 次后改成每 10 分钟一次"的退避 |
| 📒 F3 幂等 | [BankGrpcIntegrationTest.java:241](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java#L241) | 换协议后幂等还在：store 放弃等待、bank 照常提交，同键重试拿回同一个流水号。协议变了，键的规则没变 |
| 📒 F7 REST API 设计 | [BankController.java](../../backend/bankService/src/main/java/com/comp5348/bank/controller/BankController.java) | 对浏览器保留 REST（通用、好调试），服务之间用 gRPC（契约严格、代码生成）。不是所有接口都要换 |
| 📒 A4 checked vs unchecked | [BankAdapter.java:88](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L88) | `StatusRuntimeException` 是 unchecked，编译器不提醒你 catch。适配器在边界上把它变成 `UNKNOWN` 值返回，上层不用处理异常 |
| 📒 D2 singleton bean 线程安全吗 | [BankAdapter.java:63](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L63) | `BankAdapter` 是单例，channel 和 stub 被所有线程共用。可以，因为它们是线程安全、不可变的：`withDeadlineAfter` 返回一个**新** stub，不改原来的 |
| 📒 D1 IoC / bean 生命周期 | [GrpcServerLifecycle.java:37](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java#L37) | `SmartLifecycle` 让 Spring 决定 gRPC 服务器什么时候启动和关闭（依赖准备好之后启动、销毁之前关闭）。只是一个普通 bean 的话，不需要它，`@PostConstruct` 就够 |
| 📒 G4 Testcontainers / 不用假替身 | [FakeBankGrpc.java](../../backend/storeService/src/test/java/comp5348/storeservice/support/FakeBankGrpc.java) | 和"不用 H2"同一个道理：要测网络行为（deadline、断线），替身就得是真的服务器，不能是 Mockito |
| ➕ gRPC deadline vs timeout | [BankAdapter.java:80](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L80) | deadline 是绝对时间点，每次调用现设。只有一次调用、不往下游传的时候，两者区别不大；链式调用（A→B→C）时 deadline 可以往下传，timeout 不能 |
| ➕ gRPC 状态码怎么映射 | [BankAdapter.java:147](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L147) | 会改状态的调用：错误码一律当"未知"。只读调用（比如查余额）可以按码细分，因为重试和误判都不花钱 |
| ➕ proto3 默认值 | [BankAdapter.java:158](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java#L158) | 没有 null，`""` 和 0 就是"没设"。枚举的 0 号值留给 `UNSPECIFIED`，读到它要当成"不知道"，不能当成某个业务值 |

**英文场景锚点**：
- "Switching to gRPC, the risky part wasn't the plumbing. It was mapping status codes. Every error code maps to 'unknown', because a wrong 'failed' can mean charging twice."
- "A gRPC deadline is a point in time, not a duration. If you set it once on the stub at startup, every call after five seconds fails. I have a test that waits six seconds and pays again."

---

## 6. 面试追问链

1. **为什么 store 调 bank 用 gRPC？** → 契约在 `.proto` 里，两边代码自动生成，字段写错编译就不过；HTTP/2 一条连接跑多个调用。浏览器那边保留 REST。
2. **超时以后怎么办？** → 返回 `DEADLINE_EXCEEDED`，当成"不知道"，同一个幂等键重试。bank 可能已经扣了，所以不能当失败。
3. **为什么不能把 `UNAVAILABLE` 当成"肯定没发出去"？** → 连不上和连接中途断开都是这个码。中途断开时请求可能已经到了 bank。
4. **deadline 设在哪？** → 每次调用前现设。设在共用的 stub 上，算的是"启动后 5 秒"。
5. **bank 重启时 store 会怎样？** → channel 按退避时间重连；加了 `waitForReady`，调用会等连接好了再发，最多等到 deadline。代价：bank 长时间挂掉时每次等满 5 秒。

---

## 7. 英文说法

- "Store calls bank over gRPC now. Only the protocol changed. Idempotency keys and the retry rules are the same."
- "Only an OK status with an explicit outcome counts as bank's answer. Everything else is unknown, and we retry with the same key."
- "I wrote the safety-net tests on the old HTTP version first. Then I swapped the fake bank for a real gRPC server, and the same test bodies had to pass."

---

## 8. 资料

- gRPC Deadlines：https://grpc.io/docs/guides/deadlines/ （deadline 的含义；服务端要自己停）
- gRPC Status codes：https://grpc.io/docs/guides/status-codes/ （`DEADLINE_EXCEEDED` 时操作可能已经成功）
- gRPC Wait-for-Ready：https://grpc.io/docs/guides/wait-for-ready/
- gRPC Retry：https://grpc.io/docs/guides/retry/ （默认开着、没有默认策略；透明重试；可以整个关掉）
- gRPC Java Basics：https://grpc.io/docs/languages/java/basics/ （ManagedChannel、blocking stub、ServerBuilder）
- proto3 默认值：https://protobuf.dev/programming-guides/proto3/#default
- Spring `Lifecycle` / `SmartLifecycle`：https://docs.spring.io/spring-framework/reference/core/beans/factory-nature.html#beans-factory-lifecycle-processor

---

## 9. 自测题

1. store 收到 `UNIMPLEMENTED`，为什么不直接标 FAILED？
<details><summary>答案</summary>标 FAILED 后用户可以 Pay Now，attempt 加 1，用新键再扣。万一第一次其实到了 bank、扣成了（比如不同版本的 bank 在滚动更新），就扣了两次。当成未知只会同键重试，bank 按键去重，最多扣一次。</details>

2. 把 `bank.withDeadlineAfter(5, SECONDS)` 放进构造函数，会发生什么？哪个测试会抓到？
<details><summary>答案</summary>deadline 是"构造时刻 + 5 秒"这个时间点。5 秒以后的每次调用一开始就已经过了截止时间，立刻 DEADLINE_EXCEEDED，全部变成未知 → PAY_UNKNOWN。`deadlineIsPerCall_callAfterIdleStillSucceeds` 会红（我做过这个改坏检查）。</details>

3. （只有真懂了才答得出）bank 的 gRPC 服务里，如果 catch 所有异常、回 `REJECTED` + 异常信息，看起来更"友好"。错在哪？
<details><summary>答案</summary>异常不是 bank 的决定，钱可能已经动了（比如提交之后、发回答之前出错）。REJECTED 会让 store 标 FAILED，用户 Pay Now 换新键 → 扣两次。异常必须是错误状态（INTERNAL），让 store 当成未知、同键重试。`grpcTransfer_serviceThrows_errorStatus_notRejected` 就是防这个的。</details>

---

## 10. 预计回放时间

30 分钟：跑一次红灯（`840199a`）和绿灯（`427c3e0`），读 `BankAdapter` 的 transfer / fromOutcome / unknown，再读 `BankGrpcService`，最后做自测题。
