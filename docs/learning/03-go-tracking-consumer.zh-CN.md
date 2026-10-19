# 03 · Go tracking：一个"至少一次"的消费者

[English](03-go-tracking-consumer.md) | **中文**

> 对应 commit：`458081c test(red)` 空壳 → `f606749 feat` 最简实现 → `b8d8a06 test(red)` 失败场景 → 六个 `feat`（`e3fd626` 解析、`6229390` 去重、`bea1a1f` 排序、`5058a89` 两种失败、`46b115e` 优雅退出、`8ee1acc` 查询接口）→ `9aec05a` 镜像
> 预计回放时间：**60 分钟**
> 服务本身的说明（接口、配置、怎么跑）见 [trackingService/README.zh-CN.md](../../backend/trackingService/README.zh-CN.md)。这份文档只讲**这一步学到的概念**。

---

## 1. 问题

**Kafka 只保证"至少投递一次"：同一条消息可能来两次，坏消息会一直在那里，两个 topic 之间没有顺序，服务随时可能被停掉。一个"能跑通"的最简消费者在这些情况下会悄悄存错数据或丢数据。**

最简实现（`f606749`）上 29 个测试是红的，最能说明问题的几个：
- 缺字段的事件被当成零值收下：[parse_test.go:64](../../backend/trackingService/internal/event/parse_test.go#L64)
- 崩溃重启后同一条消息存了两行：[failure_test.go:29](../../backend/trackingService/internal/integration/failure_test.go#L29)
- 坏消息被悄悄丢掉，DLT 里什么都没有：[failure_test.go:82](../../backend/trackingService/internal/integration/failure_test.go#L82)
- 数据库连不上时事件直接丢了：[consumer/failure_test.go:82](../../backend/trackingService/internal/consumer/failure_test.go#L82)
- 时间线按到达顺序排，重放一次结果就变了：[failure_test.go:187](../../backend/trackingService/internal/integration/failure_test.go#L187)
- 时间是悉尼时区 `+11:00`，不是事件里写的 UTC：[store/failure_test.go:114](../../backend/trackingService/internal/store/failure_test.go#L114)
- 停止时正在处理的那条被放弃，HTTP 端口没关：[failure_test.go:233](../../backend/trackingService/internal/integration/failure_test.go#L233)、[:273](../../backend/trackingService/internal/integration/failure_test.go#L273)
- store 挂了却回 401：[httpapi/failure_test.go:66](../../backend/trackingService/internal/httpapi/failure_test.go#L66)

---

## 2. 是什么（框架层）

### 2.1 Go 的 `encoding/json`：缺字段不报错
- `json.Unmarshal` 把 JSON 填进结构体。**JSON 里没有的字段，Go 不报错，保持零值**（字符串是 `""`，`time.Time` 是公元 1 年）。JSON 的 `null` 填进非指针字段也"没有效果、不报错"（官方文档原话）。
- 所以普通结构体分不清"没给"和"给了空值"。修法：先解进**指针字段**的结构体 `wire`，没给或 null 就是 `nil`，再逐个检查：[event.go:68](../../backend/trackingService/internal/event/event.go#L68)、[:80](../../backend/trackingService/internal/event/event.go#L80)
- `json.RawMessage` 收原样的 JSON 文本。JSON 是 `null` 时它拿到的是文字 `null`，所以 payload 检查写成"第一个字符是 `{`"：[event.go:103](../../backend/trackingService/internal/event/event.go#L103)
- 对比 Java：Jackson 缺字段传 null（同样不报错），所以契约模块在 record 的构造器里检查（学习文档 00）。两种语言的坑一样，修的位置不同。
- 反过来：Go 的 `string` 字段收到数字会直接报错，Java 要专门配置才这样。所以 `TestParse_OrderIdAsNumber_Rejected` 在 Go 里从一开始就是绿的。

### 2.2 `google/uuid` 比 Java 宽松
- `uuid.Parse` 还接受 `urn:uuid:…`、`{…}`、不带横线的 32 位十六进制。Java 的 `UUID.fromString` 只收 36 位的标准写法，所以多检查一次长度：[event.go:89](../../backend/trackingService/internal/event/event.go#L89)

### 2.3 消费者组、offset、提交
- **消费者组**：同一个组里的实例分摊分区，一个分区同时只给一个实例。tracking 的组叫 `tracking`。
- **offset** 是消息在分区里的编号；**提交 offset = N** 的意思是"这个组下次从 N 开始读"。Kafka 把它存在内部的 `__consumer_offsets` topic 里。
- **先处理、再提交 = 至少一次**：处理完、还没提交时崩溃，重启后这条会再来一次。Kafka 官方文档在"Message Delivery Semantics"一节说的就是这个。反过来（先提交再处理）就是"至多一次"，崩溃会丢消息。
- tracking 的做法：关掉自动提交（`DisableAutoCommit`），一批消息**全部处理完**再用 `CommitRecords` 手动提交：[consumer.go:65](../../backend/trackingService/internal/consumer/consumer.go#L65)、[:119](../../backend/trackingService/internal/consumer/consumer.go#L119)
- 🔍 读源码发现的：franz-go 的**默认**自动提交只提交"上一次 poll"拿到的消息，正在处理的这一批不提交。所以同步处理时，默认就已经是至少一次，`TestCrashBeforeStore_EventNotLost` 在最简实现上就是绿的。加上 `kgo.GreedyAutoCommit()`（提交所有已拉到的）它才会丢消息。

### 2.4 幂等写入：`uuid` 主键 + `ON CONFLICT DO NOTHING`
- 至少一次 = 一定会有重复。去重放在数据库：`event_id uuid PRIMARY KEY`，插入时 `ON CONFLICT (event_id) DO NOTHING`，重复的那条什么都不改，也不报错：[store.go:55](../../backend/trackingService/internal/store/store.go#L55)、[:80](../../backend/trackingService/internal/store/store.go#L80)
- 为什么不报错：消费者拿到错误会重试，重复消息就会永远卡住这个分区。
- 为什么用 `uuid` 类型不用 `text`：`3F1C…` 和 `3f1c…` 是同一个 UUID，`text` 会当成两个。

### 2.5 rebalance 和 `BlockRebalanceOnPoll`
- **rebalance**：组里的实例变了（新 pod 加入、旧 pod 退出），分区重新分配。
- 风险：实例 A 处理完一批、还没提交，分区就被分给了 B，B 会再处理一遍（靠 2.4 去重兜底）；更糟的是 A 之后再提交，可能提交到已经不属于它的分区。
- `BlockRebalanceOnPoll`：poll 之后，rebalance 要等我们调用 `AllowRebalance` 才进行。tracking 在"处理完 → 提交"之后才放行，所以分区换主人时，已经处理的都已提交：[consumer.go:69](../../backend/trackingService/internal/consumer/consumer.go#L69)、[:123](../../backend/trackingService/internal/consumer/consumer.go#L123)
- 🐛 坑（这次真踩到了）：开了这个选项，`Close()` 会一直等 `AllowRebalance`。停止信号让 poll 提前返回时没人调它，服务就退不出去。重放测试和 app 退出测试抓到了这个回归；官方的出口是 `CloseAllowingRebalance()`：[consumer.go:80](../../backend/trackingService/internal/consumer/consumer.go#L80)

### 2.6 两种失败（规则 6 / ADR-08）
- **消息本身坏了**（解析不了、缺字段、放错 topic）：重试一万次也一样 → 原样写进 `<topic>.DLT`，原因放 header，然后继续：[handler.go:62](../../backend/trackingService/internal/consumer/handler.go#L62)、[dlt.go:27](../../backend/trackingService/internal/consumer/dlt.go#L27)
- **暂时性失败**（数据库连不上、DLT 写不进去）：等一会儿就好 → 原地退避重试，200 毫秒翻倍到 10 秒，不提交、不跳过。这个分区会卡住，但同一个订单的事件不会乱序：[handler.go:87](../../backend/trackingService/internal/consumer/handler.go#L87)
- **第三种：外壳合法，但数据库永远存不进去**（审查补充，失败场景表 #40）：payload 里有 `\u0000`（jsonb 拒收，SQLSTATE `22P05`）、orderId 里有 NUL（`22021`）、payload 里有非法 UTF-8 字节（`22021`）。原来所有 `Insert` 错误都当暂时失败，这种消息会被**无限重试**，整个消费者卡死。现在 `store.Insert` 看 Postgres 的错误码：**22 类（data exception，数据本身不合法）** 包成 `store.ErrRejected`，handler 把它当坏消息送 DLT；连接被拒、超时等其他错误照旧原地重试：[store.go](../../backend/trackingService/internal/store/store.go)（`ErrRejected`、`Insert`）、[handler.go](../../backend/trackingService/internal/consumer/handler.go)（`Handle`、`retry`）。测试：[review_test.go](../../backend/trackingService/internal/integration/review_test.go)（红 `598c78a`）
- **不认识的 eventType** 两种都不是：跳过。以后生产者加了新事件，老的 tracking 不会把它们全扔进 DLT；升级 tracking 后重放就能补回来。
- DLT 的 header 照抄 Spring Kafka（`kafka_dlt-original-topic`、partition 是 4 字节大端整数……），第 2 波的 Java 消费者用默认的 `DeadLetterPublishingRecoverer` 就是同一个格式。⚠️ 但 Spring Kafka 3.3 默认的 DLT **名字**是 `<topic>-dlt`，契约是 `<topic>.DLT`，Java 那边要配置目标 topic。

### 2.7 `context`：取消信号
- `context.Context` 是 Go 传"该停了"的标准方式。`ctx.Done()` 是一个 channel，被取消时关闭；所有可能阻塞的操作（数据库、HTTP、Kafka poll、`select`）都接收 ctx。
- `signal.NotifyContext`：收到 SIGTERM 或 Ctrl+C 时取消 ctx：[main.go:28](../../backend/trackingService/cmd/tracking/main.go#L28)
- 退避等待写成 `select { case <-ctx.Done(): …; case <-time.After(wait): }`，不用 `time.Sleep`：睡着的时候收不到停止信号：[handler.go:105](../../backend/trackingService/internal/consumer/handler.go#L105)
- `context.WithoutCancel(ctx)`：得到一个**不会**随父 ctx 取消的 ctx。手上那条消息用它，停止信号来了也能写完；再用 `context.AfterFunc` 在停止 10 秒后强制取消，防止一直卡着：[consumer.go:84](../../backend/trackingService/internal/consumer/consumer.go#L84)、[:86](../../backend/trackingService/internal/consumer/consumer.go#L86)

### 2.8 优雅退出（graceful shutdown）
- Kubernetes 停 pod：先发 SIGTERM，等 30 秒，还没退出就 SIGKILL。
- 消费者：不再取新消息 → 手上那条写完 → 提交 → 离开消费者组（别的实例立刻接手，不用等会话超时）。
- HTTP：`http.Server.Shutdown` 先关监听端口，再关空闲连接，等正在处理的请求回完：[app.go:127](../../backend/trackingService/internal/app/app.go#L127)。naive 版用的 `http.Serve` 没有这个能力，Run 返回了端口却还开着。
- 证据：`docker stop` 后日志 `shutting down` → `tracking stopped`，退出码 0，用时 0.5 秒。

### 2.9 HTTP：Go 1.22 路由、超时、错误码
- `mux.HandleFunc("GET /api/tracking/orders/{orderId}/timeline", …)`：Go 1.22 起标准库路由支持方法和 `{orderId}` 通配，用 `r.PathValue("orderId")` 读。不需要 Gin。
- **`http.DefaultClient` 没有超时。** store 卡住，我们的每个请求都跟着卡住。所以调 store 用 `Timeout: 2s` 的客户端：[auth.go:37](../../backend/trackingService/internal/auth/auth.go#L37)
- 401 和 503 要分开：store 说"token 无效"是 401（前端让用户重新登录）；store 没回答是 503（稍后再试）。Java 服务把两种都当 401：[auth.go:68](../../backend/trackingService/internal/auth/auth.go#L68)
- 归属检查：OrderPlaced 里的 `userId` 和 token 的用户不一致就 404。"不是你的"和"不存在"回得一模一样，别人没法靠试订单号来探测：[httpapi.go:66](../../backend/trackingService/internal/httpapi/httpapi.go#L66)、[:72](../../backend/trackingService/internal/httpapi/httpapi.go#L72)
- Go 的空切片编码成 JSON 是 `null`，不是 `[]`，所以先 `make([]Entry, 0, n)`：[httpapi.go:83](../../backend/trackingService/internal/httpapi/httpapi.go#L83)

### 2.10 时区：pgx 返回本机时区
- Postgres 的 `timestamptz` 存的是一个时刻，没有"时区"。pgx 读出来时把它放进**本机时区**的 `time.Time`。在悉尼的电脑上格式化就是 `13:01:00+11:00`：时刻没错，但和事件里的 `02:01:00Z` 对不上，前端也会显示乱。修法：读出来马上 `.UTC()`：[store.go:104](../../backend/trackingService/internal/store/store.go#L104)
- 测试里用 `time.Local = time.FixedZone("AEDT", 11*3600)` 把本机时区固定成悉尼，结果才不会因为在哪台电脑上跑而不同。

### 2.11 时间线排序和重放
- 两个 topic 之间没有顺序，到达顺序没有意义。排序用 SQL：`ORDER BY occurred_at, CASE source_topic WHEN 'order-events' THEN 0 ELSE 1 END, kafka_offset`：[store.go:94](../../backend/trackingService/internal/store/store.go#L94)
- 结果只取决于数据本身，不取决于到达顺序，所以**重放**（清空 + 从头读）一定得到同样的时间线。这就是 ADR-11 说的"读模型可以重建"（CQRS 的读端）。

### 2.12 Testcontainers for Go 和"真的崩溃"
- `postgres.Run(...)` / `kafka.Run(...)` 在测试开始时起真的容器，`TestMain` 里起一次，所有测试共用。
- build tag：文件第一行 `//go:build integration`，平时 `go test ./...` 不编译它们。
- **崩溃要在子进程里模拟**：测试把 tracking 编译成程序（`-tags crashtest`），在子进程里跑，到指定位置 `os.Exit(3)`。`os.Exit` 跳过所有 `defer`：不关连接、不离开组、不提交，和断电一样。在测试进程里 `cancel()` 是"体面地退出"，正好把要找的问题掩盖了：[crash_on.go:31](../../backend/trackingService/internal/app/crash_on.go#L31)、[binary_test.go](../../backend/trackingService/internal/integration/binary_test.go)
- Windows 坑：Testcontainers 把 Windows 命名管道挂进辅助容器 Ryuk，Ryuk 起不来。测试入口自动设置 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`：[docker.go](../../backend/trackingService/internal/examples/docker.go)

### 2.13 多阶段构建和 distroless
- 第一阶段 `golang:1.27` 编译，`CGO_ENABLED=0` 得到不依赖 C 库的静态二进制；第二阶段只放这个二进制进 `distroless/static:nonroot`：没有 shell、没有包管理器，以 uid 65532 运行。
- 结果：二进制层 15.7 MB，压缩后 6.6 MB。没有 curl，所以 `HEALTHCHECK` 调程序自己的 `healthcheck` 子命令。

---

## 3. 请求路径导读

**写入路径**

```
Kafka（order-events / delivery-events，key = orderId）
  └─ consumer.Run          PollFetches（BlockRebalanceOnPoll：rebalance 先等着）
       └─ Handler.Handle   对每条消息：
            ├─ event.Parse            坏消息 → kafkaDLT.Publish → <topic>.DLT（重试直到写进去）
            ├─ event.TopicOf          不认识 → 跳过；放错 topic → DLT
            └─ store.Insert           INSERT … ON CONFLICT DO NOTHING（失败 → 退避重试）
       └─ CommitRecords    这一批都处理完了才提交
       └─ AllowRebalance   现在可以 rebalance 了
```

**查询路径**

```
GET /api/tracking/orders/42/timeline
  └─ httpapi            没有 Bearer → 401
       └─ auth.Client   POST store /api/user/validate-token（2 秒超时）→ 401 / 503
       └─ store.Owner   OrderPlaced.userId ≠ 当前用户 → 404
       └─ store.Timeline ORDER BY occurred_at, topic, offset → .UTC()
  └─ 200 [{eventType, source, occurredAt}]
```

---

## 4. 为什么这样用

| 选择 | 替代方案 | 取舍 |
|---|---|---|
| 设计指定：franz-go | confluent-kafka-go（基于 C 的 librdkafka）、segmentio/kafka-go | franz-go 纯 Go，不需要 CGO，静态二进制和 distroless 才能用；API 和 Java 客户端的概念对得上 |
| 🤖 手动提交 + `BlockRebalanceOnPoll` | ① franz-go 默认自动提交（实测也是至少一次）② `AutoCommitMarks` 标记后自动提交 | 手动提交最直观，面试时一句话讲得清"处理完再提交"。代价：处理一批太慢会超过 rebalance 超时被踢出组，所以每条都要快 |
| 设计指定：数据库唯一约束去重 | 内存里记最近的 eventId | 内存去重重启就没了，多实例之间也不共享；数据库唯一约束是唯一靠得住的 |
| 🤖 事件放错 topic → DLT | 照存；或者忽略 | 照存会让 source 显示错；放错说明生产者有 bug，需要人看 |
| 🤖 DLT 写不进去 → 重试、卡住分区 | 记日志后提交（丢掉坏消息） | 宁可卡住也不让坏消息无声消失；代价是 Kafka 整个出问题时这个分区停住 |
| 🤖 数据库永久拒绝：按 SQLSTATE 分类（22 类 → DLT） | 在 `Parse` 阶段就拒绝 NUL、非法 UTF-8 | Parse 只能挡住**已知的**几种输入，以后 Postgres 因为别的数据问题拒绝（比如某个值超出范围），消费者又会卡死；按错误码分类能兜住整类问题。代价：要知道哪些错误码是"永久的"。只选了 22 类（数据本身不合法），没选 23 类（约束冲突，主键重复已经被 `ON CONFLICT` 吃掉了）和 42 类（SQL 写错，是我们的 bug，不该让消息背锅）。Parse 保持和 Java EventCodec 一样的规则 |
| 🤖 优雅退出给 10 秒 | 不限时；或者立刻退出 | Kubernetes 默认 30 秒后 SIGKILL，10 秒留了余量；立刻退出的话，写了一半的事务要靠重投兜底 |
| 🤖 归属检查回 404 不回 403 | 403 | 403 等于告诉对方"这个订单存在"，404 不泄露 |
| 🤖 表由服务启动时建 | golang-migrate 等迁移工具 | 只有一张表，暂时不值得引入工具；表结构要改的时候再加 |
| 🤖 标准库 `net/http`，不用 Gin | Gin、Echo、chi | 只有一个接口；Go 1.22 的路由已经支持方法和路径参数 |

---

## 5. 八股在这里

> 📒 = 《Macquarie-Round1-Java八股》里有，后面是题号。➕ = 笔记里没有（Go 的大多在这里），以后补进笔记。
>
> **结论先说：这一步没有需要做性能优化的地方。** 真正的问题都是正确性：重复、丢失、顺序、退出。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 **F3 幂等** | `ON CONFLICT (event_id) DO NOTHING`（[store.go:80](../../backend/trackingService/internal/store/store.go#L80)） | 消费端的幂等：同一个事件处理两次，结果和一次一样。**不需要分布式锁**：唯一约束本身就是并发安全的，两个实例同时插同一行，数据库只让一个成功 |
| 📒 **F6 Kafka vs RabbitMQ**（分区内有序、同组一个分区一个消费者、重置 offset 重放、提交前崩溃会重复） | 整个 consumer 包 | 四个点在这里都有真代码：key = orderId 保序；rebalance 测试；重放测试；崩溃测试。tracking 用 Kafka 不用 RabbitMQ，正因为要**重放**（ADR-11） |
| 📒 **F9 事件驱动** | tracking 只订阅、不发布 | 新加一个消费者，生产者一行都不用改（ADR-01）。代价是最终一致：刚下的单，时间线可能晚几百毫秒才出现 |
| 📒 **F2 resilience** | 退避重试（[handler.go:87](../../backend/trackingService/internal/consumer/handler.go#L87)）、调 store 的 2 秒超时、503 | 超时 + 退避，**没有熔断器**：调 store 只在用户请求时发生，量很小，超时已经保护了线程。store 被大量请求打挂时，熔断器才值得加 |
| 📒 **G4 Testcontainers vs H2** | store 和 integration 的测试 | 这次正好用到了 H2 做不准的三样：`ON CONFLICT`、`uuid` 类型、`timestamptz` 的时区行为 |
| 📒 **G2 Docker** | [Dockerfile](../../backend/trackingService/Dockerfile) | 多阶段构建：编译工具不进最终镜像；先 `COPY go.mod go.sum` 再 `go mod download`，代码改了不用重新下载依赖 |
| 📒 **D5 错误怎么返回** | `writeError`，数据库错误回 503 不带原文（[httpapi.go:69](../../backend/trackingService/internal/httpapi/httpapi.go#L69)） | 内部错误只进日志。响应里带上 `host=10.0.0.5` 这种信息，等于给攻击者画地图 |
| 📒 **D7 健康检查** | `/healthz`、`/readyz` | 活着（liveness）和就绪（readiness）分开：数据库暂时连不上时 pod 不该被重启，只该暂时不接流量 |
| 📒 **H1 secrets** | 配置全部来自环境变量，镜像里没有密码（`docker image inspect` 看过） | 第 8 步会改成 Kubernetes Secret |
| 📒 **E5 索引** | `(order_id, occurred_at)`（[store.go](../../backend/trackingService/internal/store/store.go)） | 查询总是"某个订单、按时间排"，这个复合索引同时满足过滤和排序。**不需要**再给 event_type 建索引：没有按它查的地方 |
| ➕ **Go：goroutine + channel** | app.Run 里 HTTP 和消费者各一个 goroutine，结果从 channel 回来 | 和 Java 线程比：goroutine 很轻（几 KB 栈），可以随手开；但要想好它**怎么结束**，不然就是泄漏 |
| ➕ **Go：context** | 2.7 节 | 相当于 Java 里"取消 + 超时"的统一做法。规则：会阻塞的函数第一个参数都是 ctx |
| ➕ **Go：零值和指针** | 2.1 节 | Go 的结构体永远有零值，所以"没给"要用指针或单独的标志来表示 |
| ➕ **至少一次 / 至多一次 / 恰好一次** | 2.3 节 | "恰好一次"在跨系统（Kafka → Postgres）时做不到，实际做法就是"至少一次 + 幂等" |

**场景锚点（英文，面试时直接用）**

> "Kafka gives me at-least-once delivery, so duplicates are normal. In the tracking service the event ID is the primary key and the insert does ON CONFLICT DO NOTHING. A redelivered event is simply a no-op."

> "I commit offsets by hand, only after the batch is written, and I block rebalances until that commit. So a partition never moves to another pod with work that's done but not committed."

> "I don't need a circuit breaker there. The store call only happens on user requests, the volume is tiny, and a two-second timeout already stops a slow store from tying up the service."

---

## 6. 面试追问链

**链 A：重复和丢失**
1. Kafka 会丢消息或重复吗？→ 消费端取决于什么时候提交 offset。先处理后提交是至少一次，会重复，不会丢。
2. 你怎么处理重复？→ eventId 做主键，`ON CONFLICT DO NOTHING`。
3. 为什么不在内存里去重？→ 重启就没了，多个实例之间也不共享。
4. 怎么证明真的不会重复？→ 测试在子进程里跑真程序，写完库、提交前 `os.Exit`，重启后断言消息确实又来了一次，而且库里只有一行。
5. 为什么要在子进程里？→ 在测试进程里取消是体面退出，会离开组、会走清理逻辑，真的崩溃不会。

**链 B：坏消息和暂时性失败**
1. 解析失败怎么办？→ 原样进 DLT，带原因，继续下一条。
2. 数据库挂了也进 DLT 吗？→ 不。暂时性失败原地重试，卡住分区，保住顺序。
3. DLT 本身写不进去呢？→ 也当暂时性失败，不提交，宁可卡住也不丢。
4. 卡住的代价？→ 这个分区上所有订单的时间线都延迟；需要告警。

**链 C：优雅退出**
1. Kubernetes 怎么停 pod？→ SIGTERM，默认 30 秒后 SIGKILL。
2. 收到 SIGTERM 你做什么？→ 不取新消息，手上那条写完并提交，离开组；HTTP 处理完正在进行的请求再关端口。
3. 手上那条如果卡在数据库重试呢？→ 退避等待能被 ctx 打断，马上退出，不提交；下一个实例会重新处理它。
4. 为什么要主动离开组？→ 不离开的话，别的实例要等会话超时才能接手这些分区。
5. 你踩过什么坑？→ `BlockRebalanceOnPoll` 下 `Close()` 会一直等，要用 `CloseAllowingRebalance()`，测试抓到的。

**链 D：读模型**
1. 为什么单独做一个服务？→ 查询和写入分开（CQRS 读端），不影响下单和支付。
2. 数据坏了怎么办？→ 清空，offset 重置到最早，重放。
3. 重放一定得到同样的结果吗？→ 只要排序只看数据本身（时间、topic、offset），不看到达顺序。测试证明过。
4. 重放有什么前提？→ Kafka 还留着这些事件。默认只留 7 天，要 `retention.ms=-1`。

---

## 7. 英文说法

> "The tracking service is a small Go consumer. It reads order and delivery events from Kafka, stores them in its own Postgres database, and serves one endpoint: an order's timeline."

> "Kafka delivers at least once, so the service is idempotent. The event ID is the primary key, and offsets are committed only after the write. I tested the crash case by running the real binary in a child process and killing it between the write and the commit."

> "Failures come in two kinds. A broken message goes to a dead-letter topic and the consumer moves on. A database outage is retried in place with backoff, so nothing is skipped and the order is kept."

---

## 8. 资料

- Go `encoding/json`：null 的处理、`UnmarshalJSON` 收到 null 也会被调用
  https://pkg.go.dev/encoding/json#Unmarshal
- Go 1.22 路由增强：方法匹配、`{id}` 通配、`Request.PathValue`
  https://go.dev/blog/routing-enhancements
- `signal.NotifyContext`：收到信号时取消 ctx
  https://pkg.go.dev/os/signal#NotifyContext
- `context.WithoutCancel`、`context.AfterFunc`
  https://pkg.go.dev/context#WithoutCancel
- `http.Server.Shutdown` 和 `ErrServerClosed`
  https://pkg.go.dev/net/http#Server.Shutdown
- franz-go `kgo` 包文档（`BlockRebalanceOnPoll`、`AllowRebalance`、`CommitRecords`、`CloseAllowingRebalance`）
  https://pkg.go.dev/github.com/twmb/franz-go/pkg/kgo
- Kafka 官方设计文档 · Message Delivery Semantics（至多一次 / 至少一次）
  https://kafka.apache.org/43/design/design/#message-delivery-semantics
- PostgreSQL `INSERT … ON CONFLICT`
  https://www.postgresql.org/docs/current/sql-insert.html
- Spring Kafka · Publishing Dead-letter Records（DLT header 的来源）
  https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- Testcontainers for Go · Kafka 模块
  https://golang.testcontainers.org/modules/kafka/
- distroless 镜像
  https://github.com/GoogleContainerTools/distroless

---

## 9. 自测题

**1. 把 `ON CONFLICT (event_id) DO NOTHING` 删掉（主键还在），哪些测试会红？它们红的原因和最简实现时一样吗？**

<details><summary>答案</summary>

`TestInsert_DuplicateEventId_StoredOnce`、`TestInsert_SameUUIDDifferentCase_StoredOnce`、`TestCrashAfterStoreBeforeCommit_NoDuplicateRows`，可能还有 `TestRebalance_NoLossNoDuplicates`。
原因**不一样**：最简实现是"存了两行"；现在主键还在，第二次插入会**报错**（实测：`duplicate key value violates unique constraint "tracking_events_pkey" (SQLSTATE 23505)`）。消费者把它当暂时性失败一直重试，分区就卡死了。所以 `DO NOTHING` 不只是"别存两行"，还是"重复不算错误"。
</details>

**2. 为什么退避等待不能写成 `time.Sleep(wait)`？**

<details><summary>答案</summary>

`time.Sleep` 睡着的时候收不到停止信号。退避最长 10 秒，SIGTERM 来了也得等它睡完。`select` 同时等 `ctx.Done()` 和 `time.After(wait)`，哪个先来就走哪个。`TestShutdownDuringRetry_ExitsPromptly` 测的就是这个。
</details>

**3.（要真懂才答得出）`TestCrashBeforeStore_EventNotLost` 在最简实现上就是绿的。最简实现用的是 franz-go 的自动提交，为什么没有丢消息？怎么改一行让它丢？**

<details><summary>答案</summary>

franz-go 默认的自动提交只提交"上一次 poll"拿到的消息。程序在处理当前这批时崩溃，当前这批从来没被提交过，重启后会再来。所以同步处理时，默认就是至少一次。
把 `kgo.DisableAutoCommit()` 换成 `kgo.GreedyAutoCommit()` 就会丢：它提交所有已经拉到的消息，包括还没处理的那条。崩溃前等 7 秒，自动提交就把它提交了，重启后再也读不到（实测：测试等 60 秒超时，事件没了）。这是一个很好的改坏练习。
</details>

---

## 10. 预计回放时间

**60 分钟**：看红（15 分钟）→ 按组看修法的 diff，对照第 2 节（25 分钟）→ 改坏练习（10 分钟）→ 讲英文说法、做题（10 分钟）。

回放命令（PowerShell，在仓库根目录；集成测试需要 Docker Desktop 开着）：

```powershell
cd backend\trackingService
git checkout b8d8a06
go test ./...
go test -tags integration -count=1 ./...
git checkout claude/wave1-tracking-93e22a
go test -tags integration -count=1 ./...
```

改坏练习（任选一个，跑完 `git checkout .` 恢复）：
- 删掉 `ON CONFLICT (event_id) DO NOTHING` → 跑 `go test -tags integration ./internal/store/`
- 在 consumer.go 里把 `kgo.DisableAutoCommit()` 换成 `kgo.GreedyAutoCommit()` → 跑 `go test -tags integration -run TestCrashBeforeStore ./internal/integration/`
- 把 `.UTC()` 删掉 → 跑 `go test -tags integration -run UTC ./internal/store/`
