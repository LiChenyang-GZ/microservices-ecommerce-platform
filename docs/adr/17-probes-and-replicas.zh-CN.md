# ADR-17 · 探针只检查服务自己；store 只跑一个副本

[English](17-probes-and-replicas.md) | **中文**

**状态：** 已采纳

## 背景
Spring 完整的 `/actuator/health` 包含 RabbitMQ，RabbitMQ 挂着时回 503。拿它当 Kubernetes 探针，中间件一挂，所有服务都会被反复重启，或者全被摘掉流量。可 outbox（ADR-03）存在的意义，恰恰就是中间件挂着时服务还能干活。另外，store 的 `OutboxProcessor` 没有跨实例的锁（ADR-06 只管事件发布器）。

## 决定
- **存活探针（liveness）**只看进程本身（`/actuator/health/liveness`）。
- **就绪探针（readiness）**看进程和服务自己的数据库，不看 Kafka、RabbitMQ。
- **store 只跑一个副本，更新策略是 `Recreate`**，不会有两个 pod 同时执行付款任务。代价是更新时有短暂停机。

## 代价与影响
- Kafka 挂着时 pod 保持 Ready，订单照常付款（kind 上的场景 04 验证过）。
- 在 `OutboxProcessor` 像事件发布器那样加锁之前，store 不能横向扩展。

## 代码在哪
- [k8s/apps/store.yaml](../../k8s/apps/store.yaml)（探针、`replicas: 1`、`Recreate`）以及 [k8s/apps](../../k8s/apps/) 里的其他文件
- [k8s/README.zh-CN.md](../../k8s/README.zh-CN.md) 的"决定和原因"
