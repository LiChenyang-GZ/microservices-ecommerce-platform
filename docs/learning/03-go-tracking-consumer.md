# 03 · Go tracking: an at-least-once consumer

**English** | [中文](03-go-tracking-consumer.zh-CN.md)

> Commits: `458081c test(red)` empty stubs → `f606749 feat` naive version → `b8d8a06 test(red)` failure scenarios → six `feat` commits (`e3fd626` parsing, `6229390` de-duplication, `bea1a1f` ordering, `5058a89` two kinds of failure, `46b115e` graceful shutdown, `8ee1acc` API) → `9aec05a` image
> Replay time: **60 minutes**
> The service itself (API, config, how to run it) is in [trackingService/README.md](../../backend/trackingService/README.md). This file covers **the concepts this step teaches**.

---

## 1. The problem

**Kafka only promises at-least-once delivery: a message can arrive twice, a broken message stays there forever, there is no order between two topics, and the service can be stopped at any moment. A naive consumer that "works" quietly stores wrong data or loses data in each of these cases.**

On the naive version (`f606749`) 29 tests fail. The most telling ones:
- an event with missing fields is accepted with zero values: [parse_test.go:64](../../backend/trackingService/internal/event/parse_test.go#L64)
- after a crash and restart, the same message is stored twice: [failure_test.go:29](../../backend/trackingService/internal/integration/failure_test.go#L29)
- a broken message is silently dropped; the DLT is empty: [failure_test.go:82](../../backend/trackingService/internal/integration/failure_test.go#L82)
- while the database is down, events are lost: [consumer/failure_test.go:82](../../backend/trackingService/internal/consumer/failure_test.go#L82)
- the timeline follows arrival order, so a replay changes it: [failure_test.go:187](../../backend/trackingService/internal/integration/failure_test.go#L187)
- times come back as Sydney `+11:00`, not the UTC the event says: [store/failure_test.go:114](../../backend/trackingService/internal/store/failure_test.go#L114)
- on shutdown the record in hand is abandoned and the HTTP port stays open: [failure_test.go:233](../../backend/trackingService/internal/integration/failure_test.go#L233), [:273](../../backend/trackingService/internal/integration/failure_test.go#L273)
- store is down, yet the API answers 401: [httpapi/failure_test.go:66](../../backend/trackingService/internal/httpapi/failure_test.go#L66)

---

## 2. What it is (framework level)

### 2.1 Go's `encoding/json`: missing fields are not an error
- `json.Unmarshal` fills a struct from JSON. **A field missing from the JSON keeps its zero value, with no error** (`""` for a string, year 1 for `time.Time`). A JSON `null` into a non-pointer field "has no effect on the value and produces no error" (the official docs).
- So a plain struct cannot tell "not sent" from "sent empty". The fix decodes into a struct of **pointer fields**, `wire`; a missing or null field is `nil`, and each one is checked: [event.go:68](../../backend/trackingService/internal/event/event.go#L68), [:80](../../backend/trackingService/internal/event/event.go#L80)
- `json.RawMessage` keeps the raw JSON text. For `null` it receives the text `null`, so the payload check is "first character is `{`": [event.go:103](../../backend/trackingService/internal/event/event.go#L103)
- Compared with Java: Jackson passes null for a missing field (also no error), so the contracts module checks in the record's constructor (study note 00). Same trap in both languages, fixed in different places.
- The other way round: a Go `string` field rejects a number by itself; Java needs explicit configuration. That is why `TestParse_OrderIdAsNumber_Rejected` is green in Go from the start.

### 2.2 `google/uuid` is looser than Java
- `uuid.Parse` also accepts `urn:uuid:…`, `{…}` and 32 hex digits without dashes. Java's `UUID.fromString` only takes the 36-character form, so the length is checked too: [event.go:89](../../backend/trackingService/internal/event/event.go#L89)

### 2.3 Consumer groups, offsets, commits
- **Consumer group**: instances in one group share the partitions; each partition belongs to one instance at a time. tracking's group is `tracking`.
- An **offset** is a message's position in its partition; **committing offset N** means "this group starts from N next time". Kafka keeps it in the internal `__consumer_offsets` topic.
- **Process first, commit second = at-least-once**: a crash after processing but before the commit means the message comes again. This is what the "Message Delivery Semantics" section of the Kafka docs describes. The reverse order (commit, then process) is at-most-once: a crash loses the message.
- tracking turns autocommit off (`DisableAutoCommit`) and calls `CommitRecords` itself once **every record in the batch is done**: [consumer.go:65](../../backend/trackingService/internal/consumer/consumer.go#L65), [:119](../../backend/trackingService/internal/consumer/consumer.go#L119)
- 🔍 Found by reading the source: franz-go's **default** autocommit only commits what the *previous* poll returned, never the batch being processed. With synchronous processing the default is already at-least-once, so `TestCrashBeforeStore_EventNotLost` is green on the naive version. Adding `kgo.GreedyAutoCommit()` (commit everything fetched) makes it lose messages.

### 2.4 Idempotent writes: `uuid` primary key + `ON CONFLICT DO NOTHING`
- At-least-once means duplicates will happen. The database removes them: `event_id uuid PRIMARY KEY` and `ON CONFLICT (event_id) DO NOTHING`. A duplicate changes nothing and is not an error: [store.go:55](../../backend/trackingService/internal/store/store.go#L55), [:80](../../backend/trackingService/internal/store/store.go#L80)
- Why not an error: the consumer retries on errors, so a duplicate would block its partition forever.
- Why `uuid`, not `text`: `3F1C…` and `3f1c…` are the same UUID; `text` treats them as two.

### 2.5 Rebalances and `BlockRebalanceOnPoll`
- **Rebalance**: the members of the group change (a new pod joins, an old one leaves) and partitions are reassigned.
- Risk: instance A has processed a batch but not committed it when the partition moves to B, which processes it again (2.4 makes that harmless). Worse, A may then commit for a partition it no longer owns.
- `BlockRebalanceOnPoll`: after a poll, a rebalance waits until we call `AllowRebalance`. tracking allows it only after "process → commit", so a partition changes owner only with everything processed already committed: [consumer.go:69](../../backend/trackingService/internal/consumer/consumer.go#L69), [:123](../../backend/trackingService/internal/consumer/consumer.go#L123)
- 🐛 A trap we actually hit: with this option, `Close()` waits for `AllowRebalance` forever. When the shutdown made the poll return early, nobody called it, and the service never exited. The replay and app-shutdown tests caught the regression; the documented exit is `CloseAllowingRebalance()`: [consumer.go:80](../../backend/trackingService/internal/consumer/consumer.go#L80)

### 2.6 Two kinds of failure (rule 6 / ADR-08)
- **The message itself is broken** (unparseable, missing fields, wrong topic): ten thousand retries give the same result → write it unchanged to `<topic>.DLT`, reason in headers, move on: [handler.go:62](../../backend/trackingService/internal/consumer/handler.go#L62), [dlt.go:27](../../backend/trackingService/internal/consumer/dlt.go#L27)
- **Transient failure** (database down, DLT unreachable): it will pass → retry in place, 200 ms doubling to 10 s, no commit, no skip. The partition waits, so one order's events never get out of order: [handler.go:87](../../backend/trackingService/internal/consumer/handler.go#L87)
- **An unknown eventType** is neither: skip it. When a producer adds a new event, an old tracking does not dump them all into the DLT; after upgrading tracking, a replay brings them back.
- DLT headers copy Spring Kafka (`kafka_dlt-original-topic`, partition as a 4-byte big-endian int, …), so wave 2's Java consumers write the same format with the default `DeadLetterPublishingRecoverer`. ⚠️ But Spring Kafka 3.3's default DLT **name** is `<topic>-dlt`, while the contract says `<topic>.DLT`: the Java side must configure the destination.

### 2.7 `context`: the cancel signal
- `context.Context` is Go's standard way to say "stop now". `ctx.Done()` is a channel that is closed on cancel; everything that can block (database, HTTP, Kafka poll, `select`) takes a ctx.
- `signal.NotifyContext`: cancels ctx on SIGTERM or Ctrl+C: [main.go:28](../../backend/trackingService/cmd/tracking/main.go#L28)
- The backoff wait is `select { case <-ctx.Done(): …; case <-time.After(wait): }`, not `time.Sleep`, which cannot hear the shutdown while asleep: [handler.go:105](../../backend/trackingService/internal/consumer/handler.go#L105)
- `context.WithoutCancel(ctx)` gives a ctx that is **not** cancelled with its parent. The record in hand uses it, so it can finish after SIGTERM; `context.AfterFunc` cancels it 10 s after the shutdown so it cannot hang forever: [consumer.go:84](../../backend/trackingService/internal/consumer/consumer.go#L84), [:86](../../backend/trackingService/internal/consumer/consumer.go#L86)

### 2.8 Graceful shutdown
- Kubernetes stops a pod with SIGTERM, then SIGKILL 30 s later if it is still running.
- Consumer: take no new records → finish the one in hand → commit → leave the group (another instance takes over at once instead of waiting for the session timeout).
- HTTP: `http.Server.Shutdown` closes the listeners, then idle connections, then waits for active requests to finish: [app.go:127](../../backend/trackingService/internal/app/app.go#L127). The naive `http.Serve` cannot do that, so the port stayed open after Run returned.
- Evidence: after `docker stop`, the log shows `shutting down` → `tracking stopped`, exit code 0, in 0.5 s.

### 2.9 HTTP: Go 1.22 routing, timeouts, status codes
- `mux.HandleFunc("GET /api/tracking/orders/{orderId}/timeline", …)`: since Go 1.22 the standard router matches methods and `{orderId}` wildcards, read with `r.PathValue("orderId")`. No Gin needed.
- **`http.DefaultClient` has no timeout.** If store hangs, every request hangs with it. The store call uses a client with `Timeout: 2s`: [auth.go:37](../../backend/trackingService/internal/auth/auth.go#L37)
- 401 and 503 are different: store saying "invalid token" is 401 (the frontend asks the user to log in again); store not answering is 503 (try later). The Java services treat both as 401: [auth.go:68](../../backend/trackingService/internal/auth/auth.go#L68)
- Ownership: if OrderPlaced's `userId` is not the token's user, answer 404. "Not yours" and "does not exist" look identical, so nobody can probe order ids: [httpapi.go:66](../../backend/trackingService/internal/httpapi/httpapi.go#L66), [:72](../../backend/trackingService/internal/httpapi/httpapi.go#L72)
- A nil Go slice encodes as JSON `null`, not `[]`, hence `make([]Entry, 0, n)`: [httpapi.go:83](../../backend/trackingService/internal/httpapi/httpapi.go#L83)

### 2.10 Time zones: pgx returns local time
- Postgres `timestamptz` stores an instant, not a zone. pgx returns it as a `time.Time` in the **machine's local zone**. On a Sydney machine it formats as `13:01:00+11:00`: the right instant, but not the `02:01:00Z` the event says, and confusing for the frontend. Fix: `.UTC()` right after reading: [store.go:104](../../backend/trackingService/internal/store/store.go#L104)
- The test sets `time.Local = time.FixedZone("AEDT", 11*3600)`, so the result does not depend on which machine runs it.

### 2.11 Timeline ordering and replay
- There is no order between the two topics, so arrival order means nothing. SQL sorts: `ORDER BY occurred_at, CASE source_topic WHEN 'order-events' THEN 0 ELSE 1 END, kafka_offset`: [store.go:94](../../backend/trackingService/internal/store/store.go#L94)
- The result depends only on the data, not on arrival order, so a **replay** (wipe + read from the start) always gives the same timeline. That is ADR-11's "the read model can be rebuilt" (the read side of CQRS).

### 2.12 Testcontainers for Go and a real crash
- `postgres.Run(...)` / `kafka.Run(...)` start real containers; `TestMain` starts them once for all tests in a package.
- Build tag: the first line `//go:build integration` keeps these files out of a plain `go test ./...`.
- **A crash has to happen in a child process**: the test builds tracking with `-tags crashtest`, runs it, and it calls `os.Exit(3)` at the chosen point. `os.Exit` skips every `defer`: no closing, no leaving the group, no commit, like a power cut. A `cancel()` inside the test process is a polite exit that hides exactly the problem we are looking for: [crash_on.go:31](../../backend/trackingService/internal/app/crash_on.go#L31), [binary_test.go](../../backend/trackingService/internal/integration/binary_test.go)
- Windows trap: Testcontainers mounts the Windows named pipe into its helper container Ryuk, which then cannot start. The test entry point sets `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=/var/run/docker.sock`: [docker.go](../../backend/trackingService/internal/examples/docker.go)

### 2.13 Multi-stage build and distroless
- Stage one, `golang:1.27`, builds with `CGO_ENABLED=0` into a static binary with no C library dependency; stage two copies only that binary into `distroless/static:nonroot`: no shell, no package manager, runs as uid 65532.
- Result: 15.7 MB binary layer, 6.6 MB compressed. No curl, so `HEALTHCHECK` runs the binary's own `healthcheck` subcommand.

---

## 3. Request path walkthrough

**Write path**

```
Kafka (order-events / delivery-events, key = orderId)
  └─ consumer.Run          PollFetches (BlockRebalanceOnPoll: rebalances wait)
       └─ Handler.Handle   for each record:
            ├─ event.Parse            broken → kafkaDLT.Publish → <topic>.DLT (retried until it lands)
            ├─ event.TopicOf          unknown → skip; wrong topic → DLT
            └─ store.Insert           INSERT … ON CONFLICT DO NOTHING (failure → backoff and retry)
       └─ CommitRecords    only after the whole batch is done
       └─ AllowRebalance   a rebalance may happen now
```

**Read path**

```
GET /api/tracking/orders/42/timeline
  └─ httpapi            no Bearer → 401
       └─ auth.Client   POST store /api/user/validate-token (2 s timeout) → 401 / 503
       └─ store.Owner   OrderPlaced.userId ≠ caller → 404
       └─ store.Timeline ORDER BY occurred_at, topic, offset → .UTC()
  └─ 200 [{eventType, source, occurredAt}]
```

---

## 4. Why this way

| Choice | Alternative | Trade-off |
|---|---|---|
| Design: franz-go | confluent-kafka-go (C librdkafka), segmentio/kafka-go | franz-go is pure Go, no CGO, which a static binary on distroless needs; its concepts map to the Java client |
| 🤖 Manual commit + `BlockRebalanceOnPoll` | ① franz-go's default autocommit (also at-least-once, as tested) ② `AutoCommitMarks` | Manual commit is the easiest to explain: "commit after the work". Cost: a slow batch can exceed the rebalance timeout and get the member kicked, so each record must be quick |
| Design: unique constraint for de-duplication | an in-memory set of recent eventIds | memory is lost on restart and not shared between instances; the database constraint is the only reliable one |
| 🤖 Event on the wrong topic → DLT | store it anyway; or ignore it | storing it would show the wrong source; it means a producer bug that a human should see |
| 🤖 DLT unreachable → retry, block the partition | log and commit (drop the bad message) | better stuck than silently losing a message; cost: the partition stops while Kafka is broken |
| 🤖 10 s shutdown grace | unlimited; or exit at once | Kubernetes kills after 30 s by default, 10 s leaves room; exiting at once leaves half-done work to redelivery |
| 🤖 Ownership answers 404, not 403 | 403 | 403 tells the caller the order exists; 404 does not |
| 🤖 The service creates its table | golang-migrate or another migration tool | one table does not justify a tool yet; add one when the schema changes |
| 🤖 Standard `net/http`, no Gin | Gin, Echo, chi | one endpoint; Go 1.22 routing already handles methods and path parameters |

---

## 5. Interview topics here

> 📒 = in the *Macquarie-Round1-Java八股* notes, with the question number. ➕ = not in the notes (most Go topics); add later.
>
> **Bottom line: nothing in this step needs a performance optimisation.** The real problems are correctness: duplicates, loss, order, shutdown.

| Topic | Where in the code | The judgement here |
|---|---|---|
| 📒 **F3 Idempotency** | `ON CONFLICT (event_id) DO NOTHING` ([store.go:80](../../backend/trackingService/internal/store/store.go#L80)) | Consumer-side idempotency: processing an event twice gives the same result as once. **No distributed lock needed**: the unique constraint is already safe under concurrency; if two instances insert the same row, the database lets one win |
| 📒 **F6 Kafka vs RabbitMQ** (order within a partition, one consumer per partition per group, offset reset for replay, crash before commit means a duplicate) | the whole consumer package | all four points have real code: key = orderId for order; the rebalance test; the replay test; the crash test. tracking uses Kafka, not RabbitMQ, precisely because it needs **replay** (ADR-11) |
| 📒 **F9 Event-driven** | tracking only subscribes, never publishes | adding a consumer changed no producer (ADR-01). The cost is eventual consistency: a new order may show up in the timeline a few hundred ms later |
| 📒 **F2 Resilience** | backoff ([handler.go:87](../../backend/trackingService/internal/consumer/handler.go#L87)), 2 s store timeout, 503 | timeout + backoff, **no circuit breaker**: the store call only happens on user requests, the volume is tiny, and the timeout already protects the service. A breaker would pay off if heavy traffic could take store down |
| 📒 **G4 Testcontainers vs H2** | store and integration tests | this step used exactly three things H2 gets wrong: `ON CONFLICT`, the `uuid` type, and `timestamptz` time-zone behaviour |
| 📒 **G2 Docker** | [Dockerfile](../../backend/trackingService/Dockerfile) | multi-stage keeps the toolchain out of the final image; `COPY go.mod go.sum` + `go mod download` first, so code changes do not re-download modules |
| 📒 **D5 Returning errors** | `writeError`; database errors become a 503 without the text ([httpapi.go:69](../../backend/trackingService/internal/httpapi/httpapi.go#L69)) | internal errors only go to the log. A response containing `host=10.0.0.5` draws an attacker a map |
| 📒 **D7 Health checks** | `/healthz`, `/readyz` | liveness and readiness are separate: while the database is unreachable the pod should not be restarted, only taken out of traffic |
| 📒 **H1 Secrets** | all config from environment variables; no passwords in the image (checked with `docker image inspect`) | becomes a Kubernetes Secret in step 8 |
| 📒 **E5 Indexes** | `(order_id, occurred_at)` ([store.go](../../backend/trackingService/internal/store/store.go)) | the query is always "one order, by time"; this composite index serves both the filter and the sort. **No index on event_type**: nothing queries by it |
| ➕ **Go: goroutines + channels** | app.Run runs HTTP and the consumer in one goroutine each; results come back on channels | compared with Java threads, goroutines are cheap (a few KB of stack), but you must decide **how each one ends**, or it leaks |
| ➕ **Go: context** | section 2.7 | Go's single answer to "cancel + timeout". Rule: anything that may block takes ctx as its first argument |
| ➕ **Go: zero values and pointers** | section 2.1 | a Go struct always has zero values, so "not sent" needs a pointer or a separate flag |
| ➕ **At-least-once / at-most-once / exactly-once** | section 2.3 | exactly-once across systems (Kafka → Postgres) is not available; in practice it is at-least-once + idempotency |

**Scenario anchors (say these in interviews)**

> "Kafka gives me at-least-once delivery, so duplicates are normal. In the tracking service the event ID is the primary key and the insert does ON CONFLICT DO NOTHING. A redelivered event is simply a no-op."

> "I commit offsets by hand, only after the batch is written, and I block rebalances until that commit. So a partition never moves to another pod with work that's done but not committed."

> "I don't need a circuit breaker there. The store call only happens on user requests, the volume is tiny, and a two-second timeout already stops a slow store from tying up the service."

---

## 6. Interview follow-up chains

**Chain A: duplicates and loss**
1. Can Kafka lose or duplicate messages? → On the consumer side it depends on when you commit. Process-then-commit is at-least-once: duplicates, no loss.
2. How do you handle duplicates? → eventId is the primary key, `ON CONFLICT DO NOTHING`.
3. Why not de-duplicate in memory? → Lost on restart, not shared between instances.
4. How do you prove there are no duplicates? → A test runs the real binary in a child process, kills it with `os.Exit` after the write and before the commit, then checks the message really came again and there is still one row.
5. Why a child process? → Cancelling inside the test process is a polite exit: it leaves the group and runs cleanup. A real crash does not.

**Chain B: broken messages and transient failures**
1. A message cannot be parsed? → It goes to the DLT unchanged, with the reason; move on.
2. Database down: DLT too? → No. Transient failures retry in place, blocking the partition to keep the order.
3. And if the DLT cannot be written? → Also transient: no commit; better stuck than lost.
4. What does blocking cost? → Every order on that partition gets delayed; it needs an alert.

**Chain C: graceful shutdown**
1. How does Kubernetes stop a pod? → SIGTERM, then SIGKILL after 30 s by default.
2. What do you do on SIGTERM? → Take no new records, finish and commit the one in hand, leave the group; HTTP finishes in-flight requests, then closes the port.
3. What if the record in hand is stuck retrying the database? → The backoff wait listens to ctx, so it exits at once without committing; the next instance processes it again.
4. Why leave the group explicitly? → Otherwise the others wait for the session timeout before taking over its partitions.
5. A trap you hit? → With `BlockRebalanceOnPoll`, `Close()` waits forever; use `CloseAllowingRebalance()`. The tests caught it.

**Chain D: read model**
1. Why a separate service? → Queries separate from writes (the read side of CQRS); it cannot slow down ordering or payment.
2. What if its data goes bad? → Wipe it, reset the offset to the earliest, replay.
3. Does a replay always give the same result? → Yes, as long as ordering uses only the data (time, topic, offset), not arrival order. A test proves it.
4. What does replay depend on? → Kafka still having the events. The default retention is 7 days; it needs `retention.ms=-1`.

---

## 7. How to say it in English

> "The tracking service is a small Go consumer. It reads order and delivery events from Kafka, stores them in its own Postgres database, and serves one endpoint: an order's timeline."

> "Kafka delivers at least once, so the service is idempotent. The event ID is the primary key, and offsets are committed only after the write. I tested the crash case by running the real binary in a child process and killing it between the write and the commit."

> "Failures come in two kinds. A broken message goes to a dead-letter topic and the consumer moves on. A database outage is retried in place with backoff, so nothing is skipped and the order is kept."

---

## 8. References

- Go `encoding/json`: how null is handled; `UnmarshalJSON` is called for null too
  https://pkg.go.dev/encoding/json#Unmarshal
- Go 1.22 routing enhancements: method matching, `{id}` wildcards, `Request.PathValue`
  https://go.dev/blog/routing-enhancements
- `signal.NotifyContext`: cancel a ctx when a signal arrives
  https://pkg.go.dev/os/signal#NotifyContext
- `context.WithoutCancel`, `context.AfterFunc`
  https://pkg.go.dev/context#WithoutCancel
- `http.Server.Shutdown` and `ErrServerClosed`
  https://pkg.go.dev/net/http#Server.Shutdown
- franz-go `kgo` package (`BlockRebalanceOnPoll`, `AllowRebalance`, `CommitRecords`, `CloseAllowingRebalance`)
  https://pkg.go.dev/github.com/twmb/franz-go/pkg/kgo
- Kafka design docs · Message Delivery Semantics (at-most-once / at-least-once)
  https://kafka.apache.org/43/design/design/#message-delivery-semantics
- PostgreSQL `INSERT … ON CONFLICT`
  https://www.postgresql.org/docs/current/sql-insert.html
- Spring Kafka · Publishing Dead-letter Records (where the DLT headers come from)
  https://docs.spring.io/spring-kafka/reference/kafka/annotation-error-handling.html
- Testcontainers for Go · Kafka module
  https://golang.testcontainers.org/modules/kafka/
- distroless images
  https://github.com/GoogleContainerTools/distroless

---

## 9. Self-test

**1. Delete `ON CONFLICT (event_id) DO NOTHING` (keep the primary key). Which tests fail? Is the reason the same as on the naive version?**

<details><summary>Answer</summary>

`TestInsert_DuplicateEventId_StoredOnce`, `TestInsert_SameUUIDDifferentCase_StoredOnce`, `TestCrashAfterStoreBeforeCommit_NoDuplicateRows`, and possibly `TestRebalance_NoLossNoDuplicates`.
The reason is **different**: the naive version stored two rows; now the primary key is there, so the second insert **fails** (tested: `duplicate key value violates unique constraint "tracking_events_pkey" (SQLSTATE 23505)`). The consumer treats that as transient and retries forever, so the partition is stuck. `DO NOTHING` does not only mean "no second row"; it means "a duplicate is not an error".
</details>

**2. Why can the backoff wait not be `time.Sleep(wait)`?**

<details><summary>Answer</summary>

While it sleeps, `time.Sleep` cannot hear the shutdown. The backoff grows to 10 s, so SIGTERM would wait for the sleep to end. A `select` on both `ctx.Done()` and `time.After(wait)` takes whichever comes first. `TestShutdownDuringRetry_ExitsPromptly` tests exactly this.
</details>

**3. (Only answerable if you really get it) `TestCrashBeforeStore_EventNotLost` is green on the naive version, which uses franz-go's autocommit. Why was nothing lost? Which one-line change makes it lose the message?**

<details><summary>Answer</summary>

franz-go's default autocommit only commits what the previous poll returned. The program crashes while processing the current batch, which was never committed, so it comes again after the restart. With synchronous processing the default is at-least-once.
Replace `kgo.DisableAutoCommit()` with `kgo.GreedyAutoCommit()` and it is lost: greedy commits everything fetched, including the record not yet processed. During the 7 s before the crash, autocommit commits it, and after the restart it is never read again (tested: the test times out after 60 s, the event is gone). A good break-it exercise.
</details>

---

## 10. Replay time

**60 minutes**: watch it fail (15 min) → read each fix's diff against section 2 (25 min) → break-it exercise (10 min) → say the English lines, do the self-test (10 min).

Replay commands (PowerShell, from the repository root; integration tests need Docker Desktop running):

```powershell
cd backend\trackingService
git checkout b8d8a06
go test ./...
go test -tags integration -count=1 ./...
git checkout claude/wave1-tracking-93e22a
go test -tags integration -count=1 ./...
```

Break-it exercises (pick one, then `git checkout .` to restore):
- delete `ON CONFLICT (event_id) DO NOTHING` → `go test -tags integration ./internal/store/`
- in consumer.go replace `kgo.DisableAutoCommit()` with `kgo.GreedyAutoCommit()` → `go test -tags integration -run TestCrashBeforeStore ./internal/integration/`
- delete `.UTC()` → `go test -tags integration -run UTC ./internal/store/`
