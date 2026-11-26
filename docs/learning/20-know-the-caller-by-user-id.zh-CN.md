# 20 · 按令牌里的 userId 认人

[English](20-know-the-caller-by-user-id.md) | **中文**

> 提交（分支 `fix/identity-by-user-id`）：红 `3bd9932` → 绿 `5bfff7b`；审查后红 `dfefa47` → 绿 `ffd7995`。
> 怎么发现的：邮箱归一的审查（Obsidian 75 号 🟡3）。
> 回放时间：**15 分钟**
> 先读：[11 · 登录、归属和管理员角色](11-login-ownership-admin-role.zh-CN.md)、[18 · 一个邮箱一种写法](18-one-spelling-per-email.zh-CN.md)。

---

## 1. 问题

**store 按令牌里的邮箱认人，而那是一个快照。** 令牌里也有 `userId`，但过滤器只读邮箱，`accountOf` 按它查。没迁移的大小写混杂老数据里，一个人的令牌可能被认成另一个账号（能看、付、取消它的订单），也可能谁都不是。

修之前**真的发生过**的错误（红灯），在 [TokenIdentityIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/TokenIdentityIntegrationTest.java) 里。令牌都用 store 自己的密钥签，让 `userId` 和 `subject` 指向两个真实账号，或者缺 `userId`、类型不对：
- `userId` = A、`subject` = B：A 的单 404、B 的单能看，新订单记在 B 名下
- `subject` 是表里已经没有的邮箱：主人自己的单 404
- 没有 `userId`、字符串、小数：200
- `userId` 没有对应账号、`subject` 是 A 的邮箱：被当成 A
- 审查后补的：给 delivery、tracking 用的 `validate-token` 对没有 `userId` 的令牌回 `valid: true, userId: null`

---

## 2. 是什么（框架层面）

### 2.1 身份是主键，属性是快照
主键不会变；邮箱会（归一、以后的"改邮箱"、老数据）。令牌是签发那一刻冻住的一句话，除了 id 以外的内容都可能过时。认人用 id。

### 2.2 检查 claim 的类型
JSON 只有一种数字；Jackson 把 `5` 读成 `Integer`、`5.0` 读成 `Double`、`"5"` 读成 `String`。id 只认 `Integer` / `Long`。别的类型不是 store 签出来的，就当无效令牌，不去猜。

### 2.3 "有效令牌"只能有一个定义
有三个入口读 store 的令牌：过滤器、`validate-token`（delivery、tracking 调），以及经由它的其他服务。一个收、一个拒，规则就只成立了一半。

### 2.4 去掉诱人的那条路
`userEmail` 请求属性和 `JwtUserHelper` 还在提供"当前用户的邮箱"。没人读它们了，留着只会让下一个接口又按快照去找账号。

---

## 3. 请求路径

1. `Authorization: Bearer …` → `JwtAuthenticationFilter`：验签，读 `subject`、`role` 和 `userId`（必须是 `Integer` / `Long`）。没有 → 匿名 → 受保护的接口 401。
2. 过滤器设请求属性 `userId`；`StoreController#callerId` 读它。
3. `OrderProductService#accountOf(callerId)` → `findById` → 归属检查拿这个账号的 id 和订单的 `userId` 比。
4. delivery 调 `validate-token`，它现在也拒没有 `userId` 的令牌。

---

## 4. 为什么这样做

### 4.1 方案 A（Christy 定的）
store 按 id 认人；delivery（配送单没有 `userId`）留给另一次改动。

### 4.2 红灯里看到的 🤖
六条都红在预期的那一步。改完后第一次全量没编译过（漏了 import），结果文件是上一次的；清掉旧结果再跑才看出来。

