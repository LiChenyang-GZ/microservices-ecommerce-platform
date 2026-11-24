# ADR-29 · 键冲突不是失败：bank 回 KEY_CONFLICT，store 当成"未知"

[English](29-key-conflict-is-not-a-failure.md) | **中文**

**状态：** 已采纳。修订 ADR-16（用过的键带来不同请求，原来是"拒绝"）和 ADR-28（它说的"下一件"）。

## 背景
bank 会把用过的幂等键和它第一次带来的请求比对（ADR-16）。请求不一样就回 `success = false`，gRPC 层发成 `REJECTED`，和余额不足是同一个结果。store 把 `REJECTED` 读成"钱没动"：扣款变 FAILED、发出 `PaymentFailed` 事件、Pay Now 换新键重来；退款直接放弃。

ADR-16 防的是**别人先用了你的键**。那时键能猜，谁都能调 bank；别人的请求动的是他自己的钱，不是你的，所以回"不"是对的。这两个前提现在都没了：键带 64 位随机段、从不离开 store，bank 只认 store 的服务凭据（ADR-23）。

所以现在撞上冲突，几乎一定是 **store 自己用同一个键发了不同的请求**：ADR-28 上线时正在路上、买家账号又换了的任务，以后的多副本，以后的回归。这个键第一次的请求可能已经扣了钱或退了钱。读成"不"就会扣两次。这是 A#4 审查的第 2 条发现。

两种错的代价不一样：钱动了却说"不" → 扣两次；钱没动却说"未知" → 一单要等人看一眼（反正 15 分钟后会自动取消）。

## 决定（Christy 定的，都按推荐）

### bank 回 `KEY_CONFLICT`
`bank.proto` 的 `Outcome` 加 `KEY_CONFLICT = 3`。`BankService` 给它的两个冲突出口（`previousTransferResult`、`previousRefundResult`）打上标记，`BankGrpcService` 发出这个 outcome。真正的拒绝（余额不足、账户不存在、金额不对、没东西可退）还是 `REJECTED`：bank 在动钱之前就返回了。

没选的：回 gRPC 错误码（`ALREADY_EXISTS`）不用改 proto，因为 store 本来就把所有错误码当"未知"，但它违背合约"状态 OK = bank 回答了、错误码 = 没有回答"的约定；store 去比对 reason 文字，哪天措辞一改就静悄悄坏掉。

### 扣款遇到冲突 = 未知
`PaymentService#onPayKeyConflict`：`PAY_UNKNOWN`，不标 FAILED，不发 `PaymentFailed`，Pay Now 什么都不做。`ALERT payment_key_conflict` 每单只打一次，之后打 WARN。这一轮立刻停（现在再发也是同样的回答），之后每 10 分钟用同一个键再发，这样人把任务的请求改对以后，下一次自己就能拿到 bank 的真实结果。

人怎么处理：把任务的请求和 bank 里这个键的那笔交易（`transaction_ref`）对一下，把 bank 那边的账号抄进任务的 `from_account` / `to_account`（ADR-28）。

### 退款遇到冲突也 = 未知
`PaymentService#onRefundKeyConflict`：`REFUND_UNKNOWN`，`ALERT refund_key_conflict` 只打一次，任务留着，每 10 分钟再试。原来是放弃（`ALERT refund_rejected`，任务 FAILED）。

人怎么处理：在 bank 找这个退款键的那笔交易。它退的就是这单真正的扣款 → 把 `payment.bank_txn_id` 改成那笔的原交易号，下一次就拿到成功。它退的是别的交易 → 键撞了：给这笔 payment 换一个退款键；bank 会查"同一笔原交易是不是已经退过"，不会退两次。

## 代价与影响
- **bank 要先于 store 发布**，或者一次一起换。store 不认识的 outcome 一律当"未知"（`BankAdapter#fromOutcome` 的 default），所以新 bank 配老 store 是安全的：payment 变成 `PAY_UNKNOWN`，打的是通用的 `ALERT payment_unknown`。新 store 配老 bank，冲突还是 `REJECTED`。
- **卡住的单子要人处理。** 冲突不会自己消失：任务每 10 分钟重试，直到有人把它的请求改对。每单只告警一次。
- **第一笔本来就失败时，也故意保守。** 如果这个键第一次的请求在 bank 就被拒了（余额不足）、store 却没收到回答，之后发的不同请求还是 `KEY_CONFLICT`，这单等人处理，而不是变成 FAILED。让 bank 告诉 store 第一笔怎么样了，就等于让"同一个键、不同请求"也能拿到回答，ADR-16 不允许。
- 红灯说明：proto 有了新值以后，`fromOutcome` 那条老规则（"不认识的 outcome 当未知"）就已经保住了钱；store 这次补的是专门的告警、只告警一次、同一轮不再重发。

## 代码位置
- 合约 [bank.proto](../../backend/contracts/src/main/proto/bank/v1/bank.proto)（`Outcome.KEY_CONFLICT`）、[contracts README](../../backend/contracts/README.zh-CN.md)
- bank [BankService.java](../../backend/bankService/src/main/java/com/comp5348/bank/service/BankService.java)（`previousTransferResult`、`previousRefundResult`）、[BankGrpcService.java](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/BankGrpcService.java)（`outcome`）、`TransferResponse` / `RefundResponse`（`keyConflict`）
- store [BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)（`fromOutcome`）、[OutboxProcessor.java](../../backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java)（`runPayTask`、`runRefundTask`）、[PaymentService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java)（`onPayKeyConflict`、`onRefundKeyConflict`、`alertOnce`）
- 测试：bank `BankGrpcIntegrationTest`、`BankKeyConflictIntegrationTest`；store `KeyConflictIntegrationTest`
- [docs/learning/16-key-conflict-is-not-a-failure.zh-CN.md](../learning/16-key-conflict-is-not-a-failure.zh-CN.md)
