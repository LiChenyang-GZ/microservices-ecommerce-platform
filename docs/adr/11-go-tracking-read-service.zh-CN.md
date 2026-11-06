# ADR-11 · 订单时间线是一个独立的 Go 读服务

[English](11-go-tracking-read-service.md) | **中文**

**状态：** 已采纳

## 背景
用户想看一个订单经历过的所有事情。把这个视图放进 store，就是给负责收钱的服务加读负载、加代码。

## 决定
tracking 用 Go 写，订阅两个 topic，把每个事件存进自己的库，只提供一个接口：`GET /api/tracking/orders/{orderId}/timeline`。它找 store 校验调用方的 token，只给下单的那个用户看。

## 代价与影响
- 相当于 CQRS 里的读模型：它影响不到下单和付款；数据坏了，清空以后重放 topic 就能重建。
- Go 镜像小（distroless、静态二进制），启动快。
- 多一种语言要维护。事件解析必须和 Java 完全一致，两边跑同一批示例事件（ADR-19）。
- store 挂了，时间线接口回 503，因为没法校验 token。

## 代码在哪
- [backend/trackingService](../../backend/trackingService/README.zh-CN.md)：[consumer](../../backend/trackingService/internal/consumer/consumer.go)、[httpapi](../../backend/trackingService/internal/httpapi/httpapi.go)、[auth](../../backend/trackingService/internal/auth/auth.go)
- [docs/learning/03-go-tracking-consumer.zh-CN.md](../learning/03-go-tracking-consumer.zh-CN.md)
