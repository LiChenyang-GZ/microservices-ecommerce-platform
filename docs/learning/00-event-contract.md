# 00 · Event contract: stop bad messages at the door

**English** | [中文](00-event-contract.zh-CN.md)

> Commits: `a504482 test(red)` → `b8c92d9 feat`
> Replay time: **30 minutes**
> The contract itself (every field, every event) is described in [contracts/README.md](../../backend/contracts/README.md). This doc covers only **the concepts learned in this step**.

---

## 1. Problem

**When services pass messages through Kafka, a malformed message gets quietly accepted and only fails several steps later, or only fails on the other language's side (Go).**

Tests that reproduce it (14 red at `test(red)`):
- a missing field is accepted as null: [EventEnvelopeContractTest.java:46](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L46), [:61](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L61)
- `orderId: 42` (a number) is silently turned into `"42"`: [:71](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L71)
- a payload sent as a string is accepted: [:84](../../backend/contracts/src/test/java/com/comp5348/contracts/events/EventEnvelopeContractTest.java#L84)
- delivery status `CREATED` has no matching event `DeliveryCreated`: [DeliveryEventContractTest.java:22](../../backend/deliveryService/src/test/java/com/comp5348/delivery/contracts/DeliveryEventContractTest.java#L22)

---

## 2. What it is (framework level)

### 2.1 Contract
**Plainly**: the side that writes a message and the side that reads it agree up front on what it looks like.
There are two here: the JSON format of Kafka events (`EventEnvelope` + payload), and the gRPC interface store uses to call bank (`bank.proto`).
They live in a separate Gradle module, `contracts`, and every service references the same copy with `implementation project(':contracts')`.

### 2.2 Java records and the compact constructor
- A `record` (standard since Java 16) is a class that only holds data: you list the fields, and the compiler generates the constructor, accessors (`eventId()`), `equals`, `hashCode` and `toString`. Fields are `final`, so the object can't change once built.
- **Compact constructor**: inside the record you write `public EventEnvelope { ... }` with no parameter list. It runs **before** the fields are assigned, which makes it the place for checks. If a check fails it throws, and the object is never created.
- Code: [EventEnvelope.java:28](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java#L28)

### 2.3 How Jackson turns JSON into a record
- Jackson is Spring Boot's default JSON library. `ObjectMapper.readValue(json, EventEnvelope.class)` reads the JSON, finds the record's constructor, and passes in the values by field name.
- **When a field is missing from the JSON, Jackson does not fail; it passes null.** That is the cause of the first group of red tests.
- Because Jackson always goes through the constructor, a check in the compact constructor **runs automatically on every parse**. Jackson wraps the constructor's exception in a `JsonProcessingException`, and `EventCodec` turns that into an `InvalidEventException`: [EventCodec.java:37](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java#L37)
- Gotcha: for `"payload": null`, a `JsonNode` field receives a `NullNode` object, not Java `null`. So the check is `payload == null || !payload.isObject()`: [EventEnvelope.java:34](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java#L34)

### 2.4 Jackson coercion
- Coercion = "the type is wrong, so convert it for you". By default Jackson turns the number `42` into the string `"42"` for a `String` field.
- Since Jackson 2.12 there is `CoercionConfig`, which decides what to do per target type and input shape. `CoercionAction` has four values: `Fail`, `TryConvert`, `AsNull`, `AsEmpty`.
- Our setting: `String`-like fields (`LogicalType.Textual`) receiving an integer, float or boolean → `Fail`: [EventCodec.java:27](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java#L27)
- Why turn it off: Go's `encoding/json` returns an `UnmarshalTypeError` for a number in a string field. If Java accepts what Go rejects, the problem hides until integration.

### 2.5 Checked exception `InvalidEventException`
- It extends `Exception` (not `RuntimeException`), so code that calls `EventCodec.parse` **must** catch it or declare it; the compiler forces you to deal with it.
- Purpose: separate "the message itself is broken" from "the database is temporarily down". The first can never succeed on retry, so it goes to the DLT; the second is retried in place (design rule 6 / ADR-08).

### 2.6 Explicit mapping inside an enum
- `DeliveryEventType.valueOf("CREATED")` looks up a **constant name**, but the constant is `DELIVERY_CREATED`, so it fails.
- Fix: each constant carries a field naming its status, and `fromStatus` compares them one by one: [DeliveryEventType.java:10](../../backend/contracts/src/main/java/com/comp5348/contracts/events/DeliveryEventType.java#L10), [:35](../../backend/contracts/src/main/java/com/comp5348/contracts/events/DeliveryEventType.java#L35)

### 2.7 Kafka keys and partitions
- Every Kafka message has a key. **Messages with the same key go to the same partition, and order within a partition is guaranteed.**
- So the envelope's `orderId` is the key: events for one order arrive in order (design rule 5). Different orders have no ordering guarantee, and don't need one.

### 2.8 Gradle multi-project build
- `include 'contracts'` in `settings.gradle` makes Gradle see the module; `implementation project(':contracts')` lets delivery compile against its classes.
- A build fix along the way: on Chinese-locale Windows, javac reads sources as GBK by default and Chinese comments fail to compile, so the root `build.gradle` sets `UTF-8` for every module.

---

## 3. Request path

Kafka is not wired up yet (that is wave 2), so this is the path messages **will** take, with this step's code marked:

```
store business transaction
  └─ new EventEnvelope(...)            ← compact constructor checks (protects the writer)
  └─ EventCodec.write(event)           ← to JSON, stored in event_outbox
        │
store event publisher ── sends to Kafka, key = orderId
        │
Kafka topic: order-events (same orderId → same partition → ordered)
        │
delivery consumer
  └─ EventCodec.parse(json)            ← Jackson reads JSON (coercion turned off)
        └─ calls EventEnvelope ctor    ← checks the 5 fields
        ├─ throws InvalidEventException → order-events.DLT + alert, continue with next
        └─ ok → EventCodec.payload(event, OrderPaidPayload.class) → business logic creates the delivery
```

---

## 4. Why this way

| Choice | Alternatives | Trade-off |
|---|---|---|
| 🤖 Checks in the record's **constructor** | ① check each field inside `EventCodec.parse` ② Bean Validation (`@NotNull` + `Validator`) | A constructor check applies to both reading and writing and can't be bypassed. ① only protects the reader; ② adds a dependency and only runs if someone remembers to call `validate()` |
| 🤖 Turn off Jackson's number→string coercion | keep the default and let Go fail on its own | The default is more forgiving, but then Java and Go disagree about the same message. A contract should be equally strict on both sides |
| 🤖 Shared Java module | each service writes its own DTOs (the original approach); or generate code from JSON Schema / Avro | One copy in one repo is simplest, and the compiler flags changed fields. Cost: a little coupling between services, so only formats go here, never logic |
| 🤖 orderId and amounts as strings | JSON numbers | Strings never lose precision in Java, Go or JavaScript; Kafka keys are bytes/strings anyway |
| 🤖 Unknown eventType is ignored, not sent to the DLT | treat it as a broken message | When a new event type is added later, older consumers won't dump it all into the DLT |

---

## 5. Interview follow-up chains

**Chain A: the contract**
1. How do you keep message formats consistent between services? → A shared contracts module, plus contract tests in both languages that read the same example JSON.
2. Why not let each service write its own DTOs? → You can, but nothing checks that the copies match.
3. Doesn't a shared module couple the services? → A little. So it holds formats only, no business logic, and changes only add fields.
4. What if you need to remove a field? → First make every consumer stop reading it and deploy, then make the producer stop writing it. (No version field this time; a known trade-off.)

**Chain B: Jackson**
1. What does Jackson do with a missing field on a record? → Passes null, no error.
2. How do you catch it? → Check in the compact constructor. Jackson always calls it, so every parse is checked.
3. Why does `orderId: 42` pass the constructor check? → Jackson turns 42 into `"42"` **before** calling the constructor, so the constructor sees a normal string.
4. How do you stop that? → `coercionConfigFor(LogicalType.Textual).setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)`.

**Chain C: handling bad messages**
1. What happens to a broken message? → `InvalidEventException`; the consumer sends it to the DLT, raises an alert, and moves on.
2. Why not retry? → The message itself is broken; retrying forever gives the same result and blocks the whole partition.
3. And if the database is down? → That's a transient failure: retry in place and hold the partition so ordering is kept (ADR-08).
4. How do you tell them apart? → Different exception types. `InvalidEventException` is checked, so the compiler forces separate handling.

---

## 6. How to say it

> "Services talk through Kafka events, so I put the event formats in one shared contracts module. Every event has the same envelope: an event ID for de-duplication, the type, the order ID as the Kafka key, a timestamp, and the payload."

> "The envelope is a Java record, and it checks its fields in the compact constructor. Jackson always calls that constructor, so a malformed message is rejected as soon as it's parsed, and the consumer sends it to the dead-letter topic."

> "I also turned off Jackson's number-to-string coercion. Our tracking service is in Go, and Go rejects a number in a string field. I wanted Java to be just as strict, so a bad producer fails in its own tests, not in production."

---

## 7. References

- Java records and compact constructors (Oracle, Java 17), section "The Canonical Constructor of a Record Class"
  https://docs.oracle.com/en/java/javase/17/language/records.html
- Jackson 2.12 release notes: the `CoercionConfig` system and record support
  https://github.com/FasterXML/jackson/wiki/Jackson-Release-2.12
- The Jackson issue that introduced `CoercionConfig` (where the four `CoercionAction` values come from)
  https://github.com/FasterXML/jackson-databind/issues/2113
- Go `encoding/json` `Unmarshal`: returns `UnmarshalTypeError` on a type mismatch
  https://pkg.go.dev/encoding/json#Unmarshal
- Kafka introduction: same key → same partition, ordered within a partition
  https://kafka.apache.org/intro
- microservices.io: Transactional Outbox (used in wave 2; `EventCodec.write` is the step that writes into the outbox)
  https://microservices.io/patterns/data/transactional-outbox.html

---

## 8. Self-check

**1. If you delete `EventEnvelope`'s compact constructor, which tests go red? Does `orderId_sentAsJsonNumber_isRejected` go red?**

<details><summary>Answer</summary>

Missing field (5), null field (5) and payload-not-an-object (1) go red.
`orderId_sentAsJsonNumber_isRejected` **stays green**: it is caught in `EventCodec` by turning off coercion, which has nothing to do with the constructor. The two defences are independent.
</details>

**2. Why can't you check `"payload": null` with just `payload == null`?**

<details><summary>Answer</summary>

payload is a `JsonNode`. For an explicit JSON null, Jackson gives a `NullNode` object, not Java `null`, so `== null` misses it. Use `isObject()`.
</details>

**3. (Needs real understanding) A consumer reads a message whose `orderId` is a number. Retry, or send to the DLT? What if the database is down instead? Why are the two handled differently?**

<details><summary>Answer</summary>

`orderId` is a number: throw `InvalidEventException` and **send it to the DLT**. The message itself is broken; no number of retries will change it, and keeping it would block the partition.
Database down: **retry in place** without committing the offset. It is transient and will recover, and holding the partition keeps one order's events in sequence.
The difference is whether a retry can ever succeed (design ADR-08, rule 6).
</details>

---

## 9. Replay time

**30 minutes**: checkout red → run → checkout green → run → read section 2 → break-it exercise (remove the constructor check, or the coercion line, and run each time) → say the English lines → self-check.

Replay commands (from `backend`, PowerShell):

```
git checkout a504482
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
git checkout b8c92d9
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
git checkout contracts
```
