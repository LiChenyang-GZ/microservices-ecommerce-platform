# Architecture decision records

**English** | [中文](README.zh-CN.md)

Each record says what was decided, why, what it costs, and where the code is. They describe the system as it is on `main`; where building it changed the first plan, the record says so.

ADR-01 to ADR-14 were written before the redesign was built. Code comments refer to them by number ("ADR-06", "rule 4"). ADR-15 onwards were decided while building it.

| # | Decision | Status |
|---|---|---|
| [01](01-events-between-services.md) | State changes travel between services as Kafka events | Accepted |
| [02](02-rabbitmq-for-jobs.md) | Jobs go through RabbitMQ, events through Kafka | Accepted |
| [03](03-transactional-outbox.md) | Events and money tasks are written through an outbox | Accepted |
| [04](04-single-money-path-idempotent.md) | One path moves money, and every call is idempotent | Accepted, amended by 16 |
| [05](05-timeout-is-not-failure.md) | A timeout is not a failure | Accepted |
| [06](06-advisory-lock-publisher.md) | One outbox publisher at a time, by a transaction-scoped advisory lock | Accepted |
| [07](07-order-by-order-dedupe-in-consumers.md) | Events are ordered per order, and consumers de-duplicate | Accepted |
| [08](08-two-kinds-of-consumer-failure.md) | Consumer failures: wait out an outage, move a bad message aside | Accepted, refined |
| [09](09-cancel-sync-call-and-marker.md) | Cancelling an order: a synchronous call plus a cancel marker | Accepted |
| [10](10-grpc-inside-rest-outside.md) | gRPC between services for money, REST for the browser | Accepted |
| [11](11-go-tracking-read-service.md) | The order timeline is a separate read service in Go | Accepted |
| [12](12-database-per-service.md) | Each service owns its own database | Accepted |
| [13](13-kubernetes-single-entry.md) | Run on Kubernetes behind a single entry | Accepted, entry decided by 15 |
| [14](14-out-of-scope.md) | What this design leaves as it was | Accepted |
| [15](15-gateway-api-allowlist-entry.md) | The single entry is Gateway API (Envoy Gateway) with an allowlist | Accepted, amended by 23 |
| [16](16-unguessable-idempotency-keys.md) | Idempotency keys carry a random part, and bank compares content | Accepted, amended by 28, 29 |
| [17](17-probes-and-replicas.md) | Probes check the service itself; store runs one replica | Accepted |
| [18](18-stuck-delivery-sweeper.md) | A sweeper re-sends delivery jobs that stopped moving | Accepted |
| [19](19-shared-event-contract.md) | One event contract, shared by Java and Go | Accepted |
| [20](20-stock-pessimistic-lock.md) | Stock is reserved with pessimistic row locks | Accepted |
| [21](21-schema-changes-at-startup.md) | Schema changes run as idempotent SQL at startup, not Flyway | Accepted |
| [22](22-grpc-result-mapping.md) | How a gRPC reply from bank maps to succeeded, rejected or unknown | Accepted, amended by 23 |
| [23](23-service-credentials.md) | Calls between services carry a shared secret, one per called service | Accepted, amended by 25, 26 |
| [24](24-login-ownership-admin-role.md) | Store requires a login, users touch only their own orders, admin endpoints need an admin role | Accepted, amended by 25 |
| [25](25-activation-needs-the-emailed-code.md) | Activation needs the emailed code, and store checks it with email | Accepted, amended by 26, 27, 30 |
| [26](26-rate-limits-in-redis.md) | Rate limits on store's public account endpoints, counted in Redis | Accepted, amended by 27, 30 |
| [27](27-password-reset-claims-unverified-account.md) | An unverified account does not hold its address: a password reset claims it | Accepted, amended by 30 |
| [28](28-a-retry-sends-the-first-request.md) | A retried charge sends exactly the accounts of its first send | Accepted, amended by 29 |
| [29](29-key-conflict-is-not-a-failure.md) | A key conflict is not a failure: bank says KEY_CONFLICT, store holds it as unknown | Accepted |
| [30](30-codes-survive-wrong-guesses.md) | Codes are long enough to survive wrong guesses; store limits guesses per IP + email | Accepted |

## Reliability rules

Code comments also cite these rules by number. Each one is part of the ADRs above.

| Rule | In short | ADR |
|---|---|---|
| 1 | Only `OutboxProcessor` calls bank; every call has an idempotency key | 04, 16 |
| 2 | Tasks in `payment_outbox`, events in `event_outbox`, each written in the business transaction | 03 |
| 3 | One publisher at a time; send in id order, stop at the first failure | 06 |
| 4 | Commit the database before the offset; consumers de-duplicate | 07 |
| 5 | Status never goes backwards | 07 |
| 6 | Broken message → DLT; transient trouble → wait in place | 08 |
| 7 | A timeout is not a failure; retry with the same key | 05, 22 |
| 8 | Pay Now locks the payment row and only retries a FAILED payment | 04 |

New records get the next number. A record is not rewritten when a decision changes: a new record says what changed, and the old one's status points to it.
