# 16 · 键冲突不是失败

[English](16-key-conflict-is-not-a-failure.md) | **中文**

> 提交（分支 `fix/key-conflict-is-unknown`）：红 `31674d5` → 绿 `f85ea0b`；审查之后 `d9786b4`。
> 怎么发现的：A#4 审查的第 2 条（"老任务换了号仍然可能扣两次"）。
> 回放时间：**25 分钟**
> 先读：[15 · 重试发第一次的请求](15-retry-sends-the-first-request.zh-CN.md)、[05 · gRPC 调 bank](05-grpc-bank-call.zh-CN.md)（outcome 和错误码怎么映射）。

---

## 1. 问题

**bank 有两种"不"，却都发成 `REJECTED`。** 余额不足的意思是钱没动。键冲突（这个键已经用于另一个请求）的意思是*这一次*钱没动，但这个键第一次的请求可能动过钱。store 把两种都当成确定失败：FAILED、`PaymentFailed`、Pay Now 换新键——可能扣两次。退款遇到冲突就放弃了。

修之前这些测试**真的红了**：
- [BankGrpcIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcIntegrationTest.java)：用过的扣款键带不同内容、用过的退款键退另一笔交易，都回 `REJECTED`
- [KeyConflictIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/payment/KeyConflictIntegrationTest.java)：没有专门的告警，这一轮把冲突的请求连发了 5 次

---

## 2. 是什么（框架层面）

### 2.1 冲突以前意味着什么、现在意味着什么
ADR-16 加内容比对，防的是攻击者先用了你能被猜到的键：他的请求动的是他自己的钱，所以回"不"是对的。键有了 64 位随机段、bank 前面有了服务凭据（ADR-23），攻击者这条路就没了。剩下的只有 store 自己用同一个键发了两个不同的请求，那第一个可能已经扣了钱。

### 2.2 代价不对称决定默认值
错当成"不" → 扣两次。错当成"未知" → 一单等人处理，15 分钟后自动取消。和钱有关的系统，分不清的时候，选那个能补救的错。

### 2.3 新加一个枚举值，老客户端为什么安全
`Outcome.KEY_CONFLICT = 3`。protobuf 会保留不认识的枚举数字（Java 读成 `UNRECOGNIZED`），store 的 `BankAdapter#fromOutcome` 本来就把不认识的都当"未知"（gRPC 那一步定的老规则）。所以新 bank 配老 store 是安全的，**bank 先发布**。反过来（新 store、老 bank）还是会收到 `REJECTED`。

### 2.4 错误码 vs outcome
合约的约定：状态 OK = bank 回答了；错误码 = 没有回答。冲突是一个回答，所以是 outcome，不是 `ALREADY_EXISTS`。第三个选项是比对 reason 文字，最脆。

### 2.5 不会变的东西别重试
超时可能一秒后就好了，所以 store 快速重试 5 次。冲突在有人改任务之前每次都是同样的回答，所以这一轮立刻停，10 分钟后再看。每单只告警一次，之后打 WARN。

---

## 3. 请求路径

1. store 用账号 B 发 `PAY-x-1-…`；bank 发现这个键用 A 用过。
2. bank：`previousTransferResult` → `TransferResponse.keyConflict(...)` → gRPC `Outcome.KEY_CONFLICT`。钱不动。
3. store：`fromOutcome` → `BankCallResult.keyConflict` → `OutboxProcessor#runPayTask` 结束这一轮 → `PaymentService#onPayKeyConflict`：`PAY_UNKNOWN`、`ALERT payment_key_conflict`、10 分钟后再看。
4. `PAY_UNKNOWN` 时 Pay Now 什么都不做。人把 bank 那边的账号抄进任务；下一次拿到 bank 的真实结果。

退款走同样的路到 `onRefundKeyConflict`（`REFUND_UNKNOWN`、`ALERT refund_key_conflict`）。

---

## 4. 为什么这样做

### 4.1 新 outcome、当未知、每 10 分钟再试（Christy 定的）
关口 0 的四个选择都按推荐：加一个 outcome；扣款和退款都当未知并告警一次；继续重试，这样人工修好以后不用再多做一步。

### 4.2 红灯说明了什么 🤖
proto 有了新值以后，store 那条老的 default 就已经把 payment 当成未知了；钱是安全的，靠的是几个月前为了别的原因写的一条规则。store 这次改的是运维层面：专门的告警、只打一次、同一轮不再重发。

