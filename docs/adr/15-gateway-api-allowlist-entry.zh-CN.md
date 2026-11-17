# ADR-15 · 统一入口用 Gateway API（Envoy Gateway），按白名单转发

[English](15-gateway-api-allowlist-entry.md) | **中文**

**状态：** 已采纳（取代 ADR-13 最初版本里的 "Ingress"）

## 背景
最初的设计用 Ingress。到做 Kubernetes 这一步时，ingress-nginx 从 2026 年 3 月起已经没有新版本和安全修复，kind 的文档也改推 Gateway API。另外，路由的第一版是按整个前缀转发（`/api/store/*`、`/api/delivery/*`），再给记得起来的内部路径一个个加 404。审查发现漏了两个：delivery 不要 token 的 `cancel-by-order`（谁都能取消配送单，进而触发退款），以及 store 的 `payment-success`（不经过 bank 就把订单标成 PAID）。

## 决定
- 入口是 Envoy Gateway，用 Gateway API 的资源配置（`Gateway`、`HTTPRoute`）。
- 路由是**白名单**：只转发前端真正调用的 方法 + 路径，一条一条列出来。`/api` 下其他所有路径、`/actuator`、`/internal` 都由入口自己回 404。
- 对外路径带服务名，转发时改写：`/api/store/x` 到 store 的 `/api/x`，`/api/delivery/x` 到 delivery 的 `/api/x`。Java 服务保留自己的路径；前端用相对路径，只知道这一个入口。
- bank 和 tracking 没有路由，前端不调它们。
- compose 在 `frontend/nginx.conf` 里有同一份清单，所以 `localhost:3000` 和 `localhost` 行为一样；`check-entry.ps1` 对两个入口各发 58 个请求，有不一致就报出来。

## 代价与影响
- 新加的后端接口，没人把它加进两份清单之前，外面访问不到。
- 两份清单要保持一致（脚本会检查）。
- 多装一个控制器 Envoy Gateway（`up.ps1` 会装）。
- 经过入口偶发 503（发现 I2），原因还没查清。

## 代码在哪
- [gateway.yaml](../../k8s/gateway/gateway.yaml)、[routes.yaml](../../k8s/gateway/routes.yaml)、[nginx.conf](../../frontend/nginx.conf)、[check-entry.ps1](../../k8s/scripts/check-entry.ps1)
