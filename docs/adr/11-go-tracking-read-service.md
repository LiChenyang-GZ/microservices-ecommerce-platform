# ADR-11 · The order timeline is a separate read service in Go

**Status:** Accepted

## Context
Customers want to see everything that happened to an order. Building that view inside store would add read load and code to the service that takes payments.

## Decision
tracking, written in Go, subscribes to both topics, stores every event in its own database, and offers one endpoint: `GET /api/tracking/orders/{orderId}/timeline`. It checks the caller's token with store and only shows an order to the user who placed it.

## Consequences
- A read model in the CQRS sense: it cannot affect ordering or payment, and if its data is wrong it can be emptied and rebuilt by replaying the topics.
- The Go image is small (distroless, static binary) and starts fast.
- A second language to maintain. Event parsing must match Java's exactly; both run the same example events (ADR-19).
- If store is down, the timeline answers 503 because the token cannot be checked.

## In the code
- [backend/trackingService](../../backend/trackingService/README.md): [consumer](../../backend/trackingService/internal/consumer/consumer.go), [httpapi](../../backend/trackingService/internal/httpapi/httpapi.go), [auth](../../backend/trackingService/internal/auth/auth.go)
- [docs/learning/03-go-tracking-consumer.md](../learning/03-go-tracking-consumer.md)
