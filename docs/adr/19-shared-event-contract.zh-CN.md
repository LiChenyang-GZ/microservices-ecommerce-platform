# ADR-19 · Java 和 Go 共用一份事件契约

[English](19-shared-event-contract.md) | **中文**

**状态：** 已采纳

## 背景
有契约之前，格式不对的事件可能被悄悄收下，好几步之后才出错，或者只在 Go 那边出错。比如：缺字段被当成 null，`orderId: 42` 被悄悄转成 `"42"`，payload 发成字符串也照收。

## 决定
- `contracts` 模块放事件信封（`eventId`、`eventType`、`orderId`、`occurredAt`、`payload`）、topic 名、每种事件的 payload 类型，以及 `bank.proto`。
- Java 服务只通过 `EventCodec` 读写事件。它拒绝缺字段和类型错误，这样的消息是 `InvalidEventException`，进 DLT（ADR-08）。
- 不认识的字段直接忽略，生产者加字段不会弄坏旧的消费者。
- 每种事件的示例 JSON 放在 `contracts/examples/`。Java 的契约测试和 Go 的测试解析同一批文件，两边的解析器不会慢慢走样。

## 代价与影响
- 坏消息在第一个消费者那里就失败，而且原因清楚。
- 改一个事件，要同时改契约模块、示例和 Go 的解析。

## 代码在哪
- [backend/contracts](../../backend/contracts/README.zh-CN.md)：[EventEnvelope.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java)、[EventCodec.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java)、[examples](../../backend/contracts/examples/)
- Go：[event.go](../../backend/trackingService/internal/event/event.go)、[examples_test.go](../../backend/trackingService/internal/event/examples_test.go)
- [docs/learning/00-event-contract.zh-CN.md](../learning/00-event-contract.zh-CN.md)
