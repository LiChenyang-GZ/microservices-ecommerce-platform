# 02 · Docker + 每个服务一个数据库

[English](02-docker-database-per-service.md) | **中文**

> 对应 commit：`2b451b2 build`（换行）→ `a2d43a2 fix`（两处写死的地址）→ `4f12614 feat`（镜像、compose、拆库）
> 预计回放时间：**60 分钟**
> 这一步是基础设施，**没有红灯测试**。按 learn-by-building 的规则，先写"怎么算成功"的检查清单，做完逐条跑，命令输出就是证据。清单在 Obsidian `开发/电商项目/04-第1波B-Docker拆库-检查清单.md`。

---

## 1. 问题

**两个问题。** 第一，整套系统要开 6 个终端、按顺序手动启动，换一台电脑就要重新配一遍。第二，四个服务共用一个库 `webuser` 和同一个数据库账号，任何服务都能读写别人的表，"微服务"只是名义上的。

拆的过程中还发现了第三个问题：**有两个地址写死成了 `localhost`**，在电脑上直接跑没事，放进容器就坏了。

- [OutboxProcessor.java:291](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L291)：store 告诉 delivery"状态变了就回调 `http://localhost:8082/...`"。在 delivery 容器里，`localhost` 是 delivery 自己。
- [AccountService.java:26](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java#L26)：忘记密码时调 email 服务，同样写死了 `localhost:8083`。

**对照实验（相当于这一步的红灯）**：把第 291 行临时改回写死的地址，重建 store，跑 [smoke-order.ps1](../../backend/docker/smoke-order.ps1)：

```
statuses seen: PENDING_PAYMENT -> PAID
delivery_db: 2|RECEIVED
FAIL: order stopped at PAID
delivery log: Connection refused to localhost:8082 = 54
```

delivery 已经把货送到了（RECEIVED），store 却一直以为是 PAID。改回来以后：

```
statuses seen: PENDING_PAYMENT -> PAID -> PICKED_UP -> IN_TRANSIT
bank_db: balance 10000.00 -> 3001.00 (price 6999)
PASS: order paid and delivery updates reached store
```

---

## 2. 是什么（框架层）

### 2.1 镜像和容器
**大白话**：镜像（image）是打好的包，里面有应用和它要的所有东西（JRE、jar）；容器（container）是镜像跑起来的一个实例。同一个镜像，在你电脑上、CI 上、服务器上跑出来是一样的。

### 2.2 多阶段构建（multi-stage build）
- [Dockerfile:8](../../backend/Dockerfile#L8) 第一阶段用 `eclipse-temurin:17-jdk`，有完整的 JDK，用来跑 Gradle 编译出 jar。
- [Dockerfile:23](../../backend/Dockerfile#L23) 第二阶段换成 `eclipse-temurin:17-jre`，[第 26 行](../../backend/Dockerfile#L26) 只把 jar 拷过来（`COPY --from=build`）。
- 最后的镜像里**没有** JDK、Gradle 和源码（清单 #2 实测：`no-jdk`，`/src` 不存在）。镜像更小，能被攻击的东西也更少。
- 四个 Java 服务共用这一个 Dockerfile，用 `ARG SERVICE` 区分要编译哪个子项目。🤖
- 前端同理：[frontend/Dockerfile](../../frontend/Dockerfile) 先用 node 把 React 编译成静态文件，再交给 nginx 托管，运行时没有 Node.js。镜像 82.5MB。

### 2.3 构建上下文和 `.dockerignore`
- `docker build` 会先把一个目录（构建上下文）整个发给 Docker，`COPY . .` 拷的就是它。这里上下文是 `backend/`，因为 delivery 依赖 `:contracts`，四个服务是同一个 Gradle 多项目。
- [.dockerignore:2](../../backend/.dockerignore#L2) 排除 `.env`。不排除的话，`COPY . .` 会把密码拷进构建层。清单 #11 专门进构建阶段找过：宿主机有 `backend/.env`，构建阶段里没有。

### 2.4 BuildKit 缓存挂载（cache mount）
- [Dockerfile:18](../../backend/Dockerfile#L18) `RUN --mount=type=cache,target=/root/.gradle`：Gradle 下载的依赖放在一个缓存里，下次构建直接用，但**不进镜像层**。
- 踩过的坑：compose 会**同时**构建 4 个服务，4 个 Gradle 抢同一个缓存。Gradle 自己的文件锁靠 localhost 端口通信，跨容器不通，第一次构建报 `Timeout waiting to connect to the Gradle daemon`。加上 `sharing=locked`，BuildKit 让它们排队用缓存。

### 2.5 compose：服务名就是主机名
- 同一个 compose 里的容器在一个网络上（这里叫 `shop_default`），互相用**服务名**访问：store 调 bank 用 `http://bank:8084`（[compose.yml:68](../../backend/compose.yml#L68)）。
- `localhost` 在容器里永远指容器自己，这就是第 1 节那两个 bug 的根本原因。
- 端口映射 `'5433:5432'`（[compose.yml:26](../../backend/compose.yml#L26)）：宿主机的 5433 转到容器的 5432。你本机的 Postgres 已经占了 5432，所以换一个。

### 2.6 健康检查和启动顺序
- `healthcheck`：Docker 定期在容器里跑一条命令，成功就是 healthy。Java 服务跑 `curl -fsS http://localhost:8082/actuator/health`（[compose.yml:79](../../backend/compose.yml#L79)）。
- `depends_on: condition: service_healthy`（[compose.yml:74](../../backend/compose.yml#L74)）：等 Postgres 和 RabbitMQ **真的能用了**再启动服务，而不只是"容器起来了"。
- `start_period: 120s`：Spring Boot 启动要几十秒，这段时间内检查失败不算数。
- `docker compose up --wait`：等所有容器都 healthy 才返回，有一个不行就失败。
- 注意：compose **不会**因为 unhealthy 自动重启容器。这一点到第 8 步 Kubernetes 的 liveness probe 才有。

### 2.7 Spring Boot Actuator 的 health
- 加了 `spring-boot-starter-actuator`（[bankService/build.gradle:24](../../backend/bankService/build.gradle#L24)），就有了 `/actuator/health`。
- 它不只是返回 200：会自动检查数据源（`db`）、RabbitMQ（`rabbit`）、邮件服务器（`mail`）这些依赖。有一个 DOWN，整体就是 DOWN，HTTP 返回 503。
- 清单 #4 实测：停掉 Postgres，73 秒内四个服务都变 unhealthy，`/actuator/health` 返回 503；Postgres 回来后 21 秒内全部恢复，服务没有重启，是 Hikari 连接池自己重连上的。
- email 服务关掉了 `mail` 检查（[emailService application.properties:35](../../backend/emailService/src/main/resources/application.properties#L35)）。🤖 Gmail 连不上不是这个容器自己的问题，重启它也没用；如果把它算进健康检查，k8s 会因为 Gmail 一抖动就把它杀掉重启。
- 默认只通过 HTTP 暴露 `health` 这一个端点，`env`、`beans` 这些会泄露配置的端点不对外。

### 2.8 每个服务一个数据库（database per service）
- 一个 Postgres 实例里建 5 个库，每个库属于自己的账号：[01-create-databases.sh:18-22](../../backend/docker/postgres/init/01-create-databases.sh#L18)。
- **关键是第 22 行 `REVOKE CONNECT, TEMPORARY ... FROM PUBLIC`**。Postgres 默认给 `PUBLIC`（所有账号）每个库的 CONNECT 权限。不收回的话，bank_user 照样能连 store_db，"拆库"就只是换了个名字。
- 清单 #8 实测，用 bank 的账号：
  - 连 bank_db → 成功
  - 连 store_db → `FATAL: permission denied for database "store_db"`
  - 错密码 → `FATAL: password authentication failed`
- 坑：官方 Postgres 镜像对**容器内部** 127.0.0.1 的连接用 `trust`（不查密码）。所以测错密码必须从**另一个容器**走网络连，不然错密码也能进，测试就是假绿。
- 坑：建库脚本放在 `/docker-entrypoint-initdb.d/`，**只在数据卷是空的时候跑一次**。以后改密码或加库，要 `docker compose down -v` 清空数据卷。所以 tracking_db 这次就先建好了。

### 2.9 环境变量和 relaxed binding
- `application.properties` 里写 `${BANK_DB_PASSWORD}`（[bankService application.properties:8](../../backend/bankService/src/main/resources/application.properties#L8)），值从环境变量来。
- `${DB_HOST:localhost}`：冒号后面是默认值。容器里 compose 设 `DB_HOST=postgres`；本机直接跑时没设，就用 `localhost:5433`。
- **Relaxed binding**：Spring Boot 会把环境变量 `BANK_SERVICE_URL` 自动对上配置项 `bank.service.url`（大写、点换成下划线）。所以服务之间的地址**不用改配置文件**，compose 里设环境变量就能覆盖。
- 每个容器只给它需要的变量（[compose.yml:92](../../backend/compose.yml#L92) bank 只有自己的数据库密码），没有用 `env_file: .env`。🤖 用 `env_file` 的话，每个容器都能看到所有密码。清单 #10 实测：bank 容器里只有 `BANK_DB_PASSWORD`。

### 2.10 换行符（CRLF / LF）
- Windows 上 `core.autocrlf=true`，检出的 `gradlew` 是 CRLF（`\r\n`）。拷进 Linux 容器执行会报 `/bin/sh^M: not found`。
- 两层保险：根目录 [.gitattributes](../../.gitattributes) 让 `gradlew`、`*.sh`、`Dockerfile` 以后检出时都是 LF；[Dockerfile:13](../../backend/Dockerfile#L13) 再用 `sed` 去掉 `\r`，因为已经检出的旧文件不会自动重写。

---

## 3. 请求路径导读：`docker compose up` 之后发生了什么

1. compose 读 `backend/.env`，把 `compose.yml` 里的 `${...}` 换成真实值；缺了必填的值（`:?`）直接报错退出。
2. 起 **postgres**：数据卷是空的 → 跑 `01-create-databases.sh` → 5 个库、5 个账号。`pg_isready` 通过 → healthy。
3. 起 **rabbitmq**：用 `.env` 里的账号密码建管理员。`rabbitmq-diagnostics ping` 通过 → healthy。
4. 两个都 healthy 后，起 **store / bank / delivery / email**。每个容器：`java -jar /app/app.jar`（非 root 用户）→ Spring 读环境变量 → Hikari 用 `xxx_user` 连 `postgres:5432/xxx_db` → `ddl-auto=update` 建表 → DataInitializer 灌测试数据（bank 的 5 个账户、store 的商品和仓库）→ `/actuator/health` 变成 UP。
5. 起 **frontend**：nginx 在容器的 80 端口，映射到宿主机 3000。
6. 浏览器打开 `localhost:3000` → 页面里的 JS 直接调 `localhost:8082`（宿主机端口映射到 store 容器）。
7. 下单以后，store 用 `http://bank:8084` 扣款，用 `http://delivery:8081` 建配送单，并告诉 delivery 回调 `http://store:8082/api/delivery-webhook`；delivery 走 RabbitMQ 推进状态，每一步回调 store。

---

## 4. 为什么这样用

| 选择 | 替代方案 | 为什么这样选 / 代价 |
|---|---|---|
| 在镜像里用 Gradle 编译（多阶段） | 先在本机 `gradlew bootJar`，Dockerfile 只拷 jar | 任何有 Docker 的机器都能构建，不依赖本机 JDK 版本。代价：第一次构建慢（要下载 Gradle 和依赖） |
| 一个实例、多个库 | 每个服务一个 Postgres 容器 | 本地和单机部署更省资源（ADR-12）。代价：实例挂了所有服务一起挂；这是逻辑隔离，不是物理隔离 |
| 一个共用 Dockerfile 🤖 | 每个服务一份 | 四份内容会一模一样。代价：要看 compose 的 `args` 才知道构建的是哪个服务 |
| 每个容器逐个列变量 🤖 | `env_file: .env` | 最小权限，一个容器被攻破只泄露它自己的密码。代价：compose 文件更长 |
| 本地跑也连 compose 的 Postgres（5433） | 本地继续连 `webuser` | 本地和容器用同一套库结构，问题在两边都能复现。代价：本地跑之前要先开 Docker |
| 关掉 email 的 mail 健康检查 🤖 | 保留 | 外部依赖（Gmail）的故障不该让容器被判不健康。代价：Gmail 坏了 health 看不出来，要靠日志或告警 |
| 不迁旧数据 | `pg_dump` 按表迁 | DataInitializer 会重建测试数据；旧的测试用户重新注册就行。`webuser` 没删 |

---

## 5. 八股在这里

**结论：这一步没有需要"优化"的代码，碰到的八股都是部署和安全方面的。**

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 G2 Docker：镜像 vs 容器 | [Dockerfile](../../backend/Dockerfile) | 选了多阶段 + JRE 运行 + `.dockerignore`，就是笔记里"怎么让镜像更小"的三条。**不需要**再上 distroless 或 jlink：Java 服务 540MB 左右，大头是 JRE 本身，本地开发不在乎；到 Go 服务（第 7 步）再体会十几 MB 的镜像 |
| 📒 H1 secrets | [compose.yml](../../backend/compose.yml)、[.dockerignore](../../backend/.dockerignore) | 密码只在 `.env`（git 忽略）和运行时的环境变量里，镜像、构建层、仓库里都没有（清单 #11、#20）。每个容器只拿自己的。**还不够好的地方**：环境变量用 `docker inspect` 能看到，生产上应该用 k8s Secret 或 Key Vault（第 8 步） |
| 📒 D7 Actuator / 健康检查 | [compose.yml:79](../../backend/compose.yml#L79) | health 会检查数据库和 RabbitMQ，比自己写一个返回 200 的接口更真实。**不需要**开 metrics、prometheus 端点，现在没人看 |
| 📒 E6 schema 变更 | `ddl-auto=update`（没改） | 这一步继续用 Hibernate 自动建表，没上 Flyway。拆库后每个库从空开始，`update` 能建出所有表（清单 #7）。**什么时候要换**：有真实数据、要回滚、多人改表结构时 |
| 📒 F1 单体 vs 微服务 | [01-create-databases.sh:22](../../backend/docker/postgres/init/01-create-databases.sh#L22) | "每个服务拥有自己的数据"在这一步才真正落地：连接权限上就隔开了 |
| ➕ 数据库权限：`REVOKE CONNECT FROM PUBLIC` | 同上 | Postgres 默认谁都能连所有库。拆库不收回这个权限 = 没拆 |
| ➕ 初始化脚本只跑一次 | 同上 | 只在空数据卷上跑；改了要 `down -v` |
| ➕ 容器网络：`localhost` 指容器自己 | [OutboxProcessor.java:291](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java#L291) | 服务之间用服务名访问。写死 `localhost` 的地址在容器里一定会坏 |
| ➕ Spring relaxed binding | compose 里的 `BANK_SERVICE_URL` | 环境变量自动对上配置项，部署时不用改配置文件 |
| ➕ Hikari 连接池 | 清单 #9：每个库 10 个连接 | 默认池大小 10；数据库断了会自己重连（清单 #4）。**不需要**调大：这里并发很低 |

**英文场景锚点**
- "The database split only became real when I revoked CONNECT from PUBLIC. Before that, every login could still open every database."
- "Inside a container, localhost is the container itself. Two hard-coded localhost URLs worked on my laptop and broke in Docker, so I made them configuration."
- "Each container only gets its own password. I didn't use env_file, because then every container sees every secret."

---

## 6. 面试追问链

1. **你的服务怎么部署的？** → 每个服务一个 Docker 镜像，多阶段构建，docker compose 一条命令起整套系统。
2. **多阶段构建有什么好处？** → 运行镜像里只有 JRE 和 jar，没有 JDK、构建工具和源码，更小，攻击面也更小。
3. **密码怎么传进去的？会不会进镜像？** → 运行时用环境变量传，`.dockerignore` 挡住 `.env`。我用 `docker history`、`image inspect`、在镜像和构建阶段里找文件验证过。
   → 追问：**环境变量就安全吗？** → 不完全。`docker inspect` 能看到，进程的环境也可能被读到。生产上用 Kubernetes Secret 挂成文件，或者 Key Vault。
4. **每个服务一个库，你怎么保证服务连不了别人的库？** → 每个库一个账号、库归这个账号所有，再 `REVOKE CONNECT FROM PUBLIC`。我实际用 bank 的账号连 store_db，被拒绝了。
   → 追问：**为什么不每个服务一个 Postgres 实例？** → 本地和单机省资源。代价是实例是单点，而且这是逻辑隔离。真要独立扩容时再拆实例。
5. **健康检查检查了什么？** → Actuator 的 health 会检查数据库和 RabbitMQ 连接。我停掉数据库，服务在一分钟内变成 unhealthy，数据库回来后自己恢复，没有重启。
   → 追问：**为什么把邮件检查关了？** → Gmail 是外部依赖。如果算进健康检查，Gmail 抖一下，k8s 就会重启一个本来没问题的容器。

---

## 7. 英文说法

- "Each service has its own Docker image, built in two stages. The final image only has the JRE and the jar."
- "All five databases live in one Postgres instance, but each service has its own login, and it can't connect to anyone else's database. I checked that by trying it."
- "Secrets come in as environment variables at run time. They're not in the image, and each container only gets the ones it needs."

---

## 8. 资料

- Docker · [Multi-stage builds](https://docs.docker.com/build/building/multi-stage/)
- Docker · [Use cache mounts](https://docs.docker.com/build/cache/optimize/#use-cache-mounts)（讲了 `sharing=locked`）
- Docker Compose · [Control startup and shutdown order](https://docs.docker.com/compose/how-tos/startup-order/)（`depends_on` + `service_healthy`）
- Postgres 官方镜像 · [Docker Hub 说明](https://hub.docker.com/_/postgres)（初始化脚本只在空数据目录时运行；容器内本地连接是 trust）
- PostgreSQL 16 · [Privileges](https://www.postgresql.org/docs/16/ddl-priv.html)（`PUBLIC` 默认有数据库的 CONNECT 和 TEMPORARY）
- Spring Boot · [Binding from environment variables](https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables)
- Spring Boot · [Actuator health](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.health)
- microservices.io · [Database per service](https://microservices.io/patterns/data/database-per-service.html)
- Git · [gitattributes `eol`](https://git-scm.com/docs/gitattributes#_eol)

---

## 9. 自测题

1. 建库脚本里如果删掉 `REVOKE CONNECT ... FROM PUBLIC` 这一行，清单 #8 的哪个结果会变？
   <details><summary>答案</summary>bank_user 连 store_db 会<b>成功</b>，因为 Postgres 默认给所有账号每个库的 CONNECT 权限。账号还是分开的，但隔离没了。</details>
2. 你把 `.env` 里的 `BANK_DB_PASSWORD` 改了，`docker compose up -d` 之后 bank 起不来，日志说密码错误。为什么？怎么办？
   <details><summary>答案</summary>bank 容器拿到的是新密码，但数据库里 bank_user 的密码是第一次启动时建库脚本设的，脚本只在空数据卷上跑，不会重跑。要么 <code>docker compose down -v</code> 清空重建（数据会丢），要么进 Postgres 手动 <code>ALTER ROLE bank_user PASSWORD ...</code>。</details>
3. （只有真懂才答得出）在 postgres 容器里执行 `PGPASSWORD=wrong psql -h 127.0.0.1 -U bank_user -d bank_db` 能连上。这说明密码校验没生效吗？
   <details><summary>答案</summary>不说明。官方镜像对容器内部 127.0.0.1 的连接配置的是 <code>trust</code>，根本不查密码。别的容器走网络连，用的是 <code>scram-sha-256</code>，错密码会被拒。所以要从另一个容器测，在同一个容器里测是假绿。</details>

---

## 10. 回放步骤

1. `git checkout 7d89b2e`，看一眼四个 `application.properties` 都连 `webuser`，`compose.yml` 只有 RabbitMQ。
2. `git checkout claude/wave1-docker-87ab9c`，从仓库根目录跑 `docker compose -f backend/compose.yml up -d --build --wait`。
3. 跑 `pwsh backend/docker/smoke-order.ps1`，看到 `PASS`。
4. 跑清单 #8 那三条命令，亲眼看 `permission denied`。
5. 停一下 Postgres（清单 #4），看服务变 unhealthy、再恢复。
6. 对 🤖 的选择想一遍：换成你会怎么选？
7. 用英文讲第 7 节，做第 9 节的题。
