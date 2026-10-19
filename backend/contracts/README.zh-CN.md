# contracts · 服务之间的消息格式

[English](README.md) | **中文**

这个模块只放**格式**：Kafka 事件长什么样、store 调 bank 的 gRPC 接口长什么样。
不放业务逻辑，也不会单独启动。别的服务在 `build.gradle` 里加一行 `implementation project(':contracts')` 就能用。

> **给并行的 agent**：第 0 波合并之后，**谁都不许改这个模块**。需要改就停下来，报告给 Christy。

设计依据：设计 artifact《COMP5348 电商架构》。下面写"设计：……"的地方，都是从那里来的。
标 🤖 的是设计里没写、由 Claude 决定的，Christy 合并前确认过。

---

## 1. 目录

```
contracts/
├── src/main/java/com/comp5348/contracts/events/
│   ├── EventEnvelope.java       事件外壳（5 个字段，构造时检查）
│   ├── EventCodec.java          读写 JSON 的唯一入口
│   ├── InvalidEventException    消息本身坏了 → 消费者送进 DLT
│   ├── Topics.java              topic 名字
│   ├── OrderEventType.java      order-events 的 5 种事件
│   ├── DeliveryEventType.java   delivery-events 的 6 种事件
│   └── payload/                 每种事件的 payload
├── src/main/proto/bank/v1/bank.proto   store → bank 的 gRPC 接口
├── examples/                    每种事件一个示例 JSON（Java 和 Go 的测试都读）
└── src/test/                    契约测试
```

---

## 2. 事件外壳

设计："每个事件都用同一个外壳：eventId、eventType、orderId、occurredAt、payload"。

| 字段 | JSON 类型 | 例子 | 用来做什么 | 出处 |
|---|---|---|---|---|
| `eventId` | 字符串（UUID） | `"3f1c2a6e-…"` | 消费者按它去重 | 设计 + 规则 4 |
| `eventType` | 字符串 | `"OrderPaid"` | 消费者按它决定怎么处理 | 设计 |
| `orderId` | **字符串** | `"42"` | Kafka 的 key（同一订单进同一分区，保证顺序） | 设计 + 规则 5；用字符串是 🤖 |
| `occurredAt` | 字符串（ISO-8601，UTC） | `"2026-10-19T02:02:00Z"` | tracking 按它排时间线 | 设计 + 规则 5 |
| `payload` | **对象** | `{ … }` | 每种事件自己的字段，见第 3 节 | 设计 |

**五个字段都必填。** 缺字段、值是 null、`orderId` 写成数字、`payload` 不是对象，`EventCodec.parse` 都会抛 `InvalidEventException`。消费者遇到它就把消息送进 `<topic>.DLT`，然后继续处理后面的消息（规则 6）。

### 怎么用

```java
// 生产者（store / delivery 的事件投递程序）
EventEnvelope event = new EventEnvelope(
        UUID.randomUUID(),
        OrderEventType.ORDER_PAID.wireName(),
        String.valueOf(order.getId()),
        Instant.now(),
        EventCodec.toTree(new OrderPaidPayload(...)));
String json = EventCodec.write(event);         // 存进 event_outbox，Kafka key = event.orderId()

// 消费者
try {
    EventEnvelope event = EventCodec.parse(json);
    OrderPaidPayload p = EventCodec.payload(event, OrderPaidPayload.class);
    // …业务逻辑…
} catch (InvalidEventException e) {
    // 坏消息：送进 DLT + 告警，不重试
}
```

**不认识的 `eventType`：忽略，不算坏消息。** 设计里 delivery 只处理 OrderPaid 和 OrderCancelled，其余的事件忽略。

---

## 3. 每种事件的 payload

金额一律是**字符串**，比如 `"259.80"`（🤖：避免浮点误差，Go 也不会丢精度）。

### order-events（store 写；delivery、tracking 读）

| 事件 | 什么时候发 | payload 字段 | 出处 |
|---|---|---|---|
| `OrderPlaced` | 下单事务里 | `userId`, `productId`, `quantity`, `totalAmount` | 时序图 A 第 2 步；字段 🤖 |
| `OrderPaid` | 扣款成功、订单还是待支付时，同一个事务里 | `paymentId`, `amount`, `bankTxnId`, `customerEmail`, `customerName`, `toAddress`, `fromAddresses`, `productName`, `quantity` | 规则 1、时序图 A。配送字段照抄现在的 `DeliveryRequestDTO`，这样 delivery 读到就能建单，不用回调 store |
| `PaymentFailed` | bank **明确拒绝**时（超时不算） | `paymentId`, `attempt`, `reason` | 时序图 A、规则 7 |
| `OrderCancelled` | 订单取消的事务里 | `reason`：`USER_REQUEST` 或 `PAYMENT_TIMEOUT` | 时序图 C、取消接口说明；取值 🤖 |
| `OrderRefunded` | 退款成功的事务里 | `paymentId`, `amount`, `refundTxnId`, `reason`：`ORDER_CANCELLED` / `DELIVERY_LOST` / `PAID_AFTER_CANCEL` | 时序图 B；三种 reason 对应设计"需要退款的三种情况" |

### delivery-events（delivery 写；store、tracking 读）

六种事件**共用同一个 payload**：`deliveryId`、`status`。

