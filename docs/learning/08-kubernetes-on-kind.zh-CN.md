# 08 · 部署到 Kubernetes（kind）：一个入口、密码分开、挂了能自己回来

[English](08-kubernetes-on-kind.md) | **中文**

> 对应 commit：`e22bb3a`（集群 + 入口 + 有状态组件）→ `17371b3`（前端改相对路径）→ `5bae77d`（6 个服务 + 路由）→ `8856d8e`（nginx 404）→ `d970344`（一键部署 + README）
> 基础设施类：没有 `test(red)`，用检查清单代替（learn-by-building §1）。清单和每条的证据在 Obsidian `开发/电商项目/19-第2波C-K8s-检查清单.md`。
> 预计回放时间：**60 分钟**
> 前置：[02 · Docker 与拆库](02-docker-database-per-service.zh-CN.md)（镜像、compose、每个服务一个库）。

---

## 1. 问题

**compose 能把系统跑起来，但有四件事它做不好：**

1. **没有统一入口**：前端打包时写死了 `http://localhost:8082` 等 4 个地址。浏览器直接连各个端口，换个环境就要改代码。在 K8s 上更糟：页面从入口打开，接口却悄悄打到 compose 那套系统上（检查清单 F18）。
2. **密码堆在一个容器里**：postgres 容器的环境变量里有全部 5 个服务的库密码（09 B#6）。
3. **健康检查一刀切**：`/actuator/health` 包含 RabbitMQ。RabbitMQ 一挂就回 503（K14 实测）。如果拿它当"活着吗"的检查，所有服务都会被反复重启（09 B#5）。
4. **挂了不会自己回来、也没有扩缩的概念**：compose 没写 `restart:`（09 B#2），删掉一个容器就没了。

怎么算解决了：检查清单 26 条 + 开工后发现的场景，全部跑过。最关键的三条：
- K17：`k8s/scripts/smoke-order.ps1` 经入口下单 PASS，余额正好少一次价格。
- K19：下单后马上删掉 store pod，新 pod 用同一个幂等键扣款，钱只扣一次。
- K23：重启整个集群，92 秒后全部回来，数据都在。

---

## 2. 是什么（框架层）

### 2.1 kind：Docker 里的 Kubernetes
**大白话**：kind = Kubernetes IN Docker。整个集群的"机器"（node）就是一个 Docker 容器，里面跑着 kubelet 和 containerd。
- 配置：[kind-config.yaml](../../k8s/kind-config.yaml)。`extraPortMappings` 把本机 `127.0.0.1:80` 接到节点的 30080 端口。只绑 127.0.0.1，局域网连不进来（顺带解决 09 B#3 的同类问题）。
- 节点里的镜像和本机 Docker 的镜像是**两份**。本地构建的 `shop-store` 要 `kind load docker-image` 拷进去。标签都是 `latest`，所以 K8s 默认会去 Docker Hub 拉（`imagePullPolicy: Always`），拉不到就 `ErrImagePull`。清单里写死 `IfNotPresent`：[store.yaml](../../k8s/apps/store.yaml)（`imagePullPolicy`）。

### 2.2 Pod、Deployment、Service：无状态服务的三件套
- **Pod**：一个或几个容器，共用一个 IP。随时会被删掉、换一个新的。
- **Deployment**：说"我要 1 个 store pod，用这个镜像"。pod 没了，它的 ReplicaSet 会马上补一个。K19 删 store pod 就是在测这个。
- **Service**：一个固定的名字和虚拟 IP，转发给后面的 pod。集群里的 DNS 让 `store` 解析到这个 Service。Service 名和 compose 的服务名一样，所以 `http://store:8082`、`kafka:9092` 这些地址**一个字都不用改**：[store.yaml](../../k8s/apps/store.yaml) 的 ConfigMap。
- **更新策略**：默认 `RollingUpdate` 是先起新 pod、再停旧 pod，中间两个并存。store 改成 `Recreate`（先停旧的）：OutboxProcessor 没有跨实例的锁，两个 store 并存会抢同一个扣款任务。

