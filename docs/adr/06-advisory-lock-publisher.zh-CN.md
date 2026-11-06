# ADR-06 · 同一时间只有一个 outbox 发布器：事务级咨询锁

[English](06-advisory-lock-publisher.md) | **中文**

**状态：** 已采纳

## 背景
滚动更新时，一个服务会有两个 pod 同时在跑。两个都去发 `event_outbox`，事件就会发两遍，顺序也可能乱。

## 决定
每一轮发布是一个事务，第一步执行 `pg_try_advisory_xact_lock`。拿到锁的实例才发，拿不到就跳过这一轮。每轮按 id 顺序最多取 100 条未发的行，一条一条发，每条等 Kafka 确认，并在同一个事务里标成已发。某条发失败，这一轮就停在那里，已经确认的先提交；下一轮从失败的那条开始，顺序不乱。

## 代价与影响
- 不需要额外的协调组件：锁就在服务本来就用的 PostgreSQL 里。
- 提交、回滚或连接断开都会释放锁，不会因为连接被放回连接池而一直占着。
- 吞吐只有一个实例的量，而且等 Kafka 确认时一直拿着锁，所以每轮最多 100 条。
- store 的 `OutboxProcessor`（付款任务）**还没有**用这个锁，所以 store 只能跑一个副本（ADR-17）。

## 代码在哪
- store [EventOutboxPublisher.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/EventOutboxPublisher.java)、delivery [EventOutboxPublisher.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/EventOutboxPublisher.java)
