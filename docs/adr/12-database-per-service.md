# ADR-12 · Each service owns its own database

**English** | [中文](12-database-per-service.zh-CN.md)

**Status:** Accepted

## Context
In the course version all services shared one database, so any service could read or change another's tables, and no service could change its schema on its own.

## Decision
store, bank, delivery, email and tracking each have their own database (`store_db`, `bank_db`, …) with their own login (`store_user`, …). The five databases live in **one** PostgreSQL server. Each container receives only its own password.

## Consequences
- Boundaries are enforced by the database: a service cannot log in to another service's database.
- Data from another service comes only through its API or its events.
- One server is cheap to run locally, but it is a single point of failure; it is a logical split, not a physical one.

## In the code
- [01-create-databases.sh](../../backend/docker/postgres/init/01-create-databases.sh) (compose), [db-init.sh](../../k8s/base/db-init.sh) (kind)
- [compose.yml](../../backend/compose.yml): each service lists only its own variables
- [docs/learning/02-docker-database-per-service.md](../learning/02-docker-database-per-service.md)