### 2.3 StatefulSet + PVC：有状态组件
- **StatefulSet** 和 Deployment 的区别：pod 名字固定（`postgres-0`、`rabbitmq-0`、`kafka-0`），删了重建还叫这个名；每个 pod 有自己的数据卷。
- **volumeClaimTemplates**：给每个 pod 申请一块 PVC（持久卷声明），kind 自带的 `standard` StorageClass 在节点容器里建目录。pod 删了，卷还在：[postgres.yaml](../../k8s/base/postgres.yaml) 末尾。
- RabbitMQ 为什么特别需要固定名字：它按节点名 `rabbit@<主机名>` 存数据。名字一变，就当成新节点，旧队列全丢。K21 删了 rabbitmq pod，重建后队列里的 2 条消息还在。
- **Kafka 也挂了卷**（Christy 决定，Q3）：契约里 compose 不挂卷，因为 `stop` 能保留数据。K8s 没有 `stop`，删 pod 就等于 `down`。不挂卷的话 topic 会消失，而自动建 topic 是关的，所有生产者和消费者都会报错。K22 删了 kafka pod，4 个 topic 都还在。

### 2.4 ConfigMap、Secret 和"每个读者一个 Secret"
- **ConfigMap**：普通配置（地址、端口）。**Secret**：密码。两者都通过 `envFrom` 变成容器的环境变量，Spring 的 relaxed binding 再把 `SPRING_KAFKA_BOOTSTRAP_SERVERS` 对上 `spring.kafka.bootstrap-servers`。
- **Secret 默认只是 base64，不是加密**。`kubectl get secret -o yaml` 谁有权限谁就能看。所以：
  - Secret 从 `backend/.env` 直接写进集群，不生成文件：[apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1)。仓库里没有能被误提交的 Secret 文件。
  - **每个读者一个 Secret**：store 的 Secret 里只有 store 要的 4 个键。`envFrom: secretRef: store` 不会把 bank 的密码带进来。`check-config-keys.ps1` 逐个对比 compose，证明没有别人的库密码（K11）。
  - `DATABASE_URL` 里嵌着 tracking 的密码，所以整条放 Secret，不放 ConfigMap。

### 2.5 Job 和 initContainer：K8s 没有 `depends_on`
- **Job**：跑完就结束的 pod。`db-init` 建库建账号，`kafka-init` 建 topic。
- **db-init 必须能重跑**：compose 的建库脚本只在空数据卷上跑一次；Job 可能重试、可能被手动重跑。新脚本用 `\gexec`：账号、库不存在才建，密码每次都重新设：[db-init.sh](../../k8s/base/db-init.sh)。K6 跑了两次，第二次只有 `ALTER ROLE`。
- **initContainer**：主容器启动前先跑完的容器。每个 Java 服务先用 `psql` **真的登录一次**自己的库（登录成功说明 db-init 已经跑完），再检查 RabbitMQ 端口，然后才启动 JVM：[store.yaml](../../k8s/apps/store.yaml) 的 `wait-for-deps`。K9：6 个服务首次启动 RESTARTS 都是 0。

### 2.6 三种探针 + Spring Boot 的健康分组
| 探针 | 问的问题 | 失败了 K8s 做什么 |
|---|---|---|
| `startupProbe` | 启动完了吗？ | 还没成功前，另外两个探针不开始；超过次数就重启 |
| `livenessProbe` | 还活着吗？ | **重启容器** |
| `readinessProbe` | 能接流量吗？ | 从 Service 里摘掉，不重启 |

- Spring Boot 在 K8s 里（看到 `KUBERNETES_SERVICE_HOST` 变量）会自动开 `/actuator/health/liveness` 和 `/actuator/health/readiness` 两个健康分组。默认两个都只看应用自己的状态。
- 我们把 readiness 加上数据库：`MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db`。数据库连不上，就不该接请求；RabbitMQ / Kafka 挂了只是邮件、事件晚到，不该把 store 从入口摘掉。
- K14 实测：停掉 RabbitMQ 75 秒，完整 `/actuator/health` 是 503，liveness 和 readiness 都是 UP，三个服务 RESTARTS 一直是 0。
- Java 冷启动 1–2 分钟，所以有 `startupProbe`（最多等 4 分钟），否则 liveness 会在启动完之前就把它杀掉。

