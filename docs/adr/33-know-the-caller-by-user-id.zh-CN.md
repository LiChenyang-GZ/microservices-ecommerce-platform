# ADR-33 · store 按令牌里的 userId 认人，不按邮箱

[English](33-know-the-caller-by-user-id.md) | **中文**

**状态：** 已采纳。修订 ADR-24（JWT 过滤器把邮箱放进请求、归属按它查）和 ADR-31（大小写混杂的老数据里，store 会把令牌认成别的账号）。

## 背景
store 的 JWT 里有 `userId`、`subject`（账号邮箱）和 `role`。过滤器只读邮箱、放进请求；`OrderProductService#accountOf` 按它找账号，所有归属检查（下单、查一单、我的订单、Pay Now、取消）都经过这个账号。

账号就是它的 id；邮箱只是它的一个字段，令牌里的邮箱还是**签令牌那一刻的快照**。ADR-31 把邮箱归一以后，审查 75 指出快照在没迁移的老数据上会怎样：库里有 `Victim@x.com`（id 5）和 `victim@x.com`（id 6）时，5 号的令牌被解析成 6 号，能看、付、取消 6 号的订单；只有 `Victim@x.com` 时，有效令牌谁都找不到。以后如果加"改邮箱"，所有活着的令牌也会这样坏掉。

## 决定（Christy 定：方案 A）

- **过滤器读 `userId`，要求是 JSON 整数**（jjwt-jackson 读成 `Integer` 或 `Long`）。没有、或者是字符串、小数、`5.0` 的令牌，保持匿名（受保护的接口 401）。store 签的令牌从 2025-10-30 起都带 `userId`，有效期 24 小时，所以不会把任何人踢下线。
- id 放进请求（`JwtAuthenticationFilter.USER_ID`）；`StoreController` 作为 `callerId` 传下去；`OrderProductService#accountOf(Long)` 用 `findById`。五条用户路径（下单、查一单、我的订单、Pay Now、取消）都经过它。
- **去掉了 `userEmail` 请求属性**，也删了 `JwtUserHelper`（它把令牌里的邮箱当"当前用户"递出去；没人调用）。principal 仍然是邮箱，只给 Spring 和日志用。
- **`validate-token` 用同一条规则**（审查 83）：没有 `userId` 的令牌在那里也无效，store、delivery、tracking 对"什么是有效令牌"说法一致。
- delivery 不动：配送单只有邮箱、没有 `userId`，所以仍按令牌里的邮箱判断。要改就得改表结构和事件，另一项。
- 没选：每次请求再核对"令牌里的邮箱 = 库里的邮箱"（方案 B：没有改邮箱的功能，只多一种失败，没有它能修好的情况）。

## 后果
- 在 store 里，令牌永远指向签它时的那个账号。大小写混杂的老数据不会再让一个人的令牌变成别人的、或者谁都不是；登录和 delivery 那边仍然需要 ADR-31 的迁移。
- 伪造别人的令牌仍然需要 `jwt.secret`，和以前完全一样（有密钥时，`subject` 也能写成任何邮箱）。没有任何东西变得更好伪造。
- `userId` 对应的账号不在了（只能是手工删库：代码里没有删账号的路径），回答和以前一样：看接口不同是 400、404 或 500（老的不一致，没改）。
- delivery 仍按令牌里的邮箱判归属；老的大小写混杂数据、以后的"改邮箱"，在 delivery 那边还会出问题。
- Pay Now 的日志记的是 id，不再是邮箱。

## 代码在哪
- store [JwtAuthenticationFilter.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java)（`USER_ID`、`wholeNumber`）、[StoreController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/StoreController.java)（`callerId`）、[OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java)（`accountOf`）、[TokenValidationService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/TokenValidationService.java)
- 测试：store `TokenIdentityIntegrationTest`；`CancelReadsLockedRowIntegrationTest`、`KeyConflictIntegrationTest`、`PayNowIntegrationTest`、`PayRetryAccountIntegrationTest` 里的调用改成传买家 id（`StoreIntegrationTest#buyerIdOf`）
- [docs/learning/20-know-the-caller-by-user-id.zh-CN.md](../learning/20-know-the-caller-by-user-id.zh-CN.md)
