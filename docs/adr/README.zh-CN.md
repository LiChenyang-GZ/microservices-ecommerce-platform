# 架构决策记录（ADR）

[English](README.md) | **中文**

每条记录写清楚：决定了什么、为什么、代价是什么、代码在哪。内容以 `main` 上的代码为准；实现过程中改了最初设计的地方，记录里会直接写出来。

ADR-01 到 ADR-14 是动手之前写的，代码注释按编号引用（"ADR-06""rule 4"）。ADR-15 以后是实现过程中做的决定。

| # | 决定 | 状态 |
|---|---|---|
| [01](01-events-between-services.zh-CN.md) | 服务之间用 Kafka 事件传递状态变化 | 已采纳 |
| [02](02-rabbitmq-for-jobs.zh-CN.md) | 任务走 RabbitMQ，事件走 Kafka | 已采纳 |
| [03](03-transactional-outbox.zh-CN.md) | 事件和资金任务都先写进 outbox | 已采纳 |
| [04](04-single-money-path-idempotent.zh-CN.md) | 动钱只有一条路，每次调用都幂等 | 已采纳，由 16 修订 |
| [05](05-timeout-is-not-failure.zh-CN.md) | 超时不等于失败 | 已采纳 |
| [06](06-advisory-lock-publisher.zh-CN.md) | 同一时间只有一个 outbox 发布器：事务级咨询锁 | 已采纳 |
| [07](07-order-by-order-dedupe-in-consumers.zh-CN.md) | 按订单保序，消费者自己去重 | 已采纳 |
| [08](08-two-kinds-of-consumer-failure.zh-CN.md) | 消费失败：故障就原地等，坏消息就挪开 | 已采纳，实现时细化 |
| [09](09-cancel-sync-call-and-marker.zh-CN.md) | 取消订单：同步调用 + 取消标记 | 已采纳 |
| [10](10-grpc-inside-rest-outside.zh-CN.md) | 服务之间动钱用 gRPC，浏览器用 REST | 已采纳 |
| [11](11-go-tracking-read-service.zh-CN.md) | 订单时间线是一个独立的 Go 读服务 | 已采纳 |
| [12](12-database-per-service.zh-CN.md) | 每个服务独占自己的库 | 已采纳 |
| [13](13-kubernetes-single-entry.zh-CN.md) | 部署在 Kubernetes 上，只有一个统一入口 | 已采纳，入口由 15 决定 |
| [14](14-out-of-scope.zh-CN.md) | 这次设计不动的东西 | 已采纳 |
| [15](15-gateway-api-allowlist-entry.zh-CN.md) | 统一入口用 Gateway API（Envoy Gateway），按白名单转发 | 已采纳 |
| [16](16-unguessable-idempotency-keys.zh-CN.md) | 幂等键带随机部分，bank 比对请求内容 | 已采纳 |
| [17](17-probes-and-replicas.zh-CN.md) | 探针只检查服务自己；store 只跑一个副本 | 已采纳 |
| [18](18-stuck-delivery-sweeper.zh-CN.md) | 扫表补发：重发卡住不动的配送任务 | 已采纳 |
| [19](19-shared-event-contract.zh-CN.md) | Java 和 Go 共用一份事件契约 | 已采纳 |
| [20](20-stock-pessimistic-lock.zh-CN.md) | 扣库存用悲观行锁 | 已采纳 |
| [21](21-schema-changes-at-startup.zh-CN.md) | 表结构改动在启动时跑幂等 SQL，不用 Flyway | 已采纳 |
| [22](22-grpc-result-mapping.zh-CN.md) | bank 的 gRPC 回复怎么对应到 成功 / 拒绝 / 未知 | 已采纳 |

## 可靠性规则

代码注释也按编号引用这些规则。每条都属于上面某几条 ADR。

| 规则 | 一句话 | ADR |
|---|---|---|
| 1 | 只有 `OutboxProcessor` 调 bank；每次调用带幂等键 | 04、16 |
| 2 | 任务进 `payment_outbox`，事件进 `event_outbox`，都和业务改动在同一个事务里写 | 03 |
| 3 | 同一时间只有一个发布器；按 id 顺序发，遇到失败就停 | 06 |
| 4 | 先提交数据库，再提交 offset；消费者去重 | 07 |
| 5 | 状态不倒退 | 07 |
| 6 | 坏消息 → DLT；暂时性故障 → 原地等 | 08 |
| 7 | 超时不等于失败；用同一个键重试 | 05、22 |
| 8 | Pay Now 先锁 payment 行，只重试 FAILED 的付款 | 04 |

新记录用下一个编号。决定变了不重写旧记录：写一条新记录说明改了什么，旧记录的状态指向它。
