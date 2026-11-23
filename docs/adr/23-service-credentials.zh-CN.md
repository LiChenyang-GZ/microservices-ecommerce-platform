# ADR-23 · 服务之间的调用带共享密钥，每个被调方一把

[English](23-service-credentials.md) | **中文**

**状态：** 已采纳，由 25 补充（`verify-code` 只给 store）

## 背景
在这之前，网络里没有任何地方检查"是谁在调"。bank 的 gRPC `Transfer` / `Refund` 没有拦截器；bank 的 REST 不带 token 也放行，`POST /api/bank/transfer` 能从 `SYSTEM` 给任何账户转钱；delivery 的 `/internal/deliveries/cancel-by-order` 什么都不查；email 的通知接口能以商店的名义给任意邮箱发信。入口的允许名单（ADR-15）把这些挡在外面，但集群里任何一个 pod、compose 网络里任何一个容器、本机上任何一个进程都能直接调。

比较过三种让服务证明自己身份的办法：

| 选项 | 代价 |
|---|---|
| **每个被调方一把共享密钥**（选了这个） | 密钥泄露，那个服务就敞开了；换密钥要两边一起改。半天能做完，compose 和 kind 一样能跑 |
| 自建 CA 的 mTLS | 证书要生成、挂载、续期；每个探针和客户端都要走 TLS。本地大约 2–3 天，大部分时间花在基础设施上 |
| 调用方签发的短期 JWT | 不共享密钥、会过期，但多一套密钥管理 |

## 决定
- 每个被调方有**自己的**密钥：`BANK_SERVICE_TOKEN`、`DELIVERY_SERVICE_TOKEN`、`EMAIL_SERVICE_TOKEN`。调用方放在 HTTP 头 `X-Service-Token`，或 gRPC metadata 的 `x-service-token`（gRPC 的键必须小写）。

  | 被调方 | 在哪里查 | 没带或带错 |
  |---|---|---|
  | bank | gRPC（`ServerInterceptor` 包住整个服务）和所有 `/api/bank/*` REST 路径；`/actuator` 留给探针 | `UNAUTHENTICATED` / 401，什么都不执行 |
  | delivery | 只查 `/internal/*`；`/api/*` 还是原来的用户 token 过滤器 | 401 |
  | email | `/api/*` 下**除了** `send-verification`、`verify-code`（注册页在用）以外的所有路径，精确匹配。ADR-25 之后只剩 `send-verification` | 401 |

- email 的检查是**允许名单**：只有名单上的路径是公开的，以后新加的接口在有人打开之前都是关着的。旧过滤器用 `startsWith` 判断公开路径，`/api/email/send-verification-随便什么` 也能混过去。
- 用 `MessageDigest.isEqual` 比较（常数时间）。被拒的请求只记"no / wrong service token"和方法、路径，不记密钥。
- 每个服务（包括调用方 store）只要有一把密钥是空的，就**拒绝启动**，报错里点名那个键。没配密钥还能启动的服务，要么拒绝所有调用，要么更糟——跳过检查。
- store 给每个被调方只发那个被调方的密钥：每个被调方一个 `RestTemplate` 或 gRPC channel，头挂在那里。被调方拒绝时，store 原来给的结果不变，再加一行告警，因为密钥错了不会像超时那样自己好：
  - bank gRPC `UNAUTHENTICATED` → 仍是 UNKNOWN（ADR-22），同一个键重试，加 `ALERT bank_auth_failed key=…`。不能当 REJECTED：密钥配错是我们的错，不是买家的错。
  - bank REST 401（开户、按邮箱查账号）→ 仍返回 `null`，加 `ALERT bank_auth_failed op=…`，不写邮箱。
  - delivery 401 → 取消被拒，按"稍后再试"处理，不是"太晚了"，加 `ALERT delivery_auth_failed orderId=…`。
  - email 401 → 找回密码失败、密码不改，加 `ALERT email_auth_failed`。
