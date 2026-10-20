# Kubernetes (local kind)

**English** | [中文](README.zh-CN.md)

The whole system on a kind cluster on this machine (design step 8, ADR-13). The browser knows one address: `http://localhost`.

## Before you start

| What | Version | Notes |
|---|---|---|
| Docker Desktop | running | the kind node is a Docker container |
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.33.0 | one exe on PATH |
| kubectl | ≥ 1.33 | the node is 1.34, and kubectl supports one minor version of difference. Docker Desktop ships 1.32, too old. The scripts look for: `$env:KUBECTL` → `kubectl.exe` next to `kind.exe` → kubectl on PATH |
| `backend/.env` | filled in | the same file compose uses; the Secrets come from it |
| `shop-*` images | built | `docker compose -f backend/compose.yml build` |

Space: the node and the images live in Docker's virtual disk, about 4–5 GB more.

## Start

From the repository root (PowerShell 7):

```powershell
./k8s/scripts/up.ps1
```

A git worktree has no `backend/.env`; point to one: `./k8s/scripts/up.ps1 -EnvFile E:\study\5348\backend\.env`.

About 5 minutes the first time (it pulls the node image and postgres / rabbitmq / kafka), about 3 after that. Safe to run again.

## Check

```powershell
./k8s/scripts/smoke-order.ps1        # one order through the entry: charged once, delivery updates reach store
./k8s/scripts/check-config-keys.ps1  # each pod has compose's variable names, and no other service's DB password
./k8s/scripts/check-entry.ps1        # 58 requests to both entries (kind :80, compose :3000): open / 404 / page as expected
```

Open `http://localhost` in a browser.

## What is inside

```
k8s/
  kind-config.yaml        one node; only 127.0.0.1:80 is mapped in (compose's ports stay free)
  base/                   namespace, PostgreSQL, db-init Job, RabbitMQ, Kafka, kafka-init Job
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
| listed `/api/email/...` | email, unchanged |
| bank, tracking | nothing: the frontend calls neither (bank's transfer / refund are for store, inside the cluster) |
| anything else under `/api`, `/actuator`, `/internal` | 404 `not found` |

`frontend/nginx.conf` has the same list, so compose's `localhost:3000` behaves the same. A new frontend call needs a line in both; `check-entry.ps1` shows it if they differ.

## Decisions and why

- **Gateway API (Envoy Gateway), not Ingress**: ingress-nginx has had no releases or security fixes since March 2026, and kind's docs now point to Gateway API. The design's "single entry" stays the same.
- **One Secret per reader**: `apply-secrets.ps1` sends `backend/.env` straight to the cluster and writes nothing to disk, so there is no Secret file to commit by mistake. The postgres container gets only the superuser password; databases and logins come from the re-runnable `db-init` Job. `DATABASE_URL` contains a password, so the whole URL is a Secret.
- **Split probes**: liveness checks the process only; readiness adds the service's own database, not RabbitMQ or Kafka. The full `/actuator/health` includes RabbitMQ and answers 503 while RabbitMQ is down; as a liveness probe it would restart every service in a loop.
- **store: one replica, `Recreate`**: OutboxProcessor has no lock across instances, so two store pods would pick the same payment task. An update means a short outage.
- **Kafka has a volume**: in compose it has none, because `stop` keeps the data. In Kubernetes deleting the pod is like `down`; without a volume the topics would vanish.
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

# Remove everything (the cluster's data goes with it; compose's volume is not touched)
kind delete cluster --name shop
```

## Known limits

- Every stateful part has one replica; no high availability (ADR-13's cost).
- `kind delete cluster` deletes all data in the cluster.
- `GET /api/bank/account/{n}/balance` works without a token: bank itself has no authentication (an older issue).
- With relative paths in the frontend, local `npm start` needs a proxy (`src/setupProxy.js`); not done yet.