### 4.3 审查发现了什么
0 必须修、0 建议修、6 可以不修。jjwt-jackson 把小 id 读成 `Integer`、大 id 读成 `Long`，所有真实的 id 都收；活着的令牌都带 `userId`，没人被踢下线。
- **修了**（红 `dfefa47` → 绿 `ffd7995`）：`validate-token` 拒没有 `userId` 的令牌；删了 `JwtUserHelper`；两句注释。
- **补了测试**（回归）：混合令牌打我的订单、Pay Now、取消；`userId` 是 `5.0`。
- **写明**：账号已经不在的令牌，各接口回 400 / 404 / 500 不一致（老问题）；delivery 仍按邮箱。

---

## 5. 这里的面试题

**结论**：用签过名的令牌里不会变的主键认人；令牌里其他东西都当快照。

| 题目 | 在哪 | 这里怎么做的 |
|---|---|---|
| ➕ JWT 里哪些 claim 可信 | `JwtAuthenticationFilter` | 签过名的 `userId` 决定是谁；`subject` 只是展示用的数据 |
| ➕ 稳定的标识符 | `OrderProductService#accountOf` | `findById`，不是 `findByEmail` |
| ➕ claim 类型检查 | `JwtAuthenticationFilter#wholeNumber` | 只认 `Integer` / `Long`；`5.0`、`"5"` 不是 id |
| ➕ 多个入口校验要一致 | `TokenValidationService` | 给其他服务的接口也用"没有 userId 就不是令牌" |

场景句：
- "我们的令牌里同时有 userId 和邮箱，但按邮箱找用户。邮箱归一以后发现，它是一个快照，在老数据上可能指向别的账号。我把 store 改成按签过名的 userId 认人，拒掉没有 userId 的令牌，让给其他服务用的校验接口也一致，并删掉还在提供邮箱的帮手。"

---

## 6. 追问链

1. **攻击者能把别人的 userId 写进令牌吗？** → 只有拿到签名密钥才行；有密钥的话本来就能写任何邮箱。没有变得更容易。
2. **现有的登录会断吗？** → 不会：store 从 2025-10-30 起签的令牌都带 `userId`，令牌只活 24 小时。
3. **为什么连 `5.0` 也拒？** → store 从来不签它；收"值是整数的数字"等于收 store 没签过的令牌。
4. **delivery 为什么不一起改？** → 配送单存的是邮箱、不是 userId，要改表结构和事件。

---

## 7. 怎么说

- "你是谁看你的 id；邮箱只是你的一个属性。"
- "令牌是快照，只信不会过时的部分。"
- "不该有人用的东西，就删掉。"

---

## 8. 延伸阅读

- RFC 7519 §4.1.2（`sub` claim 是签发方选的标识符）：<https://www.rfc-editor.org/rfc/rfc7519#section-4.1.2>
- OWASP JSON Web Token Cheat Sheet for Java：<https://cheatsheetseries.owasp.org/cheatsheets/JSON_Web_Token_for_Java_Cheat_Sheet.html>

---

## 9. 自测

1. 为什么令牌里的邮箱是快照？这里为什么要紧？
<details><summary>答案</summary>
签令牌时抄进去、之后不再更新。归一后的查询、老的大小写混杂数据、以后的改邮箱，都可能让它指向别的账号或谁都不是。
</details>

2. 为什么没有 `userId` 的令牌当无效处理，而不是退回按邮箱找？
<details><summary>答案</summary>
store 从 2025-10-30 起签的每个令牌都有 `userId`，没有的就不是 store 登录签出来的；按邮箱猜会把快照问题带回来。
</details>

3. （真懂才答得上）过滤器已经拒没有 `userId` 的令牌了，还有谁在收？为什么要紧？
<details><summary>答案</summary>
delivery 和 tracking 调的 `validate-token`：它回 `valid: true, userId: null`。规则要在每个入口都执行才成立。
</details>

---

## 10. 回放时间

**15 分钟**：读 §1 和 §2（6 分钟）→ `git checkout 3bd9932`，跑 `./gradlew :storeService:test --tests '*TokenIdentityIntegrationTest'`，看到六条红（4 分钟）→ `git checkout 5bfff7b`，跑绿（2 分钟）→ 自测（3 分钟）。
