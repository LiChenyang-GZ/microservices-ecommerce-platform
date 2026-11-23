# Kubernetes (local kind)

**English** | [中文](README.zh-CN.md)

The whole system on a kind cluster on this machine (design step 8, ADR-13). The browser knows one address: `http://localhost`.

## Before you start

| What | Version | Notes |
|---|---|---|
| Docker Desktop | running | the kind node is a Docker container |
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.33.0 | one exe on PATH |
| kubectl | ≥ 1.33 | the node is 1.34, and kubectl supports one minor version of difference. Docker Desktop ships 1.32, too old. The scripts look for: `$env:KUBECTL` → `kubectl.exe` next to `kind.exe` → kubectl on PATH |
| `backend/.env` | filled in | the same file compose uses; the Secrets come from it. Since the rate limits it needs `REDIS_PASSWORD`: after adding it to an older file, run `up.ps1` again |
| `shop-*` images | built | `docker compose -f backend/compose.yml build` |

Space: the node and the images live in Docker's virtual disk, about 4–5 GB more.

## Start

From the repository root (PowerShell 7):

```powershell
./k8s/scripts/up.ps1
```

A git worktree has no `backend/.env`; point to one: `./k8s/scripts/up.ps1 -EnvFile E:\study\5348\backend\.env`.

About 5 minutes the first time (it pulls the node image and postgres / rabbitmq / kafka), about 3 after that. Safe to run again: if the cluster already exists it starts a stopped node first, and restarts the deployments at the end so they run the images it just loaded.

## Check

```powershell
./k8s/scripts/smoke-order.ps1        # one order through the entry: charged once, delivery updates reach store
./k8s/scripts/check-config-keys.ps1  # each pod has compose's variable names, and no other service's DB password
./k8s/scripts/check-entry.ps1        # 55 requests to both entries (kind :80, compose :3000): open / 401 / 404 / page as expected
```

Open `http://localhost` in a browser.

## What is inside

```
k8s/
  kind-config.yaml        one node; only 127.0.0.1:80 is mapped in (compose's ports stay free)
  base/                   namespace, PostgreSQL, db-init Job, RabbitMQ, Kafka, kafka-init Job, Mailpit, Redis
  apps/                   store, bank, delivery, email, tracking, frontend
  gateway/gateway.yaml    the single entry: Envoy Gateway (Gateway API)
  gateway/routes.yaml     which path goes to which service
  scripts/                up, apply-secrets, smoke-order, check-config-keys, check-entry
```

The entry is an **allowlist**: only the method + path pairs the frontend calls are forwarded (`routes.yaml` lists them one by one). Everything else under `/api`, plus `/actuator` and `/internal`, gets 404 from the entry itself, so a new backend endpoint is not reachable from outside until someone adds it.

| Path | Goes to |
|---|---|
| `/` and any non-API path | frontend |
| listed `/api/store/x` | store's `/api/x` (store's own paths are `/api/user`, `/api/orders`, ...) |
| listed `/api/delivery/x` | delivery's `/api/x` |
| bank, tracking, email | nothing: the frontend calls none of them (bank's transfer / refund are for store, inside the cluster; a verification code is asked from store, which calls email) |
| anything else under `/api`, `/actuator`, `/internal` | 404 `not found` |

`frontend/nginx.conf` has the same list, so compose's `localhost:3000` behaves the same. A new frontend call needs a line in both; `check-entry.ps1` shows it if they differ.

## Decisions and why

- **Gateway API (Envoy Gateway), not Ingress**: ingress-nginx has had no releases or security fixes since March 2026, and kind's docs now point to Gateway API. The design's "single entry" stays the same.
- **One Secret per reader**: `apply-secrets.ps1` sends `backend/.env` straight to the cluster and writes nothing to disk, so there is no Secret file to commit by mistake. The postgres container gets only the superuser password; databases and logins come from the re-runnable `db-init` Job. `DATABASE_URL` contains a password, so the whole URL is a Secret.
- **Split probes**: liveness checks the process only; readiness adds the service's own database, not RabbitMQ or Kafka. The full `/actuator/health` includes RabbitMQ and answers 503 while RabbitMQ is down; as a liveness probe it would restart every service in a loop.
- **store: one replica, `Recreate`**: OutboxProcessor has no lock across instances, so two store pods would pick the same payment task. An update means a short outage.
- **Kafka has a volume**: in compose it has none, because `stop` keeps the data. In Kubernetes deleting the pod is like `down`; without a volume the topics would vanish.
- **Redis for store's rate limits** (`base/redis.yaml`): one pod, no volume and no snapshots, so a restart only starts the counts again. Its password is in Secret `redis`, and store gets the same value in Secret `store`. store does not wait for it: with Redis down store serves without limits and logs `ALERT rate_limit_unavailable` (fail-open), and its health does not include Redis, so Kubernetes does not take store out.
- **The client address**: Envoy appends the address it saw to `X-Forwarded-For`, and store counts by that last value. store sets `server.forward-headers-strategy=none`: on Kubernetes Spring Boot otherwise turns on Tomcat's `RemoteIpValve`, which skips private addresses as trusted proxies. kind's client is `172.22.0.1`, a private address, so a forged `X-Forwarded-For: 1.2.3.4` became the client and every forged value got a fresh quota. compose and MockMvc did not show it; only the cluster did.
- **`enableServiceLinks: false`**: otherwise Kubernetes gives every pod `KAFKA_PORT=tcp://...`, which the apache/kafka image reads as a broker setting.

## Everyday commands

`kubectl` below means the 1.33+ one (for example `& 'D:\Program Files (x86)\kind\kubectl.exe'`), not Docker Desktop's.

```powershell
# Code changed: rebuild the image, load it into the node, restart (the tag is always latest,
# so without a new load the node keeps the old image)
docker compose -f backend/compose.yml build store
kind load docker-image --name shop shop-store:latest
kubectl -n shop rollout restart deploy/store

# A password in backend/.env changed: update the Secrets, run db-init again (it sets the password again)
./k8s/scripts/apply-secrets.ps1
kubectl -n shop delete job db-init; kubectl apply -k k8s/base

# Start the rate-limit counts again (they live only in Redis; the scripts register about 10 users a run)
kubectl -n shop exec deploy/redis -- sh -c 'redis-cli -a "$REDIS_PASSWORD" --no-auth-warning FLUSHDB'

# Read the mail the shop sent (verification codes go to Mailpit; it is not behind the gateway)
kubectl -n shop port-forward svc/mailpit 8025:8025   # then http://localhost:8025

# Remove everything (the cluster's data goes with it; compose's volume is not touched)
kind delete cluster --name shop
```

## Known limits

- Every stateful part has one replica, Redis included; no high availability (ADR-13's cost).
- In a cloud with a load balancer in front of Envoy, the last `X-Forwarded-For` value would be the balancer's address, so every user would share one rate-limit count. Envoy must then trust that hop; check the rule in `ClientIp` again.
- `kind delete cluster` deletes all data in the cluster.
- bank's gRPC port 9090 is limited to store pods by a NetworkPolicy (end of `apps/bank.yaml`). kind's default network plugin enforces it; on a cluster whose plugin does not implement NetworkPolicy, only the service token protects bank.
- With relative paths in the frontend, local `npm start` needs a proxy (`src/setupProxy.js`); not done yet.
- Mail goes to Mailpit (`base/mailpit.yaml`), which is for this local cluster only: its API has no login, so any pod could read every verification code. Before deploying anywhere real, remove it and point email's `MAIL_*` at a real SMTP server.
