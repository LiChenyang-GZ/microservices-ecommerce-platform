# ADR-21 · Schema changes run as idempotent SQL at startup, not Flyway

**English** | [中文](21-schema-changes-at-startup.zh-CN.md)

**Status:** Accepted

## Context
The services create their tables with Hibernate `ddl-auto=update`. That adds new tables and columns, but on a table that already exists it never drops `NOT NULL`, and a failed "add unique" only logs a line. Wave 2 needed exactly those changes on databases that already held data: a unique `order_id` on deliveries (ADR-07), nullable customer columns for the cancel marker (ADR-09), and new order states (LOST, REFUNDED) in store's status check constraint. A fresh test database hides the problem; the long-lived compose volume does not (finding B11).

Options: (a) idempotent SQL that each service runs at startup; (b) introduce Flyway, which means baselining the existing databases and one more tool; (c) recreate the volume, which deletes data and never tests an upgrade.

## Decision
(a). After Hibernate has created or updated the tables and before the Kafka listeners start, the service runs a few statements that are safe to run again: `CREATE UNIQUE INDEX IF NOT EXISTS`, `DROP NOT NULL`, replacing a check constraint. If a required index cannot be built (for example, two delivery rows already exist for one order), startup fails on purpose: running without it would silently turn de-duplication off. The tests first build the old table shape, then check the upgrade.

## Consequences
- Existing databases upgrade in place; nobody has to run `down -v`.
- There is no migration history table. This works for a handful of changes; with many more, Flyway (or Liquibase) would be the better tool.
- `delivery`'s old `V1__…sql` file under `db/migration` is not run by anything (there is no Flyway).

## In the code
- delivery [DeliverySchemaUpgrade.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java), store [OrderStatusCheckUpgrade.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/OrderStatusCheckUpgrade.java)
