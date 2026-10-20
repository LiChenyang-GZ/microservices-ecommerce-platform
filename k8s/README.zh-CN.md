# Kubernetes（本地 kind）

[English](README.md) | **中文**

把整套系统部署到本机的 kind 集群（设计第 8 步，ADR-13）。浏览器只认一个地址：`http://localhost`。

## 先准备

| 东西 | 版本 | 说明 |
|---|---|---|
| Docker Desktop | 跑着 | kind 的节点就是一个 Docker 容器 |
| [kind](https://kind.sigs.k8s.io/docs/user/quick-start/#installation) | v0.33.0 | 一个 exe，放进 PATH |
| kubectl | ≥ 1.33 | 节点是 1.34；kubectl 只保证和集群差一个小版本。Docker Desktop 自带的是 1.32，太老。脚本按这个顺序找：环境变量 `KUBECTL` → 和 `kind.exe` 同目录的 `kubectl.exe` → PATH 里的 |
| `backend/.env` | 填好 | 和 compose 用同一个文件，Secret 从这里生成 |
| `shop-*` 镜像 | 构建好 | `docker compose -f backend/compose.yml build` |

空间：节点和镜像都在 Docker 的虚拟磁盘里，约多占 4–5 GB。

## 起来

在仓库根目录（PowerShell 7）：

```powershell
./k8s/scripts/up.ps1
```

在 git worktree 里没有 `backend/.env`，就指过去：`./k8s/scripts/up.ps1 -EnvFile E:\study\5348\backend\.env`。

第一次约 5 分钟（要拉节点镜像和 postgres / rabbitmq / kafka），之后约 3 分钟。可以重复跑。

## 验证

```powershell
./k8s/scripts/smoke-order.ps1        # 经入口下一单：钱只扣一次、配送状态回到 store
./k8s/scripts/check-config-keys.ps1  # 每个 pod 的变量名和 compose 一致，没有别人的库密码
```

浏览器打开 `http://localhost`。

## 里面有什么

```
k8s/
  kind-config.yaml        一个节点；只把 127.0.0.1:80 映射进去（compose 的端口都空着）
  base/                   namespace、PostgreSQL、db-init Job、RabbitMQ、Kafka、kafka-init Job
  apps/                   store、bank、delivery、email、tracking、frontend
  gateway/gateway.yaml    唯一入口：Envoy Gateway（Gateway API）
  gateway/routes.yaml     哪个路径去哪个服务
  scripts/                up、apply-secrets、smoke-order、check-config-keys
```

| 路径 | 去哪 |
|---|---|
| `/` | frontend |
| `/api/store/x` | store 的 `/api/x`（store 自己的路径是 `/api/user`、`/api/orders`…） |
| `/api/delivery/x` | delivery 的 `/api/x` |
| `/api/email/…`、`/api/tracking/…` | 原样转发 |
| `/api/bank/account/me`、`/api/bank/account/{n}/balance` | bank，**只有这两条 GET** |
| 其他 `/api/…`、`/actuator`、`/internal`、`/api/store/delivery-webhook` | 404 |

`frontend/nginx.conf` 里有同一张表，所以 compose 的 `localhost:3000` 也照样能用。

## 几个决定和原因

- **入口用 Gateway API（Envoy Gateway），不用 Ingress**：ingress-nginx 从 2026 年 3 月起不再发版、不修安全漏洞；kind 文档也改推 Gateway API。设计说的"唯一入口"不变。
- **每个读者一个 Secret**：`apply-secrets.ps1` 从 `backend/.env` 直接写进集群，不落盘，所以没有能被误提交的 Secret 文件。postgres 容器只拿超级用户密码；建库建账号交给可重跑的 `db-init` Job。`DATABASE_URL` 里嵌着密码，所以整条放 Secret。
- **探针分开**：liveness 只看进程；readiness 加上自己的数据库，不含 RabbitMQ / Kafka。完整的 `/actuator/health` 含 RabbitMQ，RabbitMQ 一挂它就是 503，拿它当 liveness 会让所有服务被反复重启。
- **store 1 副本 + `Recreate`**：OutboxProcessor 没有跨实例的锁，两个 store 并存会拿到同一个扣款任务。更新时会停几十秒。
- **Kafka 挂数据卷**：契约里 compose 不挂卷，因为 `stop` 能保留数据；K8s 删 pod 就等于 `down`，不挂卷 topic 会跟着消失。
- **`enableServiceLinks: false`**：否则 K8s 会给每个 pod 注入 `KAFKA_PORT=tcp://…`，apache/kafka 镜像会把它当成 broker 配置。

## 常用操作

下面的 `kubectl` 指 1.33 以上那个（比如 `& 'D:\Program Files (x86)\kind\kubectl.exe'`），不是 Docker Desktop 自带的。

```powershell
# 改了代码：重建镜像 → 重新加载进节点 → 重启（标签都是 latest，不重新 load 节点里还是旧的）
docker compose -f backend/compose.yml build store
kind load docker-image --name shop shop-store:latest
kubectl -n shop rollout restart deploy/store

# 改了 backend/.env 里的密码：更新 Secret，再重跑 db-init（它会把密码重新设一遍）
./k8s/scripts/apply-secrets.ps1
kubectl -n shop delete job db-init; kubectl apply -k k8s/base

# 全部删掉（集群里的数据一起没，compose 的数据卷不受影响）
kind delete cluster --name shop
```

## 已知限制

- 所有有状态组件都是单副本，没有高可用（ADR-13 的代价）。
- `kind delete cluster` 会删掉集群里的所有数据。
- `GET /api/bank/account/{n}/balance` 不带 token 也能查余额：bank 本身没鉴权（老问题）。
- 前端用相对路径以后，本地 `npm start` 需要另配代理（`src/setupProxy.js`），还没做。