### 2.7 入口：Gateway API + Envoy Gateway
- 设计 ADR-13 写的是 Ingress。开工时发现 ingress-nginx（最常用的 Ingress 控制器）2026 年 3 月起不再发版、不修安全漏洞，kind 文档也改推 Gateway API。Christy 决定用 Gateway API（Q7）。
- 三层资源：**GatewayClass**（用哪个控制器，这里是 Envoy Gateway）→ **Gateway**（在 80 端口监听）→ **HTTPRoute**（哪个路径去哪个 Service）：[gateway.yaml](../../k8s/gateway/gateway.yaml)、[routes.yaml](../../k8s/gateway/routes.yaml)。
- **路径改写**：设计图的外部路径是 `/api/store`，store 自己的路径是 `/api/user`、`/api/orders`……`URLRewrite` + `ReplacePrefixMatch: /api` 把 `/api/store/user/login` 变成 `/api/user/login` 再发给 store。不用改 Java。
- **最长前缀优先**：`/api/store` 的规则比 `/api` 长，所以先匹配。`/api` 兜底规则用 Envoy Gateway 的 `HTTPRouteFilter`（`directResponse`）直接回 404。没有它，`POST /api/bank/transfer` 会落到 `/`，前端回一个 200 的 index.html，把错误藏起来。
- kind 没有云负载均衡，所以 Envoy 的 Service 用 NodePort 30080（`EnvoyProxy` 资源里改），再由 `extraPortMappings` 接到本机 80。

### 2.8 `enableServiceLinks: false`
- K8s 默认给每个 pod 注入同一个 namespace 里**每个** Service 的变量：`<名字>_SERVICE_HOST`、`<名字>_PORT=tcp://…`。
- Service 叫 `kafka` → pod 里有 `KAFKA_PORT=tcp://10.96.x.x:9092`。apache/kafka 镜像会把所有 `KAFKA_*` 变量都当成 broker 配置，于是 broker 拿到一个错误的 `port` 配置。所有 pod 都关掉，也让"每个 pod 只有清单里写的变量"成立（K10）。

### 2.9 容器里的 JVM 内存
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`：堆最大 = 容器内存上限（768Mi）的 75%。JVM 认得容器的 cgroup 限制。不设的话默认只用 25%，太保守。设成 100% 的话，堆外内存（线程栈、元空间、直接内存）会把容器顶过上限，被 OOM 杀掉。

---

## 3. 请求路径导读

浏览器点"登录"：

```
浏览器  POST http://localhost/api/store/user/login        （前端的 baseURL 是相对路径 /api/store）
  → Docker 端口映射 127.0.0.1:80 → kind 节点容器的 30080               kind-config.yaml
  → NodePort Service envoy-shop-shop-… → Envoy 代理 pod                gateway.yaml（EnvoyProxy）
  → HTTPRoute 规则 /api/store：改写成 /api/user/login                    routes.yaml
  → Service store:8082（集群 DNS）→ store pod                            apps/store.yaml
  → AccountController.login（/api/user/login）                          storeService/.../AccountController.java
```

下单后 store 调 bank 扣款（集群内，不经入口）：

```
OutboxProcessor（每 5 秒）→ BankAdapter → http://bank:8084/api/bank/transfer
  BANK_SERVICE_URL 来自 ConfigMap store；bank 是 Service 名，集群 DNS 解析
```

bank 收到请求要校验 token 时，回调 `http://store:8082/api/user/validate-token`，也是集群内地址。所以入口上**不需要**放 transfer / refund，只放前端用的两条 GET（Q5）。

---

## 4. 为什么这样用

| 选择 | 替代方案 | 为什么这么选 / 代价 |
|---|---|---|
| Gateway API + Envoy Gateway（Christy 定，Q7） | ingress-nginx（设计字面写法） | ingress-nginx 已退役、不修漏洞；Gateway API 的路径改写是标准写法。代价：多一层 CRD，设计里的"Ingress"要记一笔 |
| 外部路径照设计图，在入口改写（Christy 定，Q1） | 改 Java 给 store 加 `/api/store` 前缀 | A 正在改 store / delivery，现在改路径必冲突；服务间互调也要跟着改。代价：外部路径和代码路径不一样，排查时要换算 |
| 前端改相对路径（Christy 定，Q2） | 构建时注入 API 地址 + 后端配 CORS | 同源就没有跨域；compose 用 nginx 反代同一张表。代价：本地 `npm start` 要另配代理 |
| bank 只放两条 GET（Christy 定，Q5） | 整个 `/api/bank` 放出去 | transfer / refund 没鉴权，放出去任何人都能转账；设计图本来就只给前端"账户、余额" |
| Kafka 挂卷（Christy 定，Q3） | 不挂卷，Kafka 重启后重跑 kafka-init | 不挂卷：topic、消费进度都丢 |
| store 1 副本 + Recreate（Christy 定，Q4） | RollingUpdate | 两个 OutboxProcessor 并存会抢同一个任务。代价：更新时停几十秒 |
| 🤖 每个读者一个 Secret | 一个大 Secret 给所有人 | 一个大 Secret 等于退回到"postgres 容器里有所有密码" |
| 🤖 db-init 用 Job，可重跑 | 沿用 initdb 脚本 | initdb 只在空卷上跑；改密码要删数据。Job 重跑一下就行 |
| 🤖 initContainer 先真的登录一次 | 什么都不等，靠 CrashLoopBackOff 重启撑到依赖起来 | 重启退避越来越长（最长 5 分钟），而且日志里全是假错误 |
| 🤖 readiness 含数据库、不含 RabbitMQ（Christy 同意，Q6） | readiness 用完整 health | RabbitMQ 挂了 store 就从入口摘掉，用户连商品都看不了 |
| 🤖 NodePort + `extraPortMappings` | cloud-provider-kind 给 LoadBalancer IP | Windows 上 Docker 网络的 IP 本机连不到（待确认）；端口映射最直接 |
| 🤖 纯 YAML + kustomize | Helm chart | 一个环境、没有要参数化的东西；Helm 多一层模板语言要学 |

