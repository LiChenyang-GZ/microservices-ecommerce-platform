# tracking · order timeline service (Go)

**English** | [中文](README.zh-CN.md)

Subscribes to `order-events` and `delivery-events`, stores every event in `tracking_db`, and returns an order's timeline.
Design: the *COMP5348 电商架构* artifact, ADR-07, ADR-08, ADR-11, ADR-14, rules 4, 5, 6, interactions 8 and 12.
Event format: [`backend/contracts`](../contracts/README.md) (this service only reads its `examples/`; it never changes the contract).
Study notes: [docs/learning/03-go-tracking-consumer.md](../../docs/learning/03-go-tracking-consumer.md).

---

## 1. API

```
GET /api/tracking/orders/{orderId}/timeline
Authorization: Bearer <token issued by store>

200 [{ "eventType": "OrderPlaced", "source": "order-events", "occurredAt": "2026-10-19T02:01:00Z" }, ...]
```

| Case | Response |
|---|---|
| OK | `200`, sorted by `occurredAt`; on ties `order-events` first, then offset |
| No token / store says the token is invalid | `401` |
| The order belongs to someone else, or its OrderPlaced has not arrived | `404` (same answer for both) |
| Store unreachable, timed out (2s) or 5xx; or a database error | `503`, with no internal error text |

Plus `GET /healthz` (process is up) and `GET /readyz` (database answers) for Docker and Kubernetes.

## 2. Consumer rules

| Message | What happens | Design |
|---|---|---|
| Valid event | `INSERT … ON CONFLICT (event_id) DO NOTHING`; the offset is committed only after the write | rule 4 |
| Duplicate eventId (any letter case) | nothing changes | rule 4 |
| Broken message (not JSON, missing fields, eventId not a UUID, event on the wrong topic) | written unchanged to `<topic>.DLT` with the reason in headers; move on | rule 6, ADR-08 |
| Valid envelope the database permanently rejects (Postgres error class 22, e.g. a NUL character, invalid UTF-8) | dead-lettered like a broken message; move on | rule 6, failure scenario #40 |
| Unknown eventType | logged and skipped, not dead-lettered | contracts §2 |
| Database or DLT temporarily unavailable | retried in place with backoff (200 ms doubling to 10 s); not committed, not skipped | rule 6, ADR-08 |
| SIGTERM | stop taking records; the one in hand gets up to 10 s to finish and commit; leave the group; HTTP finishes in-flight requests, then closes the port | design, "Go service" |

DLT header names and encodings follow Spring Kafka's `DeadLetterPublishingRecoverer`: `kafka_dlt-original-topic`, `kafka_dlt-original-partition` (4-byte big-endian), `kafka_dlt-original-offset` (8-byte big-endian), `kafka_dlt-original-consumer-group`, `kafka_dlt-exception-message`.

## 3. Configuration (environment variables)

| Variable | Required | Default | Notes |
|---|---|---|---|
| `DATABASE_URL` | ✅ | — | e.g. `postgres://tracking_user:…@postgres:5432/tracking_db` |
| `KAFKA_BROKERS` | ✅ | — | comma separated |
| `STORE_URL` | ✅ | — | token check, e.g. `http://store:8082` |
| `HTTP_ADDR` | | `:8085` | |
| `KAFKA_GROUP` | | `tracking` | consumer group |
| `KAFKA_SESSION_TIMEOUT` | | client default | e.g. `10s` |

A missing required variable stops the service at startup (exit code 2) and names every missing one. The service creates its own table (`CREATE TABLE IF NOT EXISTS`).

## 4. Layout

```
cmd/tracking/        main: config, SIGTERM; `tracking healthcheck` for Docker
internal/event/      envelope parsing (same rules as Java's EventCodec)
internal/store/      Postgres: schema, de-duplicating insert, timeline, order owner
internal/consumer/   franz-go consumer: two kinds of failure, DLT, manual commits, graceful shutdown
internal/auth/       token check against store
internal/httpapi/    timeline API, /healthz, /readyz
internal/app/        wiring; crash_on.go is only built with -tags crashtest, for the crash tests
internal/integration/ integration tests with real Kafka + real Postgres
internal/examples/   test helper that reads contracts/examples
```

## 5. Tests

```powershell
# unit + HTTP tests (seconds, no Docker)
go test ./...

# plus integration tests (real Postgres + Kafka via Testcontainers; Docker Desktop running, about 2–3 minutes)
go test -tags integration -count=1 ./...
```

Docker Desktop on Windows: the test entry point sets `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`; without it Testcontainers' helper container (Ryuk) cannot start ([internal/examples/docker.go](internal/examples/docker.go)).

## 6. Image

```powershell
docker build -t tracking:dev .
```

Multi-stage: `golang:1.27` builds a static binary → `gcr.io/distroless/static-debian12:nonroot`. Binary layer 15.7 MB, 6.6 MB compressed, runs as uid 65532. Distroless has no shell or curl, so `HEALTHCHECK` runs `/tracking healthcheck`.
Not in `backend/compose.yml` yet (added at integration).

## 7. Known limits

- If store's and delivery's clocks drift, events from the two topics can appear out of order (G8).
- Replay only recovers what Kafka still keeps, 7 days by default; a full replay needs `retention.ms=-1` on the topics (G6, set when the topics are created in wave 2).
- Every query calls store to check the token; while store is down the API returns 503 (ADR-14, kept on purpose).
