# ADR-13 · Run on Kubernetes behind a single entry

**Status:** Accepted (the entry technology is decided in ADR-15)

## Context
The project should run in a shape close to a real deployment, on one laptop, without the browser knowing every service's port.

## Decision
- A one-node kind cluster runs everything (namespace `shop`).
- The browser knows one address, `http://localhost`; a single entry routes paths to services (ADR-15).
- Settings are ConfigMaps; passwords are Secrets, one per reader, created straight from `backend/.env` by a script so no Secret file exists on disk.
- PostgreSQL, Kafka and RabbitMQ run as single-replica StatefulSets with volumes; a re-runnable `db-init` Job creates the databases and logins.

## Consequences
- The same images run in compose and kind.
- Every stateful component is a single point of failure; this setup gives no high availability.
- `kind delete cluster` deletes all data in the cluster.

## In the code
- [k8s/](../../k8s/README.md): `base/`, `apps/`, `gateway/`, `scripts/up.ps1`, `scripts/apply-secrets.ps1`
- [docs/learning/08-kubernetes-on-kind.md](../learning/08-kubernetes-on-kind.md)
