# contracts · message formats between services

**English** | [中文](README.zh-CN.md)

This module holds **formats only**: what each Kafka event looks like, and the gRPC interface store uses to call bank.
It has no business logic and never runs on its own. A service uses it by adding `implementation project(':contracts')` to its `build.gradle`.

> **For agents working in parallel**: once wave 0 is merged, **nobody changes this module**. If you need a change, stop and report it to Christy.

Source of truth: the design artifact *COMP5348 电商架构*. Wherever this file says "Design: …", it comes from there.
Items marked 🤖 are not in the design; Claude decided them and Christy confirmed them before merging.

---

## 1. Layout

```
contracts/
├── src/main/java/com/comp5348/contracts/events/
│   ├── EventEnvelope.java       event envelope (5 fields, checked on construction)
│   ├── EventCodec.java          the only way to read/write event JSON (uses Jackson, see below)
│   ├── InvalidEventException    the message itself is broken → consumer sends it to the DLT
│   ├── Topics.java              topic names
│   ├── OrderEventType.java      the 5 events on order-events
│   ├── DeliveryEventType.java   the 6 events on delivery-events
│   └── payload/                 one payload type per event
├── src/main/proto/bank/v1/bank.proto   store → bank gRPC interface
├── examples/                    one example JSON per event (read by both Java and Go tests)
└── src/test/                    contract tests
```

---

## 2. Event envelope

Design: "every event uses the same envelope: eventId, eventType, orderId, occurredAt, payload".

| Field | JSON type | Example | Used for | Source |
|---|---|---|---|---|
| `eventId` | string (UUID) | `"3f1c2a6e-…"` | consumers de-duplicate on it | Design + rule 4 |
| `eventType` | string | `"OrderPaid"` | consumers decide what to do with it | Design |
| `orderId` | **string** | `"42"` | Kafka key (one order → one partition → ordered) | Design + rule 5; string type is 🤖 |
| `occurredAt` | string (ISO-8601, UTC) | `"2026-10-19T02:02:00Z"` | tracking sorts the timeline by it | Design + rule 5 |
| `payload` | **object** | `{ … }` | event-specific fields, see section 3 | Design |

**All five fields are required.** A missing field, a null value, `orderId` sent as a number, or a `payload` that is not an object all make `EventCodec.parse` throw `InvalidEventException`. The consumer then sends the message to `<topic>.DLT` and moves on to the next one (rule 6).

### Usage

```java
// Producer (store / delivery event publisher)
EventEnvelope event = new EventEnvelope(
        UUID.randomUUID(),
        OrderEventType.ORDER_PAID.wireName(),
        String.valueOf(order.getId()),
        Instant.now(),
        EventCodec.toTree(new OrderPaidPayload(...)));
String json = EventCodec.write(event);         // goes into event_outbox; Kafka key = event.orderId()

// Consumer
try {
    EventEnvelope event = EventCodec.parse(json);
    OrderPaidPayload p = EventCodec.payload(event, OrderPaidPayload.class);
    // ...business logic...
} catch (InvalidEventException e) {
    // broken message: send to DLT + alert, do not retry
}
```

**Unknown `eventType`: ignore it; it is not a broken message.** Per the design, delivery only handles OrderPaid and OrderCancelled and ignores the rest.

---

## 3. Payloads

Money is always a **string**, e.g. `"259.80"` (🤖: no floating-point error, and Go keeps full precision).

### order-events (written by store; read by delivery, tracking)

| Event | When it is written | Payload fields | Source |
|---|---|---|---|
| `OrderPlaced` | in the place-order transaction | `userId`, `productId`, `quantity`, `totalAmount` | Sequence A step 2; fields 🤖 |
| `OrderPaid` | when the charge succeeds and the order is still pending, same transaction | `paymentId`, `amount`, `bankTxnId`, `customerEmail`, `customerName`, `toAddress`, `fromAddresses`, `productName`, `quantity` | Rule 1, sequence A. Delivery fields copy today's `DeliveryRequestDTO`, so delivery can create the delivery without calling store back |
| `PaymentFailed` | only when bank **definitely rejects** (a timeout is not a failure) | `paymentId`, `attempt`, `reason` | Sequence A, rule 7 |
| `OrderCancelled` | in the cancel transaction | `reason`: `USER_REQUEST` or `PAYMENT_TIMEOUT` | Sequence C, cancel API; values 🤖 |
| `OrderRefunded` | in the refund-succeeded transaction | `paymentId`, `amount`, `refundTxnId`, `reason`: `ORDER_CANCELLED` / `DELIVERY_LOST` / `PAID_AFTER_CANCEL` | Sequence B; the three reasons are the design's "three cases that need a refund" |

### delivery-events (written by delivery; read by store, tracking)

All six events **share one payload**: `deliveryId`, `status`.

| Event | `DeliveryStatus` | Source |
|---|---|---|
| `DeliveryCreated` | `CREATED` | Design: "event names map one-to-one to the `DeliveryStatus` enum" |
| `PickedUp` | `PICKED_UP` | same |
| `Delivering` | `DELIVERING` | same |
| `Received` | `RECEIVED` | same |
| `Lost` | `LOST` | same |
| `Cancelled` | `CANCELLED` | same |

