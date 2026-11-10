# ADR-22 · bank 的 gRPC 回复怎么对应到 成功 / 拒绝 / 未知

[English](22-grpc-result-mapping.md) | **中文**

**状态：** 已采纳

## 背景
ADR-05 要求每次调 bank 只能落到三种结果之一：SUCCEEDED、REJECTED（bank 明确说不行）、UNKNOWN（钱可能动了也可能没动）。改成 gRPC（ADR-10）以后，出现了设计没覆盖的情况：状态 OK 但没设 outcome；金额字符串不合法；bank 重启时，gRPC 的重连退避可能让 5 次快速重试一下子全部失败。

## 决定
- bank 用 **状态 OK + outcome**（`SUCCEEDED` / `REJECTED`）回答自己的业务结果。金额解析不了回 `REJECTED`，不回 `INVALID_ARGUMENT`：错误状态到了 store 会变成 UNKNOWN，每 10 分钟重试一次，永远不会成功。
- store 这边，**任何 gRPC 错误状态都是 UNKNOWN**（`DEADLINE_EXCEEDED`、`UNAVAILABLE` 可能发生在 bank 已经转完钱之后）。
- 状态 OK 但 outcome 没设，或者是 store 不认识的值，算 **UNKNOWN，绝不算 REJECTED**：错判成 REJECTED 会把付款标成 FAILED，Pay Now 就会用新键再扣一次。
- 每次调用都有截止时间，并且用 `waitForReady`：bank 重启期间，调用会一直等连接，直到截止时间，而不是马上失败。
- 用最朴素的 grpc-java（`grpc-netty-shaded`），自己写一个小的 Spring 配置类，不用 Spring 的 gRPC starter。

## 代价与影响
- 只有 bank 明确说"不行"才会是 REJECTED；所有拿不准的都落到 UNKNOWN。对钱是安全的，代价是走更慢、带告警的那条路（ADR-05）。
- bank 要是忘了设 outcome，会表现为告警，而不是一堆错标成 FAILED 的付款。

## 代码在哪
- store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)、[BankCallResult.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankCallResult.java)；bank [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java)
- 测试：`BankGrpcMappingIntegrationTest`、`BankGrpcIntegrationTest`
- [docs/learning/05-grpc-bank-call.zh-CN.md](../learning/05-grpc-bank-call.zh-CN.md)
