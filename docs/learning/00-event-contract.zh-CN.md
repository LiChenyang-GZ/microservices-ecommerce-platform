# 00 · 事件契约：在门口拦下坏消息

[English](00-event-contract.md) | **中文**

> 对应 commit：`a504482 test(red)` → `b8c92d9 feat`
> 预计回放时间：**30 分钟**
> 契约本身的说明（每个字段、每种事件）见 [contracts/README.zh-CN.md](../../backend/contracts/README.zh-CN.md)。这份文档只讲**这一步学到的概念**。

---

## 1. 问题

**服务之间用 Kafka 传消息时，一条格式不对的消息会被悄悄收下，等到几步之后才出错，或者只在另一种语言（Go）那边出错。**

复现的测试（`test(red)` 时 14 个红）：
- 缺字段被当成 null 收下：[EventEnvelopeContractTest.java:46](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L46)、[:61](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L61)
- `orderId: 42`（数字）被自动转成 `"42"`：[:71](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L71)
- payload 是一段文字也被收下：[:84](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L84)
- 配送状态 `CREATED` 找不到事件 `DeliveryCreated`：[DeliveryEventContractTest.java:22](../../backend/deliveryService/src/test/java/com/comp5348/delivery/contracts/DeliveryEventContractTest.java#L22)

---

## 2. 是什么（框架层）

### 2.1 契约（contract）
**大白话**：写消息的一方和读消息的一方事先约好"消息长什么样"。
这里有两种：Kafka 事件的 JSON 格式（`EventEnvelope` + payload），和 store 调 bank 的 gRPC 接口（`bank.proto`）。
放在一个单独的 Gradle 模块 `contracts` 里，各服务用 `implementation project(':contracts')` 引用同一份。

### 2.2 Java record 和紧凑构造器（compact constructor）
- `record` 是 Java 16 起正式的"只装数据的类"：写出字段，编译器自动生成构造器、getter（`eventId()`）、`equals`、`hashCode`、`toString`。字段都是 `final`，造好就不能改。
- **紧凑构造器**：在 record 里写 `public EventEnvelope { ... }`，不写参数列表。它在字段被赋值**之前**运行，适合做检查。检查不通过就抛异常，对象根本造不出来。
- 代码：[EventEnvelope.java:28](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java#L28)

### 2.3 Jackson 怎么把 JSON 变成 record
- Jackson 是 Spring Boot 默认的 JSON 库。`ObjectMapper.readValue(json, EventEnvelope.class)` 会：读 JSON → 找到 record 的构造器 → 按字段名把值传进去。
- **JSON 里缺了某个字段时，Jackson 不报错，直接传 null。** 这就是第一类红灯的原因。
- 因为 Jackson 一定会调用构造器，所以只要在紧凑构造器里检查，**读消息时就自动被检查**。构造器抛出的异常会被 Jackson 包成 `JsonProcessingException`，`EventCodec` 再统一转成 `InvalidEventException`：[EventCodec.java:37](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java#L37)
- 小坑：JSON 里写 `"payload": null` 时，`JsonNode` 类型的字段拿到的不是 Java 的 `null`，而是一个 `NullNode` 对象。所以检查写成 `payload == null || !payload.isObject()`：[EventEnvelope.java:34](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java#L34)
- **后来补的（PR #9）**：Jackson 默认**遇到多出来的字段也会报错**（`FAIL_ON_UNKNOWN_PROPERTIES`）。这和契约"只加字段、旧消费者照样能读"矛盾，第一版漏掉了，是写 Go tracking 的 agent 拿 README 对照时发现的。修法：关掉这个开关。注意它和"必填字段不能缺"是两回事：缺字段靠构造器拦，多字段直接忽略。测试 `newFieldAddedByProducer_isIgnoredByOlderConsumer`。

### 2.4 Jackson 的类型强制转换（coercion）
- coercion = "类型不对时，自动帮你转"。Jackson 默认会把数字 `42` 转成字符串 `"42"`，填进 `String` 字段。
- Jackson 2.12 起有 `CoercionConfig`：可以按"目标类型 + 输入形状"决定怎么处理。`CoercionAction` 有四种：`Fail`（报错）、`TryConvert`（尝试转换）、`AsNull`、`AsEmpty`。
- 我们的设置：`String` 类字段（`LogicalType.Textual`）收到整数、小数、布尔值 → `Fail`：[EventCodec.java:27](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java#L27)
- 为什么要关：Go 的 `encoding/json` 遇到"数字填进 string 字段"会返回 `UnmarshalTypeError`。Java 放过、Go 报错，问题就会藏到集成时才出现。

### 2.5 checked 异常 `InvalidEventException`
- 它继承 `Exception`（不是 `RuntimeException`），所以调用 `EventCodec.parse` 的地方**必须** catch 或者声明 throws，编译器会逼你处理。
- 用途：把"消息本身坏了"和"数据库暂时连不上"分开。前者重试也没用，送进 DLT；后者原地重试（设计规则 6 / ADR-08）。

### 2.6 枚举里写明对应关系
- `DeliveryEventType.valueOf("CREATED")` 按**常量名**找，但常量叫 `DELIVERY_CREATED`，所以找不到。
- 修法：每个常量带一个字段写明对应哪个状态，`fromStatus` 逐个比较：[DeliveryEventType.java:10](../../backend/contracts/src/main/java/com/comp5348/contracts/events/DeliveryEventType.java#L10)、[:35](../../backend/contracts/src/main/java/com/comp5348/contracts/events/DeliveryEventType.java#L35)

### 2.7 Kafka 的 key 和分区
- 每条 Kafka 消息有一个 key。**key 相同的消息进同一个分区，同一个分区里的顺序是有保证的。**
- 所以外壳里的 `orderId` 就是 key：同一个订单的事件按顺序到达（设计规则 5）。不同订单之间没有顺序保证，也不需要。

### 2.8 Gradle 多模块
- `settings.gradle` 里的 `include 'contracts'` 让 Gradle 认出这个模块；`implementation project(':contracts')` 让 delivery 编译时能用它的类。
- 顺带修了一个构建问题：Windows 中文系统上 javac 默认按 GBK 读源码，中文注释会编译失败，所以在根 `build.gradle` 里统一设成 `UTF-8`。

---

## 3. 请求路径导读

这一步还没接上 Kafka（那是第 2 波），所以这里画的是**以后**消息会走的路，标出这一步写的代码在哪一环：

```
store 的业务事务
  └─ new EventEnvelope(...)            ← 紧凑构造器检查（写的一方被保护）
  └─ EventCodec.write(event)           ← 转成 JSON，存进 event_outbox
        │
store 的事件投递程序 ── 发到 Kafka，key = orderId
        │
Kafka topic: order-events（同一 orderId → 同一分区 → 有序）
        │
delivery 的消费者
  └─ EventCodec.parse(json)            ← Jackson 读 JSON（关掉了类型自动转换）
        └─ 调 EventEnvelope 构造器     ← 检查 5 个字段
        ├─ 抛 InvalidEventException → 送进 order-events.DLT + 告警，继续下一条
        └─ 正常 → EventCodec.payload(event, OrderPaidPayload.class) → 业务逻辑建配送单
```

---

## 4. 为什么这样用

| 选择 | 替代方案 | 取舍 |
|---|---|---|
| 🤖 检查写在 record 的**构造器**里 | ① 在 `EventCodec.parse` 里逐个字段检查 ② 用 Bean Validation（`@NotNull` + `Validator`） | 构造器检查对"读"和"写"都生效，不会被绕过。① 只保护读的一方；② 要额外引入依赖，而且要记得手动调用 `validate()`，忘了就不检查 |
| 🤖 关掉 Jackson 的数字→字符串转换 | 保持默认，靠 Go 那边自己报错 | 默认更宽松、更"能用"，但会让 Java 和 Go 对同一条消息给出不同的结果。契约就应该两边一样严格 |
| 🤖 共享 Java 模块 | 每个服务各写一份 DTO（原来的做法）；或者用 JSON Schema / Avro 生成代码 | 一个仓库里写一份最省事，改了字段编译器会提醒。代价：服务之间多了一点耦合，所以只放格式、不放逻辑 |
| 🤖 orderId、金额用字符串 | 用 JSON 数字 | 字符串在 Java、Go、JavaScript 里都不会丢精度；Kafka 的 key 本来就是字节/字符串 |
| 🤖 不认识的 eventType 忽略，不算坏消息 | 当成坏消息送 DLT | 以后新增事件时，旧的消费者不会把它们全部扔进 DLT |

---

## 5. 八股在这里

八股是按"题目"背的，面试是按"场景"问的。这一节从这一步**真写过的代码**出发，指出哪一行碰到了哪道题，并且回答：这里选了什么、什么时候要换成"优化版"、哪里**不需要**优化版。

> 📒 = 你的《Macquarie-Round1-Java八股》里有，后面是题号。
> ➕ = 笔记里没有，Claude 补的。以后深入学八股时建议补进笔记。
>
> **结论先说：这一步没有需要优化的性能问题。** 值得练的是"判断"：为什么现在不用优化版。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 **A2 HashMap / B2 ConcurrentHashMap** | `DeliveryEventType.fromStatus()`：逐个比较 6 个值（[DeliveryEventType.java:35](../../backend/contracts/src/main/java/com/comp5348/contracts/events/DeliveryEventType.java#L35)） | **现在**：6 个值，循环最简单。**什么时候换**：调用量非常大时，可以在类加载时建一个 `static final Map<String, DeliveryEventType>` 当索引，查找从逐个比较变成一次 hash。**不需要 ConcurrentHashMap**：这个 Map 建好后只读，多个线程只读是安全的。只有多个线程**同时写**（比如一个会不断加东西的缓存）才需要它 |
| 📒 **B1 / D2 线程安全** + ➕ **ObjectMapper 线程安全** | `EventCodec` 里只有一个 `static final ObjectMapper`，所有线程共用（[EventCodec.java:15](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java#L15)） | ObjectMapper 创建很贵（要扫描类、建缓存），所以只建一个。**配置好以后**，多个线程同时用它读写 JSON 是安全的。坑：不能在用的过程中再改配置（比如某个请求里调 `MAPPER.configure(...)`），会影响所有线程。跟 D2 "singleton bean 是不是线程安全"是同一个道理：没有可变状态，才线程安全 |
| 📒 **A1 equals / hashCode** | record 按所有字段自动生成 `equals` 和 `hashCode` | 所以 payload 可以直接当 HashMap 的 key，或者放进 HashSet 去重。手写的类如果只重写 `equals`、不重写 `hashCode`，放进 HashSet 去重会失效，这就是 A1 的经典坑。record 不会犯这个错 |
| 📒 **A4 checked vs unchecked** | `InvalidEventException extends Exception`（checked） | 故意用 checked：编译器逼消费者单独处理"坏消息"（送 DLT），不会和"暂时失败"（要重试）混在一起。如果用 unchecked，忘了 catch 也能编译，坏消息就会被当成普通异常一直重试 |
| 📒 **A6 Modern Java** | `record` + 紧凑构造器 | 被问"用过哪些新特性"时，能指着一行真代码说：用 record 定义不可变的消息格式，在紧凑构造器里做检查 |
| 📒 **H1 secrets** | 第 1 步 `0d9ea54`：密码改成 `${DB_PASSWORD}`，真实值放在不进 git 的 `backend/.env` | 你在 Macquarie 被问倒的题，这次有了真实例子。还要能讲后半句：**旧密码已经在 git 历史里了，光改代码不够，要把它作废（rotate）** |
| ➕ **BigDecimal 存金额** | payload 里的 `amount` 是 `BigDecimal`，JSON 里写成字符串 `"259.80"` | `double` 存不准小数：`0.1 + 0.2` 得到 `0.30000000000000004`。三个坑：① 用字符串构造 `new BigDecimal("0.1")`，不要用 `new BigDecimal(0.1)`（后者把 double 的误差带了进来）；② 比较大小用 `compareTo`，不要用 `equals`：`new BigDecimal("1.0").equals(new BigDecimal("1.00"))` 是 **false**，因为小数位数不同；③ JSON 里写成字符串，Go 和 JavaScript 读的时候也不会丢精度 |

**场景锚点（英文，面试时直接用）**

> "In my contracts module the status lookup is a plain loop over six values. A map would only help at very high call rates, and since it would be read-only after startup, I wouldn't need a ConcurrentHashMap. I'd only reach for that when several threads write to the same map."

> "There's one shared ObjectMapper. It's expensive to build and thread-safe once it's configured, so the rule is: configure it once at startup and never change it afterwards."

> "Money is BigDecimal, sent as a string in JSON. And I compare amounts with compareTo, not equals, because equals also compares the scale."

---

## 6. 面试追问链

**链 A：契约**
1. 服务之间的消息格式怎么保证两边一致？→ 共享 contracts 模块 + 两种语言读同一批示例 JSON 的契约测试。
2. 为什么不让每个服务自己写 DTO？→ 可以，但没有东西检查两份是否一致。
3. 共享模块不会让服务耦合吗？→ 会一点。所以只放格式，不放业务逻辑；改格式只加字段，不删不改。
4. 那如果要删一个字段呢？→ 先让所有消费者不再读它，部署完，再让生产者不再写。（这次没做版本号，属于已知的取舍。）

**链 B：Jackson**
1. 缺字段时 Jackson 怎么处理 record？→ 传 null，不报错。
2. 那你怎么拦？→ 紧凑构造器里检查。Jackson 一定会调构造器，所以读的时候自动检查。
3. 为什么 `orderId: 42` 能通过构造器检查？→ Jackson 在调构造器**之前**就把 42 转成了 `"42"`，构造器看到的是正常字符串。
4. 怎么关？→ `coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)`。

**链 C：坏消息怎么处理**
1. 读到坏消息怎么办？→ 抛 `InvalidEventException`，消费者把它送进 DLT 并告警，继续处理下一条。
2. 为什么不重试？→ 消息本身坏了，重试一万次也一样，反而会堵住整个分区。
3. 那数据库连不上呢？→ 那是暂时性失败，原地重试，卡住这个分区，保住顺序（ADR-08）。
4. 怎么区分这两种？→ 不同的异常类型。`InvalidEventException` 是 checked 的，编译器逼你单独处理。

---

## 7. 英文说法

> "Services talk through Kafka events, so I put the event formats in one shared contracts module. Every event has the same envelope: an event ID for de-duplication, the type, the order ID as the Kafka key, a timestamp, and the payload."

> "The envelope is a Java record, and it checks its fields in the compact constructor. Jackson always calls that constructor, so a malformed message is rejected as soon as it's parsed, and the consumer sends it to the dead-letter topic."

> "I also turned off Jackson's number-to-string coercion. Our tracking service is in Go, and Go rejects a number in a string field. I wanted Java to be just as strict, so a bad producer fails in its own tests, not in production."

---

## 8. 资料

- Java record 和紧凑构造器（Oracle 官方，Java 17）："The Canonical Constructor of a Record Class"一节
  https://docs.oracle.com/en/java/javase/17/language/records.html
- Jackson 2.12 发布说明：`CoercionConfig` 系统、record 支持
  https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.12
- 引入 `CoercionConfig` 的 Jackson issue（`CoercionAction` 四种取值的来源）
  https://github.com/FasterXML/jackson-databind/issues/2113
- Go `encoding/json` 的 `Unmarshal`：类型不匹配时返回 `UnmarshalTypeError`
  https://pkg.go.dev/encoding/json#Unmarshal
- Kafka 官方介绍：同一个 key 进同一个分区，分区内有序
  https://kafka.apache.org/intro
- microservices.io：Transactional Outbox（第 2 波会用到，这里的 `EventCodec.write` 就是写进 outbox 的那一步）
  https://microservices.io/patterns/data/transactional-outbox.html

---

## 9. 自测题

**1. 把 `EventEnvelope` 的紧凑构造器整段删掉，哪些测试会变红？`orderId_sentAsJsonNumber_isRejected` 会红吗？**

<details><summary>答案</summary>

缺字段（5 个）、null 字段（5 个）、payload 不是对象（1 个）会变红。
`orderId_sentAsJsonNumber_isRejected` **不会红**：它是在 `EventCodec` 里靠关掉类型自动转换拦下来的，跟构造器无关。这两道防线各管各的。
</details>

**2. 为什么 `"payload": null` 不能只写 `payload == null` 来检查？**

<details><summary>答案</summary>

payload 的类型是 `JsonNode`。JSON 里显式写 null 时，Jackson 给的是 `NullNode` 对象，不是 Java 的 `null`，所以 `== null` 判断不出来。要用 `isObject()`。
</details>

**3.（要真懂才答得出）消费者读到一条 `orderId` 是数字的消息。它应该重试，还是送进 DLT？如果是数据库连不上，又该怎么办？两种处理为什么不一样？**

<details><summary>答案</summary>

`orderId` 是数字：抛 `InvalidEventException`，**送进 DLT**。消息本身坏了，重试多少次结果都一样，留着会堵住整个分区。
数据库连不上：**原地重试**，不提交 offset。这是暂时性失败，等一会儿就好；而且卡住这个分区才能保证同一个订单的事件不乱序。
区别就在于"重试有没有可能成功"（设计 ADR-08、规则 6）。
</details>

---

## 10. 预计回放时间

**30 分钟**：checkout 红 → 跑 → checkout 绿 → 跑 → 读第 2 节 → 改坏练习（删掉构造器检查，或者删掉 coercion 那一行，各跑一次）→ 讲英文说法 → 做题。

回放命令（在 `backend`，PowerShell）：

```
git checkout a504482
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
git checkout b8c92d9
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
git checkout contracts
```