| 事件 | 对应的 `DeliveryStatus` | 出处 |
|---|---|---|
| `DeliveryCreated` | `CREATED` | 设计："事件名和 `DeliveryStatus` 枚举一一对应" |
| `PickedUp` | `PICKED_UP` | 同上 |
| `Delivering` | `DELIVERING` | 同上 |
| `Received` | `RECEIVED` | 同上 |
| `Lost` | `LOST` | 同上 |
| `Cancelled` | `CANCELLED` | 同上 |

- 名字不是完全一样（`CREATED` ↔ `DeliveryCreated`），所以用 `DeliveryEventType.fromStatus(status.name())` 来找，不要自己拼名字。
- **`Cancelled` 的 `deliveryId` 可以是 null**：订单在 delivery 读到 OrderPaid 之前就被取消了，delivery 只写了一个"取消标记"，还没有配送单（ADR-09、时序图 C）。
- store 判断"状态不倒退"用的顺序：`CREATED < PICKED_UP < DELIVERING < 终态（RECEIVED / LOST / CANCELLED）`（规则 5）。

---

## 4. bank.proto（store → bank）

设计：store 调 bank 用 gRPC（ADR-10），幂等键见 ADR-04，超时处理见 ADR-05 / 规则 7。

| 方法 | 请求 | 幂等键 |
|---|---|---|
| `Transfer` | `idempotency_key`, `from_account`, `to_account`, `amount` | `PAY-{paymentId}-{attempt}` |
| `Refund` | `idempotency_key`, `original_transaction_id`, `reason` | `REFUND-{paymentId}` |

**怎么读结果**（这是最重要的一条）：

| bank 返回 | 意思 | store 怎么做 |
|---|---|---|
| `outcome = SUCCEEDED` | 成功 | 走成功流程 |
| `outcome = REJECTED` | 确定失败（比如余额不足） | 扣款：标 FAILED；退款：停下来 + 告警 |
| gRPC 报错（`DEADLINE_EXCEEDED`、`UNAVAILABLE` 等） | **不知道结果** | 用**同一个键**重试；5 次后标 `PAY_UNKNOWN` / `REFUND_UNKNOWN`，**绝不能当成失败** |

🤖：结果只分 SUCCEEDED / REJECTED 两种，"不知道"靠 gRPC 自己的错误码表示，不再另加一个字段。
🤖：退款总是全额退，所以 `Refund` 不带金额。

**第 2 步还是 HTTP**（第 6 步才换成 gRPC）。第 2 步改 bank 的 REST 接口时，请求和返回的字段**照这个 proto 起名**（JSON 里用驼峰：`idempotencyKey`、`fromAccount`……）。这样第 6 步换 gRPC 时只换协议，字段不用动。

---

## 5. 示例文件 `examples/`

每种事件一个，文件名是 `<topic>-<eventType>.json`。

- Java：`EventEnvelopeContractTest` 读每个文件，检查它能解析、payload 能转成对应的类、写回去内容不变。
- Go（第 1 波 agent C）：tracking 的测试也读这些文件。**两种语言读同一份样本**，才能保证理解一致。

新增或修改事件时，示例文件要一起改，否则 `examples_coverEveryEventType` 会变红。

---

## 6. 失败场景表（第 0 步）

| 场景 | 设计里怎么处理 | 证明它的测试 | 结果 |
|---|---|---|---|
| 事件缺了外壳字段 | 外壳 5 个字段必填；坏消息进 DLT（规则 6） | `eventEnvelope_missingRequiredField_failsValidation`（5 个） | 先红后绿 ✅ |
| 外壳字段是 null | 同上 | `eventEnvelope_nullRequiredField_failsValidation`（5 个） | 先红后绿 ✅ |
| orderId 写成数字，Go 读不了 | 契约规定是字符串 🤖 | `orderId_sentAsJsonNumber_isRejected` | 先红后绿 ✅ |
| payload 不是对象 | 外壳规定 | `payload_notAnObject_isRejected` | 先红后绿 ✅ |
| 配送事件和 `DeliveryStatus` 对不上 | 设计"一一对应" | `everyDeliveryStatus_hasMatchingEventType`、`deliveryStatusToEvent_isOneToOne` | 先红后绿 ✅ |
| 示例文件和 Java 类不一致 | — | `everyExample_parsesIntoItsPayloadType_andRoundTrips`（11 个） | 一直是绿（安全网） |
| 有事件没有示例文件 | — | `examples_coverEveryEventType` | 一直是绿（安全网） |
| store 和 bank 用的 `.proto` 不一致 | ADR-10 | 只有一份 `.proto`，两边都从它生成代码；能编译就通过 | 📋 |

**怎么跑**（在 `backend` 目录，PowerShell）：

```
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
```

> `--tests` 后面不要写 `"*Contract*"`：Windows 上 Java 会把它当文件通配符，匹配成 `contracts` 文件夹。

---

## 7. 🤖 Claude 替你做的决定（合并前确认）

1. `orderId`、金额都用字符串。
2. Java 的事件类放在共享模块里，各服务引用同一份；Go 靠示例 JSON 对齐。代价：改契约时所有服务都要重新编译。所以这里只放格式，不放业务逻辑。
3. 各个 payload 的具体字段，以及 `reason` 的取值（设计只写了事件名）。
4. gRPC 只有 SUCCEEDED / REJECTED 两种结果；退款不带金额。
5. 没有加版本号字段。以后改格式只**加字段**，不改、不删已有字段，旧的消费者照样能读。
