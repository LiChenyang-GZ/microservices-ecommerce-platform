# ADR-08 · 消费失败：故障就原地等，坏消息就挪开

[English](08-two-kinds-of-consumer-failure.md) | **中文**

**状态：** 已采纳（实现时细化成三种情况）

## 背景
消费者失败有两种原因：依赖的东西暂时挂了（重试就会好），或者消息本身是坏的（重试永远不会好）。两种按同一种方式处理，要么丢事件，要么把一个分区永远堵住。

## 决定
三种情况，store、delivery、tracking 处理方式一样：

| 失败 | 怎么处理 |
|---|---|
| 消息是坏的：解析不了、缺字段、引用的订单不存在 | 不重试。写进 `<topic>.DLT`，原因放在 header 里，打 `ALERT`，接着处理下一条 |
| 已知的暂时性故障：数据库挂了、锁超时 | 原地指数退避重试，故障持续多久就等多久。这个分区停着，顺序不乱 |
| 其他异常，多半是 bug | 重试 5 次，然后进 `<topic>.DLT` 并打 `ALERT` |

最初的设计只有前两种。第三种是后来加的（Christy 选的方案 b）：一个意料之外的异常，既不能把分区永远卡住，也不能悄悄把事件丢掉。

每个 topic 3 个分区，由 3 个消费者线程读，一个分区在等不会拖住另外两个。每个 `.DLT` 的分区数和原 topic 一样，因为 Spring 会把坏记录写到编号相同的分区。Go 和 Java 写一样的 DLT header。

## 代价与影响
- 异常必须分类分对；分错了，要么卡住分区，要么过早进 DLT。
- 分区在等的时候，这个分区里的订单都会延迟，直到故障结束。

## 代码在哪
- delivery [KafkaConsumerConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/events/KafkaConsumerConfig.java)、store [KafkaConsumerConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/events/KafkaConsumerConfig.java)
- tracking [dlt.go](../../backend/trackingService/internal/consumer/dlt.go)、[handler.go](../../backend/trackingService/internal/consumer/handler.go)
