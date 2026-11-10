# ADR-12 · 每个服务独占自己的库

[English](12-database-per-service.md) | **中文**

**状态：** 已采纳

## 背景
课程版本所有服务共用一个库，任何服务都能读改别人的表，谁也没法单独改自己的表结构。

## 决定
store、bank、delivery、email、tracking 各有自己的库（`store_db`、`bank_db` …）和自己的登录名（`store_user` …）。五个库放在**同一个** PostgreSQL 服务器里。每个容器只拿到自己的密码。

## 代价与影响
- 边界由数据库保证：一个服务登录不了别的服务的库。
- 别的服务的数据只能通过它的接口或事件拿。
- 一个服务器在本地很省事，但它是单点；这是逻辑上的拆分，不是物理上的。

## 代码在哪
- [01-create-databases.sh](../../backend/docker/postgres/init/01-create-databases.sh)（compose）、[db-init.sh](../../k8s/base/db-init.sh)（kind）
- [compose.yml](../../backend/compose.yml)：每个服务只列自己需要的变量
- [docs/learning/02-docker-database-per-service.zh-CN.md](../learning/02-docker-database-per-service.zh-CN.md)
