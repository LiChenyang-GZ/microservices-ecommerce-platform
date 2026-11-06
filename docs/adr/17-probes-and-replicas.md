# ADR-17 · Probes check the service itself; store runs one replica

**Status:** Accepted

## Context
Spring's full `/actuator/health` includes RabbitMQ and answers 503 while RabbitMQ is down. Used as a Kubernetes probe, a broker outage would restart every service in a loop, or take every service out of traffic, even though the outbox (ADR-03) is there precisely so that services keep working while a broker is down. Separately, store's `OutboxProcessor` has no lock across instances (ADR-06 covers only the event publisher).

## Decision
- **Liveness** checks the process only (`/actuator/health/liveness`).
- **Readiness** checks the process and the service's own database, not Kafka or RabbitMQ.
- **store runs one replica with the `Recreate` strategy**, so two pods never run payment tasks at once. An update therefore means a short outage.

## Consequences
- With Kafka down, pods stay Ready and orders are still paid (scenario 04 on kind confirmed it).
- store cannot scale out until `OutboxProcessor` takes a lock like the event publisher does.

## In the code
- [k8s/apps/store.yaml](../../k8s/apps/store.yaml) (probes, `replicas: 1`, `Recreate`) and the other files in [k8s/apps](../../k8s/apps/)
- [k8s/README.md](../../k8s/README.md), "Decisions and why"