### 4.3 审查发现了什么
没有必须修、没有建议修。可以修：bank 直接调 service 的冲突测试只查了 `success == false`（现在查标记，去掉标记会变红）；注释和 contracts README 还写着"冲突 = REJECTED"；退款这边没写人工处理；发布顺序没写；第一笔本来就失败的键也要等人处理（故意保留）。

---

## 5. 这里的面试点

**结论**：一个回答如果可能意味着"钱动过"，它就不是失败。

| 考点 | 在哪 | 这里的选择 |
|---|---|---|
| ➕ 失败 vs 未知 | `onPayKeyConflict` | 错了的代价是扣两次时，当未知 |
| ➕ protobuf 枚举演进 | `bank.proto`、`fromOutcome` | 只加值；老读者把不认识的值映射到安全默认；生产方先发布 |
| ➕ 错误码 vs 返回体里的 outcome | `BankGrpcService#outcome` | 回答就是 outcome；错误码表示没有回答 |
| ➕ 按错误类型定重试策略 | `OutboxProcessor#runPayTask` | 暂时的 → 快速重试；确定的 → 停、慢重试、告警 |
| ➕ 威胁模型变了 | ADR-16 → ADR-29 | 为攻击者设计的规则，在攻击者被挡在外面以后变成了正确性问题 |
| ➕ 只告警一次 | `alertOnce` | 第一次冲突 ALERT，之后 WARN |

场景锚点：
- "我们的 bank 把'用过的幂等键带来不同内容'当成拒绝。键能猜的时候这是对的；键改成随机、调用方也要鉴权以后，冲突只可能是我们自己改了请求的重试，钱可能动过。我加了 KEY_CONFLICT 这个 outcome，store 现在把它当未知。"
- "红灯让我意外：store 这边其实已经安全了，因为一条更早的规则把不认识的枚举值当未知。也正因为这样，bank 可以先上线。"

---

## 6. 追问链

1. **为什么不保留 REJECTED？** → 冲突说明不了第一个请求动没动钱；REJECTED 却告诉 store"没动"。
2. **为什么不让 bank 返回第一笔交易的结果？** → 那样"同一个键、不同请求"就成了拿到回答的办法，ADR-16 不允许；交给人处理。
3. **store 比 bank 先上线会怎样？** → 老 bank 对冲突还是回 REJECTED；所以 bank 先上。
4. **为什么停掉快速重试？** → 请求不变，回答就不会变。
5. **退款会不会退两次？** → 不会：退款键不变，bank 在动钱之前就拒绝，而且同一笔原交易 bank 不会退第二次。

---

## 7. 怎么说

- "可能动过钱的回答，就不是失败。"
- "枚举只加值；老读者回落到安全默认；生产方先发布。"
- "会变的才重试；不会变的就告警。"

---

## 8. 延伸阅读

- Protocol Buffers，*Enum behavior*（开放枚举，保留不认识的值）：<https://protobuf.dev/programming-guides/enum/>
- gRPC，*Status codes and their use*：<https://grpc.github.io/grpc/core/md_doc_statuscodes.html>

---

## 9. 自测

1. 写 ADR-16 的时候，"冲突 = 拒绝"为什么是对的？现在为什么错了？
<details><summary>答案</summary>
那时冲突意味着攻击者用你能被猜到的键做了他自己的请求，你的钱没动。现在键是随机的、只有 store 能调 bank，冲突意味着 store 用同一个键发了两个不同的请求，第一个可能已经扣了钱。
</details>

2. bank 和 store 谁先发布？为什么？
<details><summary>答案</summary>
bank。老 store 把不认识的 outcome 当未知，是安全的。新 store 配老 bank，冲突还是 REJECTED，会标 FAILED。
</details>

3. （真懂才答得出）store 的红灯只红在告警那一句。解释为什么状态断言已经过了，以及 store 这次补了什么。
<details><summary>答案</summary>
proto 加了 `KEY_CONFLICT = 3` 以后，store 生成的枚举认识这个值，但 `fromOutcome` 没有对应的分支，走 `default` 返回未知：快速重试，然后 `PAY_UNKNOWN`。这次补的是专门的路径：发一次就结束这一轮，`ALERT payment_key_conflict` 只打一次，退款同理。
</details>

---

## 10. 回放时间

**25 分钟**：读 §1、§2（10 分钟）→ `git checkout 31674d5`，跑 `./gradlew :bankService:test --tests '*BankGrpcIntegrationTest'`，看到 REJECTED（5 分钟）→ `git checkout f85ea0b`，跑它和 `*KeyConflictIntegrationTest`，都绿（5 分钟）→ 自测（5 分钟）。
