# 微服务电商平台

[English](README.md) | **中文**

一个拆成五个服务的网店，服务之间通过 Kafka 事件、RabbitMQ 任务和一次 gRPC 调用通信。这个项目关注的是分布式结账里最难的部分：即使 broker、bank 或某个消费者在最不巧的时候挂掉，顾客也必须**只被扣一次款**，出问题时**只被退一次款**，订单既不能卡住，也不能倒退。

项目起点是悉尼大学 COMP5348（Enterprise-Scale Software Development）的小组作业：四个 Spring Boot 服务、一个共用数据库、RabbitMQ。之后由我一个人重新设计并扩展：幂等支付、每个服务一个库、Kafka + outbox、一个 Go 读服务、store 到 bank 的 gRPC、本地 Kubernetes 部署。每一步都先写失败的测试，再用端到端场景脚本验证。

## 架构

架构图（Mermaid）见 [英文 README](README.md#architecture)，这里只列要点：

- **Kafka 传事实**（"订单已支付""包裹丢了"）。生产者在业务事务里把事件写进自己的 `event_outbox` 表，再由发布器发出去。
- **RabbitMQ 传任务**：delivery 的模拟快递步骤（每步一个任务，约 10 秒一步），以及 store 的邮件（`email_queue`）。
- **gRPC** 只用在动钱的那一次调用上：store → bank。
- **一个 PostgreSQL 服务器，每个服务一个库、一个登录名**；没有服务读别人的库。
- **统一入口是白名单**：只转发前端用到的 方法 + 路径；`/internal`、`/actuator` 和没列出来的路径在入口直接 404（[routes.yaml](k8s/gateway/routes.yaml)、[nginx.conf](frontend/nginx.conf)）。

## 服务

| 服务 | 语言 / 框架 | 负责什么 | 数据库 | 接口 |
|---|---|---|---|---|
| **store** | Java 17、Spring Boot 3.5 | 用户与登录（JWT）、商品与仓库库存、订单、支付；所有扣款和退款都由它发起 | `store_db` | REST `/api/user`、`/api/products`、`/api/orders`、`/api/store/...`、`/api/payments`；生产 `order-events`，消费 `delivery-events`；bank 的 gRPC 客户端；发 `email_queue` |
| **bank** | Java 17、Spring Boot 3.5、grpc-java | 账户和转账 | `bank_db` | gRPC `BankService.Transfer` / `Refund`（:9090，[bank.proto](backend/contracts/src/main/proto/bank/v1/bank.proto)）；REST `/api/bank/...`（开户、查余额） |
| **delivery** | Java 17、Spring Boot 3.5 | 配送单和模拟快递（CREATED → PICKED_UP → DELIVERING → RECEIVED，或 LOST） | `delivery_db` | REST `/api/deliveries/...`、`/api/admin/dlq/...`、`/internal/deliveries/cancel-by-order/{orderId}`；消费 `order-events`，生产 `delivery-events`；RabbitMQ 任务队列 |
| **email** | Java 17、Spring Boot 3.5 | 验证码和通知邮件（Gmail SMTP） | `email_db` | REST `/api/email/...`；消费 `email_queue` |
| **tracking** | Go 1.27、franz-go、pgx | 每个订单的只读事件时间线 | `tracking_db` | `GET /api/tracking/orders/{orderId}/timeline`；消费两个 topic |
| frontend | React 19、nginx | 网店界面 | — | compose 下的统一入口（`localhost:3000`） |

## 可靠性设计

每条都在代码里实现、有测试覆盖；代码链接见英文版的同名表格。

| 问题 | 代码怎么做 |
|---|---|
| 重试不能扣两次 | 每笔扣款 / 退款带幂等键（`PAY-{paymentId}-{随机}` / `REFUND-…`）。bank 用唯一约束存键，按固定顺序锁两个账户行，拿到锁后再查一次键；同一个键、内容不同直接判冲突。超时记为 `PAY_UNKNOWN`，不是失败，用**同一个键**重试（ADR-04、ADR-05）。 |
| 扣款 / 退款要扛得住崩溃 | store 不在请求里直接调 bank，而是在改订单的同一个事务里写一条 PAY / REFUND 任务到 `payment_outbox`，由 `OutboxProcessor` 执行，带快重试和慢重试。 |
| "提交"和"发送"之间不能丢事件 | 事件先作为一行写进各服务的 `event_outbox`，和业务数据同一个事务。发布器按 id 顺序逐条等 Kafka 确认，并持有 `pg_try_advisory_xact_lock`，两个实例不会发同一批（ADR-06）。Kafka 挂着也能正常付款，事件在表里等着。 |
| Saga 补偿：丢件 | delivery 发 `Lost`；store 在同一个事务里把订单标成 LOST 并写退款任务；bank 确认后订单变 REFUNDED，再发 `OrderRefunded`。 |
| 取消和建配送单赛跑 | 如果用户在 delivery 读到 `OrderPaid` 之前就取消，delivery 先插一行**取消标记**（该订单的 CANCELLED 行）；迟到的 `OrderPaid` 看到这一行就什么都不建（ADR-09）。 |
| Kafka 至少一次投递 | 消费者在数据库事务提交后才提交 offset。重复消息无害：delivery 每个订单只有一行，tracking 用 `ON CONFLICT (event_id) DO NOTHING`，store 忽略不能让订单往前走的事件。 |
| 事件迟到或乱序 | 状态不倒退：store 给状态排名 CREATED < PICKED_UP < DELIVERING < 终态，名次不高于当前状态的事件直接跳过。同一订单的事件用 `orderId` 做分区 key，保证有序（ADR-07）。 |
| 毒消息不能堵住分区，短暂故障不能丢事件 | 失败分两种。解析不了的消息直接进 `<topic>.DLT`；已知的暂时性故障（数据库挂了、锁超时）原地退避重试，直到恢复为止，这期间分区停住等待，保证同一订单的顺序；其他错误重试 5 次后进 DLT 并打 `ALERT` 日志。Go 和 Java 写一样的 DLT 头（ADR-08）。 |
| RabbitMQ 挂着时丢了配送任务 | 场景 05 发现：RabbitMQ 挂着时建的配送单会永远停在 CREATED。现在有扫表任务：非终态、60 秒没动的配送单会被重发任务；先用条件 `UPDATE` 认领，两个实例不会都重发（[学习文档 09](docs/learning/09-stuck-delivery-job-sweeper.zh-CN.md)）。 |

另外：库存用悲观行锁预留（[学习文档 04](docs/learning/04-inventory-pessimistic-lock.zh-CN.md)）；未支付订单超时自动取消，并在订单行锁下复查。

## 端到端场景

[backend/docker/scenarios](backend/docker/scenarios/README.zh-CN.md) 里的脚本通过 HTTP API 操作运行中的系统，再去查各服务自己的库。每次都注册新用户，可以反复跑。需要 PowerShell 7。

| 场景 | 发生什么 | 怎么跑 |
|---|---|---|
| **下单扣款** | 订单 PAID → PICKED_UP → IN_TRANSIT → DELIVERED；只扣一次；tracking 时间线按顺序有六个事件 | `pwsh backend/docker/scenarios/06-sequence-a-order-to-received.ps1` |
| **丢件退款** | 以 100% 丢件率重启 delivery；订单 LOST → REFUNDED，只退一次，余额回到原样 | `pwsh backend/docker/scenarios/07-sequence-b-lost-refund.ps1` |
| **取消竞态** | 停掉 Kafka 让 `OrderPaid` 迟到，用户先取消。取消标记挡住迟到的事件：不建配送单、退一次款、库存还回去 | `pwsh backend/docker/scenarios/08-sequence-c-cancel-before-delivery-reads.ps1` |

04–08 加 `-Target kind` 就在 Kubernetes 上跑。01–05 是故障场景（Pay Now 点两次、付完马上取消、付款时 bank 挂了、Kafka 挂了、RabbitMQ 挂了），见[场景说明](backend/docker/scenarios/README.zh-CN.md)。

## 快速开始

需要 Docker Desktop 和 PowerShell 7，命令都在仓库根目录运行。

### Docker Compose

```powershell
Copy-Item backend/.env.example backend/.env
# 填好 backend/.env：所有 *_PASSWORD，以及 JWT_SECRET（至少 64 个字符）。
# MAIL_USERNAME / MAIL_PASSWORD 可以留空：只是发不出邮件，其他都正常。

docker compose -f backend/compose.yml up -d --build --wait   # 第一次构建要几分钟
pwsh backend/docker/smoke-order.ps1                          # 下一单：PAID、只扣一次、配送往前走
```

打开 http://localhost:3000 注册用户（新用户自动开一个余额 10,000 的银行账户）。端口：store 8082、delivery 8081、email 8083、bank 8084（gRPC 9090）、tracking 8085、PostgreSQL 5433、RabbitMQ 管理界面 15672、Kafka（宿主机）9094。

停止：`docker compose -f backend/compose.yml stop`。`down -v` 会连数据卷一起删。

### Kubernetes（kind）

```powershell
docker compose -f backend/compose.yml stop     # compose 和 kind 不要同时开
docker compose -f backend/compose.yml build    # kind 用 compose 构建出的 shop-* 镜像
./k8s/scripts/up.ps1                           # 建集群、加载镜像、apply k8s/（第一次约 5 分钟）
./k8s/scripts/smoke-order.ps1
```

入口是 `http://localhost`（Envoy Gateway）。需要 kind v0.33、kubectl ≥ 1.33，详见 [k8s/README.zh-CN.md](k8s/README.zh-CN.md)。删除：`kind delete cluster --name shop`。

### 环境变量

都来自 `backend/.env`（不进 git，模板 [backend/.env.example](backend/.env.example)）。变量说明见英文版表格；Kafka 和 gRPC 的地址与配置见 [docs/wave2-config-contract.zh-CN.md](docs/wave2-config-contract.zh-CN.md)。

## 测试

Java 和 Go 的集成测试用 Testcontainers 起真的 PostgreSQL、Kafka、RabbitMQ，所以 Docker 要开着；先停掉 compose 栈和 kind 节点，不然容器起得慢。

```powershell
cd backend
./gradlew test                        # 所有 Java 模块（store 最多，要几分钟）
./gradlew :bankService:test           # 或单个模块：contracts、storeService、bankService、deliveryService

cd trackingService
go test ./...                         # 单元测试
go test -tags integration -count=1 ./...   # 加上真 Postgres + Kafka（2–3 分钟）
```

前端有 `npm test`，但唯一的测试 `App.test.js` 现在是失败的（见下文）。

## 设计文档

- [docs/learning/](docs/learning/)：每次改动一篇，讲问题、复现它的失败测试、修法和取舍（中英文各一份）。
- 模块说明：[contracts](backend/contracts/README.zh-CN.md)、[delivery](backend/deliveryService/README.zh-CN.md)、[tracking](backend/trackingService/README.zh-CN.md)、[k8s](k8s/README.zh-CN.md)、[场景脚本](backend/docker/scenarios/README.zh-CN.md)。
- 架构决策记录（ADR-01 到 ADR-14）在另一份设计文档里，暂未放进仓库；代码注释按编号引用。
- [docs/legacy/](docs/legacy/)：原课程项目（只有 RabbitMQ 的版本）留下的笔记。

## 已知限制和下一步

- **服务之间没有鉴权**：delivery 的 `/internal/...` 和 bank 的 gRPC 接受网络内任何调用方，bank 查余额不需要 token；现在只靠入口白名单挡在外面。store、delivery 的部分接口（比如 store 的退款接口）还缺权限检查。
- **单副本**：所有有状态组件都只有一个；store 只能一个副本，因为 `OutboxProcessor` 没有跨实例的锁。
- **kind 入口偶发 503**（发现 I2）：一次读请求经过 Envoy Gateway 时在到达 store 之前被重置，重试就过了，原因还没查清。
- **场景脚本 01–03 只能跑 compose**；04–08 两边都能跑。
- **前端测试**：`App.test.js` 在 `main` 上失败（还在找 CRA 模板里的 "learn react" 文字）。
- **下一步**：部署到云上的 Kubernetes（计划 AKS），并把 RabbitMQ 驱动的模拟快递换成工作流引擎。
