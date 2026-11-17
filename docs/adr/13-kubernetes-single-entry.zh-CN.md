# ADR-13 · 部署在 Kubernetes 上，只有一个统一入口

[English](13-kubernetes-single-entry.md) | **中文**

**状态：** 已采纳（入口用什么技术由 ADR-15 决定）

## 背景
项目要在一台笔记本上，以接近真实部署的形态跑起来，而且浏览器不用知道每个服务的端口。

## 决定
- 一个单节点的 kind 集群跑全部东西（命名空间 `shop`）。
- 浏览器只知道一个地址 `http://localhost`，统一入口按路径转发到各服务（ADR-15）。
- 配置放在 ConfigMap；密码放在 Secret，每个读取方一个，由脚本直接从 `backend/.env` 生成，磁盘上没有 Secret 文件。
- PostgreSQL、Kafka、RabbitMQ 都是单副本 StatefulSet，各自挂一个卷；可重复执行的 `db-init` Job 负责建库和登录名。
- Kafka 在这里挂了卷，compose 里却没有：compose 的 `stop` 会保留容器数据，但在 Kubernetes 里删 pod 就相当于 `down`，不挂卷 topic 就没了。

## 代价与影响
- compose 和 kind 用同一批镜像。
- 有状态组件都是单点，这套配置不提供高可用。
- `kind delete cluster` 会删掉集群里的所有数据。

## 代码在哪
- [k8s/](../../k8s/README.zh-CN.md)：`base/`、`apps/`、`gateway/`、`scripts/up.ps1`、`scripts/apply-secrets.ps1`
- [docs/learning/08-kubernetes-on-kind.zh-CN.md](../learning/08-kubernetes-on-kind.zh-CN.md)
