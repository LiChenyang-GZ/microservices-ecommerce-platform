# ADR-28 · 扣款重试只发第一次发送时的账号

[English](28-a-retry-sends-the-first-request.md) | **中文**

**状态：** 已采纳。修订 ADR-16（bank 比对请求内容，所以 store 必须发同样的内容）。被 ADR-29 修订：下面说的"下一件"做完了，键冲突当成"未知"。

## 背景
一条 PAY 任务可能发好几次：一轮里最多快速重试 5 次，结果未知以后每 10 分钟再试一次（ADR-05）。每次都用同一个键，但 `PaymentService#startPayAttempt` 每次发之前都**重新读**买家的银行账号。两次发送之间读到的东西变了，就会出错：
- **账号读不到**（被清空、整行被删、订单行没了）：任务被标 FAILED。FAILED 的意思是"bank 说了不"，页面就会显示 Pay Now，Pay Now 会换新键开始新的一次。如果第一次其实已经扣了，买家就付了两次。快速重试里（payment 还是 PENDING）也一样会这样，所以"是不是 PAY_UNKNOWN"问错了，该问的是"这个键发过没有"。
- **账号换了号**：同一个键带着另一个 `fromAccount` 发出去。bank 比对内容（ADR-16），回 `KEY_CONFLICT`、success = false，store 当成"拒绝"：又是 FAILED，又是 Pay Now。

现在没有清空或修改买家银行账号的接口，所以要手工改数据才会发生。这是第一次审查的发现 A#4。

## 决定

### 第一次发送记下账号，之后每次重试都发记下的（Christy 定的：方案 C）
- `payment_outbox` 加 `from_account`、`to_account`。任务第一次发送之前，`startPayAttempt` 读买家账号和 `bank.store.account`，写到任务上，同时 `retryCount` 加 1，在同一个事务里提交，提交之后才调 bank。
- 记过账号的任务不再读买家账号。金额来自只写一次的 `Payment.amount`。所以**一个键永远只对应一个请求**，"同一个键随便重试"才是安全的。
- 新的一次（FAILED 之后点 Pay Now）是一条新任务：重新读账号，记在它自己的键上。

### 从没发过、读不到账号：照旧 FAILED
`retryCount` 是在调用之前数的，0 就是 bank 从没收到过这个键。钱不可能动过，Pay Now 可以重来。"第一次发送之前订单被取消"用的是同一个道理。

### 这两列出现之前就发过、现在读不到账号：先停着
改动之前写的任务这两列是空的。如果它已经发过、买家账号又读不到，store 不发（不知道第一次用的是哪个账号），也不标 FAILED：payment 变成 `PAY_UNKNOWN`，`ALERT payment_no_account` 只打一次（之后每 10 分钟一条 WARN），10 分钟后再看。

人看到这条告警怎么处理：去 bank 按键查（`transaction_ref`）。查到了 → 把那笔交易的账号抄进任务的 `from_account` / `to_account`，下一次会发一模一样的请求，拿到 bank 的真实结果。查不到 → 扣款根本没到 bank，标 FAILED 是安全的。**不要**直接给买家绑一个新银行账号：同一个键、不同账号，就是 `KEY_CONFLICT`。

### bank 说扣了，订单行却没了：记下来，停
审查发现的：账号记下以后，订单行被删的任务照样会发出去。`onPaySucceeded` 原来找不到订单就抛异常、回滚，每轮重发，永远停不下来。现在记 payment 为 SUCCESS、关任务、打 `ALERT payment_no_order`。没有订单可发货；退不退由人定。

### 没选的
| 选项 | 为什么不选 |
|---|---|
| 只做 A：发过的任务读不到账号时不标 FAILED | "换了号"的情况没修，最后还是 `KEY_CONFLICT` → FAILED |
| 改成把 `KEY_CONFLICT` 当"结果未知" | 对，而且还要做（见下），但单靠它，所有换了号的重试都会卡在"未知"，而原来那个请求其实可以直接再发一次 |

## 代价与影响
- **老任务换了号，仍然可能扣两次。** 上线时正在路上的任务没有记账号；如果买家的账号在这期间被**换成了别的号**（不是清空），重试会发新号。能兜住所有"同一个键、不同请求"来源（老任务、以后多副本、以后的回归）的修法在 store 怎么读 bank 的回答：`KEY_CONFLICT` 不能算"拒绝"。要在 `bank.proto` 加一个 outcome。下一件。
- `payment_outbox` 多两列，`ddl-auto=update` 自动加（可空、没默认值，Postgres 只改元数据）。不需要启动 SQL（ADR-21）。回滚安全：老代码不认识这两列。
- 任务可能记了账号却没发出去（提交之后、调 bank 之前进程停了）。下一轮发的是同一个请求，安全；只是以后不会再因为"读不到账号"被标 FAILED。
- 如果有两个 `OutboxProcessor`，可能在谁都还没记账号的时候同时读到这条任务。store 只跑一个副本（ADR-17），现在碰不到。

## 代码位置
- store [PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java)（`startPayAttempt`、`holdUnknownWithoutAccount`、`onPaySucceeded`）、[PaymentOutbox.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/PaymentOutbox.java)（`fromAccount`、`toAccount`）
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)（`previousTransferResult`、`sameTransfer`：同一个键、不同请求为什么算冲突）
- 测试：store `PayRetryAccountIntegrationTest`
- [docs/learning/15-retry-sends-the-first-request.zh-CN.md](../learning/15-retry-sends-the-first-request.zh-CN.md)