---

## 5. 八股在这里

**结论**：这一步没有要"优化"的 Java 代码，碰到的八股都在部署和运维层面。最值得讲的是 D7（健康检查）和 H1（secrets），两条都有实测证据。

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 D7 Actuator / 健康检查 | [store.yaml](../../k8s/apps/store.yaml) 三个探针 + `READINESS_INCLUDE` | liveness 只看进程、readiness 加数据库。依赖坏了要"暂停接流量"，不是"重启"。**不需要**分开的场景：没有外部依赖的服务（frontend 用同一个 `/` 就够） |
| 📒 H1 secrets | [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1) | 每个读者一个 Secret，不落盘；Secret 只是 base64，靠 RBAC 和不进 git 保护。生产要再加：etcd 加密、外部密钥库（Key Vault / External Secrets）。**不需要**外部密钥库的场景：本地 kind，只有一个人 |
| 📒 H3 技术过时 | [gateway.yaml](../../k8s/gateway/gateway.yaml) 开头注释 | 新部署就不选已退役的 ingress-nginx。旧集群里已经在跑的不会坏，但要排进迁移计划 |
| 📒 F2 resilience | K19、K21、K23 | 自愈靠控制器（Deployment / StatefulSet）+ 数据卷 + 幂等键三样一起：pod 被删，新 pod 用同一个键重试，钱只扣一次 |
| 📒 F8 Webhook | [routes.yaml](../../k8s/gateway/routes.yaml) 的 `delivery-webhook` | 没鉴权的回调接口只能在集群内用；按前缀整段转发时很容易把它一起带出去（F32） |
| 📒 C1 堆 | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` | 容器里堆按上限的百分比算，留出堆外内存；**不需要**再手写 `-Xmx` |
| ➕ Gateway API vs Ingress | routes.yaml | 角色分开（GatewayClass / Gateway / Route），改写、按方法匹配是标准字段，不靠控制器私有注解 |
| ➕ StatefulSet vs Deployment | base/*.yaml | 固定名字 + 每个 pod 一块卷；数据库、消息队列用它，无状态服务用 Deployment |
| ➕ 三种探针 | §2.6 | startup 保护慢启动；liveness 失败重启；readiness 失败摘流量 |
| ➕ Service 环境变量注入 | `enableServiceLinks: false` | 老机制，DNS 出来之前用的；会和读 `KAFKA_*` 的镜像冲突 |
| ➕ kubectl 版本差 | [tools.ps1](../../k8s/scripts/tools.ps1) | kubectl 只保证和集群差一个小版本 |

**英文场景锚点**：
- "When RabbitMQ was down, the full health endpoint returned 503, but liveness stayed up, so Kubernetes didn't restart anything. That's why liveness and readiness are separate."
- "Each pod gets its own Secret with only its own keys, and a script checks that against compose. The Secrets come straight from the env file, so there's no Secret file to commit by mistake."

---

## 6. 面试追问链

1. **liveness 和 readiness 有什么区别？** → 失败时一个重启、一个摘流量。
2. **那数据库连不上，该让哪个失败？** → readiness。重启解决不了数据库的问题，反而让所有 pod 一起重启。
3. **Spring Boot 怎么知道哪些检查属于哪个探针？** → 健康分组。在 K8s 里自动开 liveness / readiness 两组，默认只看应用状态；用 `management.endpoint.health.group.readiness.include` 加上 `db`。
4. **Java 启动慢，liveness 会不会误杀？** → 会，所以加 startupProbe：它成功之前 liveness 不开始。
5. **readiness 失败以后 pod 还会收到请求吗？** → 不会，Service 的 Endpoints 里会把它去掉。但已经建立的长连接（比如 gRPC）可能还连着，要靠客户端重新解析或者连接寿命限制（待确认，B 的 gRPC 上线后可以验证）。

---

## 7. 英文说法

- "I deployed the whole system to a local kind cluster. The browser only talks to one gateway, and it routes by path to six services."
- "Postgres, RabbitMQ and Kafka run as StatefulSets with volumes. I deleted each pod and checked the data and the queues were still there."
- "I killed the store pod right after an order. The new pod retried the payment with the same idempotency key, and the customer was charged once."

---

## 8. 资料

- Kubernetes · [Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- Spring Boot · [Kubernetes Probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes)（健康分组、在 K8s 里自动开启）
- Kubernetes · [StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- Kubernetes · [Secrets](https://kubernetes.io/docs/concepts/configuration/secret/)（base64 不是加密）
- Kubernetes · [Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/)（Recreate 策略）
- Gateway API · [HTTP path redirects and rewrites](https://gateway-api.sigs.k8s.io/guides/http-redirect-rewrite/)（`ReplacePrefixMatch`）
- Envoy Gateway · [Direct Response](https://gateway.envoyproxy.io/docs/tasks/traffic/direct-response/)
- Kubernetes Blog · [Ingress NGINX Retirement](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)
- Kubernetes · [Version Skew Policy](https://kubernetes.io/releases/version-skew-policy/)（kubectl 差一个小版本）
- Kubernetes · [Connecting Applications with Services](https://kubernetes.io/docs/tutorials/services/connect-applications-service/)（Service 环境变量、`enableServiceLinks`）
- kind · [Quick Start](https://kind.sigs.k8s.io/docs/user/quick-start/)（`kind load docker-image`）

---

## 9. 自测题

1. store 的 readiness 里为什么有数据库、没有 RabbitMQ？如果反过来会怎样？
<details><summary>答案</summary>
数据库连不上，store 什么请求都处理不了，应该从入口摘掉（readiness 失败）。RabbitMQ 挂了只影响发邮件和配送作业，下单、看商品照样能做；把它放进 readiness，RabbitMQ 一挂 store 就收不到任何请求，小故障被放大成全站故障。两个都不该进 liveness，因为重启 store 解决不了外部依赖的问题。
</details>

2. 删掉 `kafka-0`，为什么 topic 还在？如果照 compose 不挂卷，会发生什么？
<details><summary>答案</summary>
StatefulSet 的 volumeClaimTemplates 给 kafka-0 一块 PVC，删 pod 不删 PVC，新的 kafka-0 挂回同一块卷，topic 和 offset 都在磁盘上。不挂卷的话数据在容器的可写层里，删 pod 就没了；自动建 topic 又是关的，所有生产者和消费者会报 UNKNOWN_TOPIC，直到有人重跑 kafka-init，而且消费进度也丢了。
</details>

3. （要真懂）store 为什么用 `Recreate`？如果改成默认的 `RollingUpdate`，钱会不会扣两次？
<details><summary>答案</summary>
RollingUpdate 先起新 pod 再停旧的，中间两个 store 并存，两个 OutboxProcessor 会拿到同一个 PENDING 的 PAY 任务。钱不会扣两次：两边用的是同一个幂等键，bank 对键去重。但同一个键并发到达 bank 时，第二个可能回 500（09 A#6），store 会把它当成"不知道"，进入重试甚至 PAY_UNKNOWN。Recreate 避开了并存，代价是更新时停几十秒。根本解法是给 OutboxProcessor 也加单实例保证（比如 pg_try_advisory_xact_lock 或 SKIP LOCKED），那时才能多副本、滚动更新。
</details>

---

## 10. 预计回放时间

**60 分钟**：
1. `./k8s/scripts/up.ps1`，看 6 步各在做什么（10 分钟）。
2. `./k8s/scripts/smoke-order.ps1`，对照 §3 的请求路径（10 分钟）。
3. 停 RabbitMQ 看探针：`kubectl -n shop scale statefulset rabbitmq --replicas=0`，看 RESTARTS 和 `/actuator/health`，再恢复（10 分钟）。
4. 删 store pod 后马上下单 / 删 kafka-0 看 topic（15 分钟）。
5. 读 §4 的 🤖 行，想自己会怎么选；做自测题（15 分钟）。