- The names are not identical (`CREATED` ↔ `DeliveryCreated`), so use `DeliveryEventType.fromStatus(status.name())`; never build the name by hand.
- **`Cancelled` may have a null `deliveryId`**: the order was cancelled before delivery read OrderPaid, so delivery only wrote a cancel marker and no delivery exists yet (ADR-09, sequence C).
- Order store uses for "never move backwards": `CREATED < PICKED_UP < DELIVERING < terminal (RECEIVED / LOST / CANCELLED)` (rule 5).

---

## 4. bank.proto (store → bank)

Design: store calls bank over gRPC (ADR-10); idempotency keys in ADR-04; timeouts in ADR-05 / rule 7.

| Method | Request | Idempotency key |
|---|---|---|
| `Transfer` | `idempotency_key`, `from_account`, `to_account`, `amount` | `PAY-{paymentId}-{attempt}` |
| `Refund` | `idempotency_key`, `original_transaction_id`, `reason` | `REFUND-{paymentId}` |

**How to read the result** (the most important rule here):

| bank returns | Meaning | What store does |
|---|---|---|
| `outcome = SUCCEEDED` | success | continue the success path |
| `outcome = REJECTED` | definite failure (e.g. insufficient funds) | charge: mark FAILED; refund: stop + alert |
| gRPC error (`DEADLINE_EXCEEDED`, `UNAVAILABLE`, …) | **result unknown** | retry with the **same key**; after 5 tries mark `PAY_UNKNOWN` / `REFUND_UNKNOWN`. **Never treat it as a failure** |

🤖: only two outcomes, SUCCEEDED / REJECTED; "unknown" is signalled by gRPC's own status codes, not by an extra field.
🤖: refunds are always full refunds, so `Refund` has no amount.

**Step 2 still uses HTTP** (gRPC comes in step 6). When step 2 changes bank's REST API, name request and response fields **after this proto** (camelCase in JSON: `idempotencyKey`, `fromAccount`, …). Then step 6 only swaps the protocol; no field changes.

---

## 5. Examples in `examples/`

One file per event, named `<topic>-<eventType>.json`.

- Java: `EventEnvelopeContractTest` reads every file and checks that it parses, its payload converts to the right type, and writing it back gives the same JSON.
- Go (wave 1, agent C): tracking's tests read the same files. **Both languages test against the same samples**, so they agree on the format.

When you add or change an event, update its example too, or `examples_coverEveryEventType` goes red.

---

## 6. Failure scenarios (step 0)

| Scenario | How the design handles it | Test that proves it | Result |
|---|---|---|---|
| Envelope field missing | all 5 fields required; broken messages go to the DLT (rule 6) | `eventEnvelope_missingRequiredField_failsValidation` (×5) | red, then green ✅ |
| Envelope field is null | same | `eventEnvelope_nullRequiredField_failsValidation` (×5) | red, then green ✅ |
| orderId sent as a number, Go can't read it | contract says string 🤖 | `orderId_sentAsJsonNumber_isRejected` | red, then green ✅ |
| payload is not an object | envelope rule | `payload_notAnObject_isRejected` | red, then green ✅ |
| Delivery events don't match `DeliveryStatus` | design: one-to-one | `everyDeliveryStatus_hasMatchingEventType`, `deliveryStatusToEvent_isOneToOne` | red, then green ✅ |
| Example files drift from the Java types | — | `everyExample_parsesIntoItsPayloadType_andRoundTrips` (×11) | always green (safety net) |
| An event has no example file | — | `examples_coverEveryEventType` | always green (safety net) |
| store and bank use different `.proto` files | ADR-10 | there is one `.proto`; both sides generate code from it; it compiles | 📋 |

**How to run** (from `backend`, PowerShell):

```
.\gradlew :contracts:test :deliveryService:test --tests "*DeliveryEventContractTest" --continue
```

> Don't pass `"*Contract*"` to `--tests`: on Windows, Java expands it as a file wildcard and it matches the `contracts` folder.

---

## 7. 🤖 Decisions Claude made (confirmed before merge)

1. `orderId` and money amounts are strings.
2. Java event types live in this shared module and every service uses the same copy; Go stays in sync through the example JSON. Cost: changing the contract recompiles every service, which is why only formats live here, never business logic.
3. The exact payload fields and the `reason` values (the design only names the events).
4. gRPC has two outcomes, SUCCEEDED / REJECTED; refunds carry no amount.
5. No version field. Format changes only **add** fields and never rename or remove existing ones, so older consumers keep working. To make that true, `EventCodec` **skips fields it doesn't know**; `newFieldAddedByProducer_isIgnoredByOlderConsumer` guards this.
   - Background: `EventCodec` reads and writes JSON with **Jackson**, Spring Boot's default JSON library, which turns JSON text into Java objects and back. Its default rule is "a field that is in the JSON but not in the Java class is an error", so before the fix, one extra field made the whole message count as broken and go to the DLT. The agent writing the Go tracking service found this by checking its parser against this README.
