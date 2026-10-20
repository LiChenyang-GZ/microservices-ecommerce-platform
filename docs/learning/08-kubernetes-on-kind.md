# 08 · Kubernetes on kind: one entry, separate passwords, comes back by itself

**English** | [中文](08-kubernetes-on-kind.zh-CN.md)

> Commits: `e22bb3a` (cluster + entry + stateful parts) → `17371b3` (frontend relative paths) → `5bae77d` (six services + routes) → `8856d8e` (nginx 404) → `d970344` (one-command deploy + README)
> Infrastructure work: no `test(red)`; a checklist instead (learn-by-building §1). The checklist and the evidence for each item are in Obsidian `开发/电商项目/19-第2波C-K8s-检查清单.md`.
> Replay time: **60 minutes**
> Before this: [02 · Docker and database per service](02-docker-database-per-service.md) (images, compose, one database per service).

---

## 1. Problem

**compose runs the system, but four things it does badly:**

1. **No single entry**: the frontend bundle had `http://localhost:8082` and three more addresses written in. The browser called each port directly; another environment meant changing code. On Kubernetes it was worse: the page came from the entry, but the API calls silently went to the compose stack (checklist F18).
2. **All passwords in one container**: the postgres container's environment had all five service passwords (09 B#6).
3. **One health check for everything**: `/actuator/health` includes RabbitMQ. With RabbitMQ down it answers 503 (measured in K14). Used as an "is it alive" check, it would restart every service in a loop (09 B#5).
4. **Nothing comes back by itself**: compose has no `restart:` (09 B#2); a deleted container stays gone.

Solved means: all 26 checklist items plus the scenarios found on the way were run. The three that matter most:
- K17: `k8s/scripts/smoke-order.ps1` places an order through the entry, PASS, and the balance drops by exactly one price.
- K19: the store pod is deleted right after an order; the new pod charges with the same idempotency key, once.
- K23: the whole cluster restarted; everything was back in 92 seconds with its data.

---

## 2. What it is (framework level)

### 2.1 kind: Kubernetes in Docker
**Plain words**: the cluster's "machine" (node) is one Docker container running kubelet and containerd.
- Config: [kind-config.yaml](../../k8s/kind-config.yaml). `extraPortMappings` connects this machine's `127.0.0.1:80` to the node's port 30080. Bound to 127.0.0.1 only, so the LAN cannot reach it.
- Images inside the node are a **separate copy** from Docker's. Locally built `shop-store` must be copied in with `kind load docker-image`. The tag is `latest`, so Kubernetes would try to pull it from Docker Hub (`imagePullPolicy: Always`) and fail with `ErrImagePull`. The manifests say `IfNotPresent`: [store.yaml](../../k8s/apps/store.yaml).

### 2.2 Pod, Deployment, Service
- **Pod**: one or more containers sharing an IP. It can be deleted and replaced at any time.
- **Deployment**: "I want 1 store pod with this image". When the pod is gone, its ReplicaSet makes a new one at once. K19 tests exactly this.
- **Service**: a fixed name and virtual IP in front of the pods. Cluster DNS resolves `store` to it. Service names equal the compose service names, so `http://store:8082` and `kafka:9092` **do not change at all**: see the ConfigMap in [store.yaml](../../k8s/apps/store.yaml).
- **Update strategy**: the default `RollingUpdate` starts the new pod before stopping the old one, so both run for a while. store uses `Recreate` (old one first): OutboxProcessor has no lock across instances, so two store pods would take the same payment task.

### 2.3 StatefulSet + PVC
- **StatefulSet** vs Deployment: fixed pod names (`postgres-0`, `rabbitmq-0`, `kafka-0`) that survive re-creation, and one volume per pod.
- **volumeClaimTemplates** request a PVC (persistent volume claim) per pod; kind's `standard` StorageClass makes a directory inside the node. Deleting the pod keeps the volume: end of [postgres.yaml](../../k8s/base/postgres.yaml).
- Why RabbitMQ needs the fixed name: it stores data under `rabbit@<hostname>`. A new name looks like a new node, and the old queues are gone. K21 deleted the rabbitmq pod; the 2 queued messages were still there afterwards.
- **Kafka has a volume too** (Christy's decision, Q3): the contract gives compose no volume, because `stop` keeps the data. Kubernetes has no `stop`; deleting the pod is like `down`. Without a volume the topics vanish, and since auto-create is off every producer and consumer would fail. K22 deleted kafka-0; all 4 topics remained.

### 2.4 ConfigMap, Secret, and one Secret per reader
- **ConfigMap**: plain settings (addresses, ports). **Secret**: passwords. Both become environment variables through `envFrom`; Spring's relaxed binding maps `SPRING_KAFKA_BOOTSTRAP_SERVERS` to `spring.kafka.bootstrap-servers`.
- **A Secret is only base64 by default, not encryption.** Anyone allowed to run `kubectl get secret -o yaml` can read it. So:
  - Secrets go from `backend/.env` straight into the cluster, no file is written: [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1). There is no Secret file to commit by mistake.
  - **One Secret per reader**: store's Secret holds only store's 4 keys. `envFrom: secretRef: store` cannot bring bank's password in. `check-config-keys.ps1` compares every service with compose and shows no foreign database password (K11).
  - `DATABASE_URL` contains tracking's password, so the whole URL is a Secret, not ConfigMap data.

### 2.5 Jobs and init containers: Kubernetes has no `depends_on`
- **Job**: a pod that runs to completion. `db-init` creates databases and logins; `kafka-init` creates the topics.
- **db-init must be safe to re-run**: compose's script runs once on an empty volume; a Job can be retried or re-run by hand. The new script uses `\gexec`: create the login and database only if missing, set the password every time: [db-init.sh](../../k8s/base/db-init.sh). K6 ran it twice; the second run only did `ALTER ROLE`.
- **Init container**: runs to completion before the main container starts. Each Java service first **really logs in** to its database with `psql` (success means db-init is done), checks the RabbitMQ port, and only then starts the JVM: `wait-for-deps` in [store.yaml](../../k8s/apps/store.yaml). K9: all six services started with 0 restarts.

### 2.6 Three probes and Spring Boot's health groups
| Probe | Question | On failure Kubernetes... |
|---|---|---|
| `startupProbe` | finished starting? | holds the other two probes back; after too many failures, restarts |
| `livenessProbe` | still alive? | **restarts the container** |
| `readinessProbe` | can it take traffic? | removes it from the Service; no restart |

- On Kubernetes (it sees `KUBERNETES_SERVICE_HOST`) Spring Boot turns on two health groups, `/actuator/health/liveness` and `/actuator/health/readiness`. By default both only look at the application's own state.
- Readiness here also checks the database: `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db`. Without the database store can serve nothing, so it should not get requests. RabbitMQ or Kafka down only delays emails and events; that should not take store off the entry.
- K14: with RabbitMQ stopped for 75 s, the full `/actuator/health` was 503, liveness and readiness were UP, and the three services stayed at 0 restarts.
- A cold Java start takes 1–2 minutes, hence the `startupProbe` (up to 4 minutes); otherwise liveness would kill it before it finished starting.

### 2.7 The entry: Gateway API + Envoy Gateway
- Design ADR-13 says Ingress. While building it turned out that ingress-nginx (the most used Ingress controller) has had no releases or security fixes since March 2026, and kind's docs point to Gateway API. Christy chose Gateway API (Q7).
- Three resources: **GatewayClass** (which controller: Envoy Gateway) → **Gateway** (listens on 80) → **HTTPRoute** (which path goes to which Service): [gateway.yaml](../../k8s/gateway/gateway.yaml), [routes.yaml](../../k8s/gateway/routes.yaml).
- **Path rewrite**: the design's external path is `/api/store`; store's own paths are `/api/user`, `/api/orders`, ... `URLRewrite` with `ReplacePrefixMatch: /api` turns `/api/store/user/login` into `/api/user/login` before sending it to store. No Java change.
- **Longest prefix wins**: the `/api/store` rule is longer than `/api`, so it matches first. The `/api` catch-all uses Envoy Gateway's `HTTPRouteFilter` (`directResponse`) to answer 404. Without it `POST /api/bank/transfer` would fall through to `/`, and the frontend would answer 200 with index.html, hiding the mistake.
- kind has no cloud load balancer, so Envoy's Service is a NodePort 30080 (set in the `EnvoyProxy` resource), and `extraPortMappings` connects it to port 80 on this machine.

### 2.8 `enableServiceLinks: false`
- By default Kubernetes gives each pod variables for **every** Service in the namespace: `<NAME>_SERVICE_HOST`, `<NAME>_PORT=tcp://...`.
- A Service named `kafka` puts `KAFKA_PORT=tcp://10.96.x.x:9092` into pods. The apache/kafka image turns every `KAFKA_*` variable into a broker setting, so the broker gets a wrong `port`. It is off for all pods, which also makes "each pod has only the variables in its manifest" true (K10).

### 2.9 JVM memory in a container
- `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`: maximum heap = 75% of the container's memory limit (768Mi). The JVM reads the cgroup limit. The default is only 25%. At 100%, off-heap memory (thread stacks, metaspace, direct buffers) would push the container over its limit and get it OOM-killed.

---

## 3. Request path

The browser clicks "Sign in":

```
browser  POST http://localhost/api/store/user/login       (frontend baseURL is the relative /api/store)
  → Docker port mapping 127.0.0.1:80 → kind node container port 30080   kind-config.yaml
  → NodePort Service envoy-shop-shop-... → Envoy proxy pod               gateway.yaml (EnvoyProxy)
  → HTTPRoute rule /api/store: rewritten to /api/user/login              routes.yaml
  → Service store:8082 (cluster DNS) → store pod                         apps/store.yaml
  → AccountController.login (/api/user/login)                           storeService/.../AccountController.java
```

After an order, store charges via bank (inside the cluster, not through the entry):

```
OutboxProcessor (every 5 s) → BankAdapter → http://bank:8084/api/bank/transfer
  BANK_SERVICE_URL comes from ConfigMap store; bank is the Service name, resolved by cluster DNS
```

When bank validates a token it calls `http://store:8082/api/user/validate-token`, also inside the cluster. So the entry **does not need** transfer or refund; it exposes only the two GETs the frontend uses (Q5).

---

## 4. Why this way

| Choice | Alternative | Why / cost |
|---|---|---|
| Gateway API + Envoy Gateway (Christy, Q7) | ingress-nginx (the design's literal word) | ingress-nginx is retired with no security fixes; path rewrite is a standard field in Gateway API. Cost: more CRDs; the design's "Ingress" needs a note |
| External paths as in the design, rewritten at the entry (Christy, Q1) | add a `/api/store` prefix in store's Java code | A is changing store and delivery now, so a path change would conflict; service-to-service calls would change too. Cost: external paths differ from code paths |
| Frontend uses relative paths (Christy, Q2) | inject API URLs at build time + CORS on the backends | same origin means no CORS; compose's nginx forwards the same table. Cost: local `npm start` needs a proxy |
| bank exposes two GETs only (Christy, Q5) | expose all of `/api/bank` | transfer and refund have no authentication; the design gives the frontend only "accounts, balance" |
| Kafka has a volume (Christy, Q3) | no volume, re-run kafka-init after each restart | without it topics and consumer offsets are lost |
| store: 1 replica + Recreate (Christy, Q4) | RollingUpdate | two OutboxProcessors would take the same task. Cost: a short outage per update |
| 🤖 one Secret per reader | one big Secret for all | a big Secret is back to "postgres has every password" |
| 🤖 db-init as a re-runnable Job | keep the initdb script | initdb runs only on an empty volume; changing a password means wiping data. The Job just runs again |
| 🤖 init container really logs in first | no waiting, let CrashLoopBackOff restart until dependencies are up | restart back-off grows (up to 5 minutes) and the logs fill with false errors |
| 🤖 readiness has the database, not RabbitMQ (Christy agreed, Q6) | readiness = full health | RabbitMQ down would take store off the entry; users could not even browse |
| 🤖 NodePort + `extraPortMappings` | cloud-provider-kind LoadBalancer IP | on Windows the Docker network IPs are not reachable from the host (to be confirmed); a port mapping is the direct way |
| 🤖 plain YAML + kustomize | a Helm chart | one environment, nothing to parameterise; Helm adds a template language |

---

## 5. Interview topics here

**Conclusion**: no Java code to "optimise" in this step; the topics are about deployment and operations. The best ones to talk about are D7 (health checks) and H1 (secrets), both with measured evidence.

| Topic | Where | Judgement here |
|---|---|---|
| 📒 D7 Actuator / health checks | three probes + `READINESS_INCLUDE` in [store.yaml](../../k8s/apps/store.yaml) | liveness = the process, readiness adds the database. A broken dependency means "stop sending traffic", not "restart". **Not needed** without external dependencies (frontend uses `/` for both) |
| 📒 H1 secrets | [apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1) | one Secret per reader, never on disk; a Secret is only base64, protected by RBAC and by staying out of git. Production adds etcd encryption and an external store (Key Vault / External Secrets). **Not needed** on a one-person local kind |
| 📒 H3 tech obsolescence | header of [gateway.yaml](../../k8s/gateway/gateway.yaml) | a new deployment does not pick the retired ingress-nginx; existing ones keep working but need a migration plan |
| 📒 F2 resilience | K19, K21, K23 | self-healing = controllers (Deployment / StatefulSet) + volumes + idempotency keys together: the pod dies, the new one retries with the same key, one charge |
| 📒 F8 Webhook | `delivery-webhook` in [routes.yaml](../../k8s/gateway/routes.yaml) | an unauthenticated callback is for inside the cluster only; forwarding a whole prefix easily exposes it too (F32) |
| 📒 C1 heap | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75` | in a container the heap is a share of the limit, leaving room off-heap; **no need** for a hand-written `-Xmx` |
| ➕ Gateway API vs Ingress | routes.yaml | split roles (GatewayClass / Gateway / Route); rewrite and method match are standard fields, not controller-specific annotations |
| ➕ StatefulSet vs Deployment | base/*.yaml | fixed names + a volume per pod: databases, brokers. Stateless services use Deployments |
| ➕ three probes | §2.6 | startup protects slow starts; liveness restarts; readiness takes the pod out of traffic |
| ➕ Service environment variables | `enableServiceLinks: false` | the pre-DNS mechanism; clashes with images that read `KAFKA_*` |
| ➕ kubectl version skew | [tools.ps1](../../k8s/scripts/tools.ps1) | kubectl is supported within one minor version of the cluster |

**Scenario anchors**:
- "When RabbitMQ was down, the full health endpoint returned 503, but liveness stayed up, so Kubernetes didn't restart anything. That's why liveness and readiness are separate."
- "Each pod gets its own Secret with only its own keys, and a script checks that against compose. The Secrets come straight from the env file, so there's no Secret file to commit by mistake."

---

## 6. Follow-up chain

1. **Liveness vs readiness?** → one restarts, the other takes the pod out of traffic.
2. **The database is down; which should fail?** → readiness. A restart does not fix the database, and it restarts every pod at once.
3. **How does Spring Boot know which checks belong to which probe?** → health groups. On Kubernetes it enables liveness and readiness groups that only look at application state; `management.endpoint.health.group.readiness.include` adds `db`.
4. **Java starts slowly; will liveness kill it?** → it would, hence the startupProbe: liveness does not start until it succeeds.
5. **After readiness fails, does the pod still get requests?** → not new ones: the Service's endpoints drop it. Long-lived connections (such as gRPC) may stay open; that needs client re-resolution or a connection age limit (to be confirmed once B's gRPC is in).

---

## 7. How to say it

- "I deployed the whole system to a local kind cluster. The browser only talks to one gateway, and it routes by path to six services."
- "Postgres, RabbitMQ and Kafka run as StatefulSets with volumes. I deleted each pod and checked the data and the queues were still there."
- "I killed the store pod right after an order. The new pod retried the payment with the same idempotency key, and the customer was charged once."

---

## 8. Reading

- Kubernetes · [Liveness, Readiness and Startup Probes](https://kubernetes.io/docs/tasks/configure-pod-container/configure-liveness-readiness-startup-probes/)
- Spring Boot · [Kubernetes Probes](https://docs.spring.io/spring-boot/reference/actuator/endpoints.html#actuator.endpoints.kubernetes-probes) (health groups, enabled automatically on Kubernetes)
- Kubernetes · [StatefulSets](https://kubernetes.io/docs/concepts/workloads/controllers/statefulset/)
- Kubernetes · [Secrets](https://kubernetes.io/docs/concepts/configuration/secret/) (base64 is not encryption)
- Kubernetes · [Deployments](https://kubernetes.io/docs/concepts/workloads/controllers/deployment/) (Recreate strategy)
- Gateway API · [HTTP path redirects and rewrites](https://gateway-api.sigs.k8s.io/guides/http-redirect-rewrite/) (`ReplacePrefixMatch`)
- Envoy Gateway · [Direct Response](https://gateway.envoyproxy.io/docs/tasks/traffic/direct-response/)
- Kubernetes Blog · [Ingress NGINX Retirement](https://kubernetes.io/blog/2025/11/11/ingress-nginx-retirement/)
- Kubernetes · [Version Skew Policy](https://kubernetes.io/releases/version-skew-policy/) (kubectl within one minor version)
- Kubernetes · [Connecting Applications with Services](https://kubernetes.io/docs/tutorials/services/connect-applications-service/) (Service environment variables, `enableServiceLinks`)
- kind · [Quick Start](https://kind.sigs.k8s.io/docs/user/quick-start/) (`kind load docker-image`)

---

## 9. Self-check

1. Why does store's readiness include the database but not RabbitMQ? What if it were the other way round?
<details><summary>Answer</summary>
Without the database store can handle nothing, so it should leave the entry (readiness fails). RabbitMQ down only affects emails and delivery jobs; ordering and browsing still work. With RabbitMQ in readiness, a RabbitMQ outage would cut store off completely, turning a small failure into a full outage. Neither belongs in liveness: restarting store does not fix an external dependency.
</details>

2. After deleting `kafka-0`, why are the topics still there? What happens without a volume, as in compose?
<details><summary>Answer</summary>
The StatefulSet's volumeClaimTemplates give kafka-0 a PVC. Deleting the pod keeps the PVC, and the new kafka-0 mounts the same volume, so topics and offsets are on disk. Without a volume the data is in the container's writable layer and disappears with the pod; with auto-create off, every producer and consumer gets UNKNOWN_TOPIC until someone re-runs kafka-init, and consumer progress is lost.
</details>

3. (Needs real understanding) Why does store use `Recreate`? With the default `RollingUpdate`, could a customer be charged twice?
<details><summary>Answer</summary>
RollingUpdate starts the new pod before stopping the old one, so two OutboxProcessors can pick the same PENDING PAY task. No double charge: both use the same idempotency key and bank de-duplicates by key. But when the same key reaches bank concurrently, the second call may get a 500 (09 A#6), which store treats as "unknown", leading to retries or PAY_UNKNOWN. Recreate avoids the overlap at the cost of a short outage. The real fix is a single-runner guarantee for OutboxProcessor (pg_try_advisory_xact_lock or SKIP LOCKED); then several replicas and rolling updates are fine.
</details>

---

## 10. Replay time

**60 minutes**:
1. `./k8s/scripts/up.ps1`; follow what each of the 6 steps does (10 min).
2. `./k8s/scripts/smoke-order.ps1` with §3's request path next to it (10 min).
3. Stop RabbitMQ and watch the probes: `kubectl -n shop scale statefulset rabbitmq --replicas=0`, check RESTARTS and `/actuator/health`, then scale back (10 min).
4. Delete the store pod right after an order / delete kafka-0 and list the topics (15 min).
5. Read the 🤖 rows in §4 and decide what you would pick; do the self-check (15 min).