- 没有调用方的接口**删掉**，而不是加保护：bank `POST /api/bank/transfer`、`/refund`、`GET /api/bank/account/me`；email 的四个通知接口和 `check-verified`（通知邮件走 RabbitMQ `email_queue`）。
- 网络：compose 不再发布 bank 的 gRPC 端口 9090。Kubernetes 上用 NetworkPolicy 只让 store 的 pod 连 bank:9090；8084 对所有 pod 开放（kubelet 探针要用，而且每条 REST 路径本来就要密钥）。
- `ServiceToken`、`ServiceTokenFilter` 每个服务各一份。`contracts` 是事件和 proto 模块，不该带 servlet 依赖；各服务是分开构建、分开部署的。

## 代价与影响
- **密钥泄露，那一个服务就敞开了**，直到换掉。store 拿着三把，store 被攻破就三个都开了；这是调用方的角色决定的，换成 mTLS 也一样。
- **换密钥会有空档。** 每个被调方只认一把。换的时候要改值（`backend/.env` 或 Secret）、两边都重启；中间这段时间付款回 UNKNOWN、由 outbox 重试，取消被拒，找回密码失败，都会打告警。被调方同时认"当前"和"上一把"就能消掉空档；没做。
- **启动检查只挡"空"，挡不住"错"。** 两边配了两个不同的非空值都能启动，要到第一次调用才出告警。
- **密钥在网络里是明文传的**（`usePlaintext()`、普通 HTTP）。能看到流量的人就能看到密钥。这是 mTLS 或服务网格主要能补上的东西。
- **`email_queue` 不在范围内。** 有 RabbitMQ 账号的都能往队列里放通知，这条路只靠 RabbitMQ 的账号密码挡。
- NetworkPolicy 只有在集群的网络插件实现了 NetworkPolicy 时才生效。kind 默认的 kindnet 从 v0.24 起会执行（v0.33 上实测：delivery → bank:9090 被挡）；换成不支持的插件，就只剩密钥在保护 bank。
- 三份过滤器要一起改。
- 这只解决"是哪个服务在调"。哪个**用户**能取消、退款哪张订单，谁是管理员，是另外的决定（后面两个鉴权 PR）。

## 代码在哪
- bank [ServiceToken.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java)、[ServiceTokenFilter.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceTokenFilter.java)、[FilterConfig.java](../../backend/bankService/src/main/java/com/comp5348/bank/config/FilterConfig.java)、[ServiceTokenServerInterceptor.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java)、[GrpcServerLifecycle.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java)
- delivery [ServiceToken.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/ServiceToken.java)、[ServiceTokenFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/ServiceTokenFilter.java)、[FilterConfig.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java)
- email [ServiceToken.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceToken.java)、[ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java)
- store [ServiceTokens.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/ServiceTokens.java)、[BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)、[DeliveryAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java)、[AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)
- 部署：[compose.yml](../../backend/compose.yml)、[.env.example](../../backend/.env.example)、[apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1)、[check-config-keys.ps1](../../k8s/scripts/check-config-keys.ps1)、[bank.yaml](../../k8s/apps/bank.yaml)（NetworkPolicy 在最后）
- 测试：bank `BankGrpcAuthIntegrationTest`、`BankRestAuthIntegrationTest`、`OldBankRestEndpointsRemovedTest`；delivery `CancelByOrderAuthIntegrationTest`；email `EmailServiceTokenIntegrationTest`、`OldEmailEndpointsRemovedTest`；store `BankServiceTokenIntegrationTest`、`DeliveryServiceTokenIntegrationTest`、`EmailServiceTokenIntegrationTest`、`EmailCallTimeoutIntegrationTest`；每个服务一个 `*StartupWithoutServiceTokenTest`
- [docs/learning/10-service-credentials.zh-CN.md](../learning/10-service-credentials.zh-CN.md)
