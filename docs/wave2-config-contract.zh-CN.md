# 第 2 波配置契约

[English](wave2-config-contract.md)

第 2 波三个任务并行：**A** Kafka（设计第 4–5 步）、**B** gRPC（第 6 步）、**C** Kubernetes（第 8 步）。
A 和 B 本来都要改 `backend/compose.yml` 和 `.env.example`；C 写 ConfigMap 和 Secret 之前，必须知道 A、B 起的名字。
所以共用的部分先在这里定下来、先合并，然后再分出三个分支。

**规则**：第 2 波的任务不改这份文件里的任何东西。需要改，就停下来报告。

## Kafka

| 项 | 值 | 为什么 |
|---|---|---|
| 镜像 | `apache/kafka:3.9.1`，单节点，KRaft（不用 ZooKeeper） | 设计图 2 |
| compose / 集群内地址 | `kafka:9092` | |
| 本机地址 | `localhost:9094` | 给用 `bootRun` / `go run` 在本机跑的服务 |
| topic | `order-events`、`delivery-events`、`order-events.DLT`、`delivery-events.DLT` | `contracts` → `Topics.java` |
| 分区 / 副本 | 每个 topic 3 分区、1 副本 | 同一个 `orderId` 永远落在同一个分区（有序），不同订单分散到三个分区。`.DLT` 和原 topic 分区数相同，因为 Spring 的死信发送默认写到**同一个分区号** |
| 自动建 topic | **关** | topic 名写错会立刻报错，而不是悄悄建一个空 topic。也顺带解决第 1 波审查的"`order-events.DLT` 不存在时消费者无限重试" |
| 谁建 topic | 一次性的 `kafka-init` 服务，`--if-not-exists` | |
| 数据卷 | 不挂：`stop` 保留，`down` 清空 | 事件还在各服务的 `event_outbox` 表里 |

## 环境变量

| 名字 | 谁读 | compose 里的值 | 哪个任务用 |
|---|---|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | store、delivery | `kafka:9092` | A（Spring 自动绑定到 `spring.kafka.bootstrap-servers`） |
| `KAFKA_BROKERS` | tracking | `kafka:9092` | 已有（第 1 波） |
| `KAFKA_GROUP` | tracking | `tracking` | 已有 |
| `BANK_GRPC_PORT` | bank | `9090` | B：bank 的 gRPC 服务端口 |
| `BANK_GRPC_ADDRESS` | store | `bank:9090` | B：store 的 gRPC 客户端连哪里 |
| `DATABASE_URL` | tracking | `postgres://tracking_user:${TRACKING_DB_PASSWORD}@postgres:5432/tracking_db?sslmode=disable` | 已有。密码放进了 URL，所以不能含 `@ : / ? #` |
| `STORE_URL` | tracking | `http://store:8082` | 已有（校验 token） |

消费者组 id，写在各服务自己的配置里：store = `store`，delivery = `delivery`，tracking = `tracking`。

端口：store 8082，bank 8084（REST）+ 9090（gRPC），delivery 8081，email 8083，tracking 8085，Kafka 9094（本机），frontend 3000。

## 之后每个任务能改什么

- **A**：store 和 delivery 的 Java 代码和 `application.properties`。`compose.yml` 里只删 `DELIVERY_NOTIFICATION_URL`（webhook 不要了）。
- **B**：bank 和 store（`adapter/BankAdapter.java` 及以下），bank 和 store 的 Gradle 文件。不改 `compose.yml`。`.proto` 在 `contracts` 里，第 0 波已经定好。
- A 和 B 都要往 `storeService/build.gradle` 加依赖，这是唯一预料到的重叠：开第二个 PR 前用 `git merge-tree` 试合并。
- **C**：`k8s/` 下的新文件。密码进 Secret，其余进 ConfigMap，**键名用上面的**。
