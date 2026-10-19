# Wave 2 config contract

[中文](wave2-config-contract.zh-CN.md)

Wave 2 runs three tasks in parallel: **A** Kafka (design steps 4–5), **B** gRPC (step 6), **C** Kubernetes (step 8).
A and B would both have edited `backend/compose.yml` and `.env.example`, and C needs the names A and B pick before it can write ConfigMaps and Secrets.
So the shared part is fixed here first and merged before the three branches start.

**Rule:** the wave 2 tasks do not change anything in this file. If a task needs a change, it stops and reports.

## Kafka

| Item | Value | Why |
|---|---|---|
| Image | `apache/kafka:3.9.1`, single node, KRaft (no ZooKeeper) | Design, figure 2 |
| Address inside compose / cluster | `kafka:9092` | |
| Address from this machine | `localhost:9094` | For services run with `bootRun` / `go run` |
| Topics | `order-events`, `delivery-events`, `order-events.DLT`, `delivery-events.DLT` | `contracts` → `Topics.java` |
| Partitions / replicas | 3 / 1 for every topic | One `orderId` always maps to one partition (ordered); different orders spread over three. A `.DLT` has the same count as its topic because Spring's dead-letter publisher writes to the **same partition number** by default |
| Auto-create topics | **off** | A wrong topic name fails at once instead of creating an empty topic. Also covers the wave 1 review finding "consumer retries forever when `order-events.DLT` does not exist" |
| Who creates topics | the one-shot `kafka-init` service, `--if-not-exists` | |
| Data volume | none: `stop` keeps the data, `down` deletes it | Events are still in each service's `event_outbox` table |

## Environment variables

| Name | Read by | Value in compose | Used by task |
|---|---|---|---|
| `SPRING_KAFKA_BOOTSTRAP_SERVERS` | store, delivery | `kafka:9092` | A (Spring binds it to `spring.kafka.bootstrap-servers`) |
| `KAFKA_BROKERS` | tracking | `kafka:9092` | exists (wave 1) |
| `KAFKA_GROUP` | tracking | `tracking` | exists |
| `BANK_GRPC_PORT` | bank | `9090` | B: port of bank's gRPC server |
| `BANK_GRPC_ADDRESS` | store | `bank:9090` | B: where store's gRPC client connects |
| `DATABASE_URL` | tracking | `postgres://tracking_user:${TRACKING_DB_PASSWORD}@postgres:5432/tracking_db?sslmode=disable` | exists. The password goes into a URL, so it must not contain `@ : / ? #` |
| `STORE_URL` | tracking | `http://store:8082` | exists (token check) |

Consumer group ids, set in each service's own config: store = `store`, delivery = `delivery`, tracking = `tracking`.

Ports: store 8082, bank 8084 (REST) + 9090 (gRPC), delivery 8081, email 8083, tracking 8085, Kafka 9094 (host), frontend 3000.

## What each task may change after this

- **A**: Java code and `application.properties` of store and delivery. In `compose.yml` only the removal of `DELIVERY_NOTIFICATION_URL` (the webhook goes away).
- **B**: bank and store (`adapter/BankAdapter.java` and below), Gradle files of bank and store. Not `compose.yml`. The `.proto` is in `contracts` and was fixed in wave 0.
- A and B both add dependencies to `storeService/build.gradle`. That is the one expected overlap: check with `git merge-tree` before opening the second PR.
- **C**: new files under `k8s/`. Secrets go to a Secret, everything else to a ConfigMap, **with the names above**.
