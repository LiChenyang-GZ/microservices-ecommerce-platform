# tracking · 订单时间线服务（Go）

[English](README.md) | **中文**

订阅 `order-events` 和 `delivery-events` 两个 topic，把每个事件存进 `tracking_db`，按订单返回时间线。
设计依据：设计 artifact《COMP5348 电商架构》ADR-07、ADR-08、ADR-11、ADR-14，规则 4、5、6，交互说明第 8、12 行。
事件格式：[`backend/contracts`](../contracts/README.zh-CN.md)（这个服务只读它的 `examples/`，不改契约）。
学习文档：[docs/learning/03-go-tracking-consumer.zh-CN.md](../../docs/learning/03-go-tracking-consumer.zh-CN.md)。

---

## 1. 接口

```
GET /api/tracking/orders/{orderId}/timeline
Authorization: Bearer <store 签发的 token>

200 [{ "eventType": "OrderPlaced", "source": "order-events", "occurredAt": "2026-10-19T02:01:00Z" }, ...]
```

| 情况 | 返回 |
|---|---|
| 正常 | `200`，按 `occurredAt` 升序；时间相同 `order-events` 在前，再按 offset |
| 没带 token / store 说 token 无效 | `401` |
| 订单不是这个用户的，或者还没收到它的 OrderPlaced | `404`（两种情况回得一样） |
| store 连不上、超时（2 秒）、5xx；或者数据库出错 | `503`，不带内部错误信息 |

另有 `GET /healthz`（进程活着）和 `GET /readyz`（能连上数据库），给 Docker 和 Kubernetes 用。

## 2. 消费规则

| 消息 | 做什么 | 依据 |
|---|---|---|
| 正常事件 | `INSERT … ON CONFLICT (event_id) DO NOTHING`，写成功后才提交 offset | 规则 4 |
| 重复的 eventId（大小写不同也算） | 什么都不改 | 规则 4 |
| 坏消息（不是 JSON、缺字段、eventId 不是 UUID、事件放错 topic） | 原样写进 `<topic>.DLT`，原因放在 header，继续下一条 | 规则 6、ADR-08 |
| 外壳合法但数据库永久拒收（Postgres 错误码 22 类，例如 NUL 字符、非法 UTF-8） | 当坏消息写进 `<topic>.DLT`，继续下一条 | 规则 6、失败场景 #40 |
| 不认识的 eventType | 记日志、跳过，不进 DLT | 契约 §2 |
| 数据库或 DLT 暂时不可用 | 原地退避重试（200 ms 翻倍到 10 s），不提交、不跳过 | 规则 6、ADR-08 |
| 收到 SIGTERM | 不再取新消息，手上那条最多再给 10 秒写完并提交，离开消费者组；HTTP 处理完正在进行的请求再关端口 | 设计"Go 服务"一节 |

DLT 的 header 名字和编码照抄 Spring Kafka 的 `DeadLetterPublishingRecoverer`：`kafka_dlt-original-topic`、`kafka_dlt-original-partition`（4 字节大端）、`kafka_dlt-original-offset`（8 字节大端）、`kafka_dlt-original-consumer-group`、`kafka_dlt-exception-message`。

## 3. 配置（环境变量）

| 变量 | 必填 | 默认 | 说明 |
|---|---|---|---|
| `DATABASE_URL` | ✅ | — | 如 `postgres://tracking_user:…@postgres:5432/tracking_db` |
| `KAFKA_BROKERS` | ✅ | — | 逗号分隔 |
| `STORE_URL` | ✅ | — | 校验 token 用，如 `http://store:8082` |
| `HTTP_ADDR` | | `:8085` | |
| `KAFKA_GROUP` | | `tracking` | 消费者组名 |
| `KAFKA_SESSION_TIMEOUT` | | 客户端默认 | 如 `10s` |

缺必填项时启动就退出（退出码 2），并列出缺了哪些。表由服务启动时自己建（`CREATE TABLE IF NOT EXISTS`）。

## 4. 目录

```
cmd/tracking/        main：读配置、处理 SIGTERM；`tracking healthcheck` 给 Docker 用
internal/event/      解析事件外壳（规则和 Java 的 EventCodec 一致）
internal/store/      Postgres：建表、去重写入、查时间线、查下单用户
internal/consumer/   franz-go 消费者：两种失败、DLT、手动提交、优雅退出
internal/auth/       回调 store 校验 token
internal/httpapi/    查询接口、/healthz、/readyz
internal/app/        把上面这些接起来；crash_on.go 只在 -tags crashtest 时编译，给崩溃测试用
internal/integration/ 真 Kafka + 真 Postgres 的集成测试
internal/examples/   读 contracts/examples 的测试工具
```

## 5. 测试

```powershell
# 单元 + HTTP 测试（几秒，不需要 Docker）
go test ./...

# 加上集成测试（真 Postgres + 真 Kafka，Testcontainers；Docker Desktop 要开着，约 2–3 分钟）
go test -tags integration -count=1 ./...
```

Windows 上的 Docker Desktop：测试入口会自动设置 `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`，否则 Testcontainers 的辅助容器 Ryuk 起不来（[internal/examples/docker.go](internal/examples/docker.go)）。

## 6. 镜像

```powershell
docker build -t tracking:dev .
```

多阶段构建：`golang:1.27` 编译静态二进制 → `gcr.io/distroless/static-debian12:nonroot`。二进制层 15.7 MB，压缩后 6.6 MB，以 uid 65532 运行。distroless 里没有 shell 和 curl，所以 `HEALTHCHECK` 调的是 `/tracking healthcheck`。
还没有加进 `backend/compose.yml`（集成时做）。

## 7. 已知限制

- store 和 delivery 的时钟不同步时，两个 topic 的事件在时间线上可能倒挂（G8）。
- 重放只能找回 Kafka 还保留着的事件，默认 7 天；要完整重放，topic 需要 `retention.ms=-1`（G6，第 2 波建 topic 时设）。
- 查询接口每次都回调 store 校验 token，store 不可用时返回 503（ADR-14，有意保留）。
