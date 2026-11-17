# ADR-10 · 服务之间动钱用 gRPC，浏览器用 REST

[English](10-grpc-inside-rest-outside.md) | **中文**

**状态：** 已采纳

## 背景
store → bank 这一次调用出错就是钱的问题。走 HTTP + JSON 时，请求和响应的类型都很松，超时和业务失败也不好区分。

## 决定
- store 通过 gRPC 扣款和退款：`BankService.Transfer`、`BankService.Refund`，定义在共享 contracts 模块的 `bank.proto` 里。响应里带一个明确的结果（outcome）。
- 浏览器继续经过统一入口调 REST（ADR-13、ADR-15）。
- 不动钱的调用留在 REST：store 通过 HTTP 给用户开银行账户、按邮箱查账户，重置密码时通过 HTTP 调 email。

## 代价与影响
- 类型在编译时检查，客户端代码自动生成。每次调用有截止时间（deadline），超时就是"未知"（ADR-05）。
- 多一个 `.proto` 文件要维护；调试要用 grpcurl 这类工具（compose 里 bank 把 9090 发布出来就是为了这个）。

## 代码在哪
- [bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto)
- bank [BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java)；store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)
- [docs/learning/05-grpc-bank-call.zh-CN.md](../learning/05-grpc-bank-call.zh-CN.md)
