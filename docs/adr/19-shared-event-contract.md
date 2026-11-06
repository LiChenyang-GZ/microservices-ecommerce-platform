# ADR-19 · One event contract, shared by Java and Go

**Status:** Accepted

## Context
Before the contract existed, a malformed event could be accepted quietly and fail several steps later, or fail only on the Go side. Examples: a missing field read as null, `orderId: 42` silently turned into `"42"`, a payload sent as a string accepted as-is.

## Decision
- A `contracts` module holds the event envelope (`eventId`, `eventType`, `orderId`, `occurredAt`, `payload`), the topic names, one payload type per event, and `bank.proto`.
- `EventCodec` is the only way the Java services read and write events. It rejects missing fields and wrong types; such a message is an `InvalidEventException` and goes to the DLT (ADR-08).
- Unknown fields are ignored, so a producer can add a field without breaking older consumers.
- Example JSON for every event type lives in `contracts/examples/`. Java contract tests and Go tests both decode the same files, so the two parsers cannot drift apart.

## Consequences
- A broken message fails at the first consumer, with a clear reason.
- Changing an event means changing the contract module, its examples and the Go parser together.

## In the code
- [backend/contracts](../../backend/contracts/README.md): [EventEnvelope.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventEnvelope.java), [EventCodec.java](../../backend/contracts/src/main/java/com/comp5348/contracts/events/EventCodec.java), [examples](../../backend/contracts/examples/)
- Go: [event.go](../../backend/trackingService/internal/event/event.go), [examples_test.go](../../backend/trackingService/internal/event/examples_test.go)
- [docs/learning/00-event-contract.md](../learning/00-event-contract.md)
