# 02 · Docker + one database per service

**English** | [中文](02-docker-database-per-service.zh-CN.md)

> Commits: `2b451b2 build` (line endings) → `a2d43a2 fix` (two hard-coded URLs) → `4f12614 feat` (images, compose, database split)
> Replay time: **60 minutes**
> This step is infrastructure, so there is **no red test**. Following learn-by-building, a "what counts as done" checklist was written first, then every item was run and its output kept as evidence. The checklist is in Obsidian `开发/电商项目/04-第1波B-Docker拆库-检查清单.md`.

---

## 1. The problem

**Two problems.** First, running the system meant six terminals started by hand in the right order, and every new machine needed the same setup again. Second, all four services shared one database, `webuser`, and one database login. Any service could read and write any other service's tables, so the "microservices" were separate only in name.

While splitting, a third problem showed up: **two URLs were hard-coded to `localhost`**. That works on a laptop and breaks inside a container.

- [OutboxProcessor.java:291](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L291): store tells delivery "call me back at `http://localhost:8082/...`". Inside the delivery container, `localhost` is delivery itself.
- [AccountService.java:26](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java#L26): password reset calls the email service, also hard-coded to `localhost:8083`.

**Control experiment (this step's "red")**: put the hard-coded URL back on line 291, rebuild store, run [smoke-order.ps1](../../backend/docker/smoke-order.ps1):

```
statuses seen: PENDING_PAYMENT -> PAID
delivery_db: 2|RECEIVED
FAIL: order stopped at PAID
delivery log: Connection refused to localhost:8082 = 54
```

Delivery had already delivered the parcel (RECEIVED), but store still thought it was PAID. With the fix back:

```
statuses seen: PENDING_PAYMENT -> PAID -> PICKED_UP -> IN_TRANSIT
bank_db: balance 10000.00 -> 3001.00 (price 6999)
PASS: order paid and delivery updates reached store
```

---

## 2. What it is (framework level)

### 2.1 Image and container
An image is the package: the app and everything it needs (JRE, jar). A container is a running copy of an image. The same image behaves the same on a laptop, in CI and on a server.

### 2.2 Multi-stage build
- [Dockerfile:8](../../backend/Dockerfile#L8): stage one uses `eclipse-temurin:17-jdk`, a full JDK, to run Gradle and build the jar.
- [Dockerfile:23](../../backend/Dockerfile#L23): stage two switches to `eclipse-temurin:17-jre`, and [line 26](../../backend/Dockerfile#L26) copies only the jar (`COPY --from=build`).
- The final image has **no** JDK, Gradle or source code (checklist #2: `no-jdk`, no `/src`). Smaller image, less to attack.
- All four Java services share this one Dockerfile; `ARG SERVICE` picks the subproject. 🤖
- Same idea for the frontend: [frontend/Dockerfile](../../frontend/Dockerfile) builds the React app with node, then nginx serves the static files. No Node.js at run time. Image size 82.5MB.

### 2.3 Build context and `.dockerignore`
- `docker build` first sends a directory (the build context) to Docker; `COPY . .` copies from it. The context here is `backend/`, because delivery depends on `:contracts` and the four services are one Gradle multi-project build.
- [.dockerignore:2](../../backend/.dockerignore#L2) excludes `.env`. Without it, `COPY . .` would copy the passwords into a build layer. Checklist #11 looked inside the build stage: the host has `backend/.env`, the build stage does not.

### 2.4 BuildKit cache mount
- [Dockerfile:18](../../backend/Dockerfile#L18) `RUN --mount=type=cache,target=/root/.gradle`: Gradle's downloaded dependencies stay in a cache for the next build, but **never go into an image layer**.
- A trap we hit: compose builds the four services **at the same time**, so four Gradle runs share one cache. Gradle's own file locking talks over localhost ports, which do not cross containers, and the first build failed with `Timeout waiting to connect to the Gradle daemon`. `sharing=locked` makes BuildKit hand the cache to one build at a time.

### 2.5 Compose: the service name is the host name
- Containers in one compose project share a network (`shop_default` here) and reach each other by **service name**: store calls bank at `http://bank:8084` ([compose.yml:68](../../backend/compose.yml#L68)).
- `localhost` inside a container always means that container. That is the root cause of both bugs in section 1.
- Port mapping `'5433:5432'` ([compose.yml:26](../../backend/compose.yml#L26)): host port 5433 forwards to the container's 5432. A local PostgreSQL already holds 5432.

### 2.6 Health checks and start order
- `healthcheck`: Docker runs a command inside the container on a schedule; success means healthy. The Java services run `curl -fsS http://localhost:8082/actuator/health` ([compose.yml:79](../../backend/compose.yml#L79)).
- `depends_on: condition: service_healthy` ([compose.yml:74](../../backend/compose.yml#L74)): start the service only after Postgres and RabbitMQ are **actually usable**, not just "the container started".
- `start_period: 120s`: Spring Boot needs tens of seconds to start; failures in that window don't count.
- `docker compose up --wait`: returns only when every container is healthy, and fails if one isn't.
- Note: compose does **not** restart a container because it is unhealthy. That comes with Kubernetes liveness probes in step 8.

### 2.7 Spring Boot Actuator health
- With `spring-boot-starter-actuator` ([bankService/build.gradle:24](../../backend/bankService/build.gradle#L24)) you get `/actuator/health`.
- It does more than return 200: it checks the data source (`db`), RabbitMQ (`rabbit`) and the mail server (`mail`). If one is DOWN, the whole status is DOWN and the HTTP status is 503.
- Checklist #4: with Postgres stopped, all four services went unhealthy within 73 seconds and `/actuator/health` returned 503. With Postgres back, all recovered within 21 seconds without a restart; the Hikari pool reconnected on its own.
- The email service turns the `mail` check off ([emailService application.properties:35](../../backend/emailService/src/main/resources/application.properties#L35)). 🤖 Gmail being unreachable is not this container's fault, and restarting it would not help. Counted in health, every Gmail hiccup would get the container killed by Kubernetes.
- By default only `health` is exposed over HTTP; endpoints that leak configuration, like `env` and `beans`, are not.

### 2.8 Database per service
- One Postgres instance, five databases, each owned by its own login: [01-create-databases.sh:18-22](../../backend/docker/postgres/init/01-create-databases.sh#L18).
- **The key line is 22, `REVOKE CONNECT, TEMPORARY ... FROM PUBLIC`.** Postgres grants `PUBLIC` (every login) CONNECT on every database by default. Without revoking it, bank_user can still open store_db, and the split is only a rename.
- Checklist #8, using bank's login:
  - bank_db → connects
  - store_db → `FATAL: permission denied for database "store_db"`
  - wrong password → `FATAL: password authentication failed`
- Trap: the official Postgres image trusts connections from 127.0.0.1 **inside the container** (no password check). The wrong-password test must come from **another container** over the network, or a wrong password gets in and the check is a false green.
- Trap: the init script in `/docker-entrypoint-initdb.d/` runs **only when the data volume is empty**. Changing a password or adding a database later needs `docker compose down -v`. That is why tracking_db was created now.

### 2.9 Environment variables and relaxed binding
- `application.properties` says `${BANK_DB_PASSWORD}` ([bankService application.properties:8](../../backend/bankService/src/main/resources/application.properties#L8)); the value comes from the environment.
- `${DB_HOST:localhost}`: after the colon is the default. In a container compose sets `DB_HOST=postgres`; run directly on the machine, the default `localhost:5433` applies.
- **Relaxed binding**: Spring Boot maps the environment variable `BANK_SERVICE_URL` to the property `bank.service.url` (upper case, dots to underscores). So service URLs needed **no config file change**; compose overrides them with environment variables.
- Each container gets only the variables it needs ([compose.yml:92](../../backend/compose.yml#L92): bank has only its own database password). No `env_file: .env`. 🤖 With `env_file`, every container would see every secret. Checklist #10: the bank container has only `BANK_DB_PASSWORD`.

### 2.10 Line endings (CRLF / LF)
- On Windows with `core.autocrlf=true`, `gradlew` is checked out with CRLF (`\r\n`). Run inside a Linux container it fails with `/bin/sh^M: not found`.
- Two safeguards: the root [.gitattributes](../../.gitattributes) keeps `gradlew`, `*.sh` and `Dockerfile` LF on future checkouts; [Dockerfile:13](../../backend/Dockerfile#L13) also strips `\r` with `sed`, because files already checked out are not rewritten.

---

## 3. Walkthrough: what happens after `docker compose up`

1. Compose reads `backend/.env` and fills in the `${...}` values in `compose.yml`. A missing required value (`:?`) stops it with an error.
2. **postgres** starts: the volume is empty → `01-create-databases.sh` runs → 5 databases, 5 logins. `pg_isready` passes → healthy.
3. **rabbitmq** starts with the admin login from `.env`. `rabbitmq-diagnostics ping` passes → healthy.
4. Once both are healthy, **store / bank / delivery / email** start. Each container runs `java -jar /app/app.jar` as a non-root user → Spring reads the environment → Hikari connects as `xxx_user` to `postgres:5432/xxx_db` → `ddl-auto=update` creates the tables → DataInitializer seeds test data (bank's 5 accounts, store's products and warehouses) → `/actuator/health` is UP.
5. **frontend** starts: nginx on container port 80, mapped to host port 3000.
6. The browser opens `localhost:3000` → the page's JavaScript calls `localhost:8082` directly (host port mapped to the store container).
7. After an order, store charges via `http://bank:8084`, creates the delivery via `http://delivery:8081`, and tells delivery to call back `http://store:8082/api/delivery-webhook`. Delivery moves the status forward through RabbitMQ and calls store back at each step.

---

## 4. Why this way

| Choice | Alternative | Why / cost |
|---|---|---|
| Compile with Gradle inside the image (multi-stage) | Run `gradlew bootJar` on the host; the Dockerfile only copies the jar | Any machine with Docker can build it, regardless of the local JDK. Cost: the first build is slow (downloads Gradle and dependencies) |
| One instance, several databases | One Postgres container per service | Cheaper locally and on one machine (ADR-12). Cost: if the instance dies, every service goes with it; this is logical isolation, not physical |
| One shared Dockerfile 🤖 | One per service | The four copies would be identical. Cost: you read compose's `args` to see which service is built |
| List each container's variables 🤖 | `env_file: .env` | Least privilege: a compromised container leaks only its own password. Cost: a longer compose file |
| Local runs also use the compose Postgres (5433) | Keep using `webuser` locally | Same schema layout locally and in containers, so problems reproduce on both. Cost: Docker must be running before a local run |
| Turn off email's mail health check 🤖 | Keep it | An external dependency (Gmail) failing should not mark the container unhealthy. Cost: a Gmail outage doesn't show in health; logs or alerts must catch it |
| No data migration | `pg_dump` table by table | DataInitializer rebuilds the test data; old test users just register again. `webuser` was not dropped |

---

## 5. Where the interview basics show up

**Bottom line: nothing in this step needs "optimising". The topics it touches are deployment and security.**

| Topic | Where | Judgement |
|---|---|---|
| 📒 G2 Docker: image vs container | [Dockerfile](../../backend/Dockerfile) | Multi-stage + JRE at run time + `.dockerignore` are exactly the notes' three answers to "how to make images smaller". **Not needed**: distroless or jlink. The Java images are about 540MB, mostly the JRE, and that doesn't matter locally; the Go service (step 7) is where a tiny image is worth showing |
| 📒 H1 secrets | [compose.yml](../../backend/compose.yml), [.dockerignore](../../backend/.dockerignore) | Passwords live only in `.env` (git-ignored) and in run-time environment variables; none in images, build layers or the repo (checklist #11, #20). Each container gets only its own. **Not good enough yet**: `docker inspect` shows environment variables; production should use Kubernetes Secrets or Key Vault (step 8) |
| 📒 D7 Actuator / health | [compose.yml:79](../../backend/compose.yml#L79) | Health checks the database and RabbitMQ, which is more honest than a hand-written endpoint that returns 200. **Not needed**: metrics or Prometheus endpoints; nobody reads them yet |
| 📒 E6 schema changes | `ddl-auto=update` (unchanged) | Still Hibernate auto-DDL, no Flyway. After the split every database starts empty and `update` creates all tables (checklist #7). **When to switch**: real data, rollbacks, several people changing the schema |
| 📒 F1 monolith vs microservices | [01-create-databases.sh:22](../../backend/docker/postgres/init/01-create-databases.sh#L22) | "Each service owns its data" becomes real here: the connection rights enforce it |
| ➕ Database privileges: `REVOKE CONNECT FROM PUBLIC` | same | By default any login can connect to any database. Split without revoking = not split |
| ➕ Init scripts run once | same | Only on an empty volume; changes need `down -v` |
| ➕ Container networking: `localhost` is the container | [OutboxProcessor.java:291](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L291) | Services talk by service name. A hard-coded `localhost` URL will break in a container |
| ➕ Spring relaxed binding | `BANK_SERVICE_URL` in compose | Environment variables map onto properties, so deploys need no config file edits |
| ➕ Hikari connection pool | checklist #9: 10 connections per database | Default pool size is 10, and it reconnects after the database comes back (checklist #4). **Not needed**: a bigger pool; concurrency here is low |

**Scenario anchors**
- "The database split only became real when I revoked CONNECT from PUBLIC. Before that, every login could still open every database."
- "Inside a container, localhost is the container itself. Two hard-coded localhost URLs worked on my laptop and broke in Docker, so I made them configuration."
- "Each container only gets its own password. I didn't use env_file, because then every container sees every secret."

---

## 6. Interview follow-up chain

1. **How do you deploy your services?** → One Docker image per service, multi-stage builds, and docker compose starts the whole system with one command.
2. **Why multi-stage?** → The run-time image has only the JRE and the jar: no JDK, build tools or source. Smaller, and less to attack.
3. **How do passwords get in? Can they end up in the image?** → As environment variables at run time; `.dockerignore` keeps `.env` out. I checked with `docker history`, `image inspect`, and by searching the image and the build stage for files.
   → Follow-up: **Are environment variables safe?** → Not fully. `docker inspect` shows them, and a process's environment can be read. In production I'd mount Kubernetes Secrets as files, or use Key Vault.
4. **One database per service: how do you stop a service from opening another's?** → One login per database, the database owned by that login, and `REVOKE CONNECT FROM PUBLIC`. I actually tried bank's login on store_db and it was refused.
   → Follow-up: **Why not one Postgres instance per service?** → Cheaper locally and on one machine. The cost is a single point of failure and only logical isolation. I'd split instances when a service really needs to scale on its own.
5. **What does the health check check?** → Actuator health checks the database and RabbitMQ connections. I stopped the database, the services went unhealthy within a minute, and recovered on their own without a restart when it came back.
   → Follow-up: **Why turn off the mail check?** → Gmail is external. If it counted, a Gmail hiccup would make Kubernetes restart a container that was fine.

---

## 7. How to say it

- "Each service has its own Docker image, built in two stages. The final image only has the JRE and the jar."
- "All five databases live in one Postgres instance, but each service has its own login, and it can't connect to anyone else's database. I checked that by trying it."
- "Secrets come in as environment variables at run time. They're not in the image, and each container only gets the ones it needs."

---

## 8. References

- Docker · [Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- Docker · [Use cache mounts](https://docs.docker.com/build/cache/optimize/#use-cache-mounts) (covers `sharing=locked`)
- Docker Compose · [Control startup and shutdown order](https://docs.docker.com/compose/how-tos/startup-order/) (`depends_on` + `service_healthy`)
- Official Postgres image · [Docker Hub page](https://hub.docker.com/_/postgres) (init scripts run only on an empty data directory; local connections inside the container are trusted)
- PostgreSQL 16 · [Privileges](https://www.postgresql.org/docs/16/ddl-priv.html) (`PUBLIC` gets CONNECT and TEMPORARY on databases by default)
- Spring Boot · [Binding from environment variables](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables)
- Spring Boot · [Actuator health](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health)
- microservices.io · [Database per service](https://microservices.io/patterns/data/database-per-service.html)
- Git · [gitattributes `eol`](https://git-scm.com/docs/gitattributes#_eol)

---

## 9. Self-check

1. If the `REVOKE CONNECT ... FROM PUBLIC` line is removed from the init script, which result in checklist #8 changes?
   <details><summary>Answer</summary>bank_user connecting to store_db would <b>succeed</b>, because Postgres gives every login CONNECT on every database by default. The logins are still separate, but the isolation is gone.</details>
2. You change `BANK_DB_PASSWORD` in `.env`, run `docker compose up -d`, and bank fails to start with a password error. Why, and what do you do?
   <details><summary>Answer</summary>The bank container gets the new password, but bank_user's password in the database was set by the init script on first start, and that script only runs on an empty volume. Either <code>docker compose down -v</code> and rebuild (data is lost), or run <code>ALTER ROLE bank_user PASSWORD ...</code> in Postgres by hand.</details>
3. (Only answerable if you really get it) Inside the postgres container, `PGPASSWORD=wrong psql -h 127.0.0.1 -U bank_user -d bank_db` connects. Does that mean password checks are broken?
   <details><summary>Answer</summary>No. The official image configures <code>trust</code> for 127.0.0.1 inside the container, so no password is checked. Other containers come in over the network with <code>scram-sha-256</code>, and a wrong password is refused. Testing from inside the same container is a false green.</details>

---

## 10. Replay

1. `git checkout 7d89b2e`: all four `application.properties` point at `webuser`, and `compose.yml` has only RabbitMQ.
2. `git checkout claude/wave1-docker-87ab9c`, then from the repository root: `docker compose -f backend/compose.yml up -d --build --wait`.
3. Run `pwsh backend/docker/smoke-order.ps1` and see `PASS`.
4. Run the three commands of checklist #8 and see `permission denied` yourself.
5. Stop Postgres for a moment (checklist #4): services go unhealthy, then recover.
6. For each 🤖 choice: what would you have picked, and why?
7. Say section 7 out loud in English, then do section 9.
