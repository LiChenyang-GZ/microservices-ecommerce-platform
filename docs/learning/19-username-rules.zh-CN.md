# 19 · 用户名规则

[English](19-username-rules.md) | **中文**

> 提交（分支 `fix/username-no-at`）：红 `789e6e1` → 绿 `dd6570d`；审查后红 `e6a9ae7` → 绿 `40bed87`。
> 怎么发现的：邮箱归一的审查（Obsidian 75 号 🟡1）。
> 回放时间：**15 分钟**
> 先读：[18 · 一个邮箱一种写法](18-one-spelling-per-email.zh-CN.md)。

---

## 1. 问题

**用户名可以长得像别人的邮箱，登录还信了。** 登录先按邮箱找、再按用户名找。邮箱归一以后（学习文档 18），用户名 `Bob@X.com` 会找到邮箱是 `bob@x.com` 的那个账号。注册对用户名什么都收：`@`、空格、冒充 `admin` 的西里尔字母 `а`、控制字符、几百个字符。

修之前**真的发生过**的错误（红灯）：
- [UsernameRulesIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/UsernameRulesIntegrationTest.java)：带 `@`、空格、制表符、西里尔字母、2 个或 33 个字符的用户名都是 200；`"  bob "` 连空格一起存；用户名不合规、邮箱已注册未验证时，回的是"已注册未验证"而不是规则；审查后补的：带空格注册的人，同样带空格输就登录不了
- `RegisterPage.test.jsx`：页面把 `bob@x.com`、`ab`、`bob smith` 发了出去；审查后补的：页面放行了一个以全角空格结尾的名字（store 会拒）

---

## 2. 是什么（框架层面）

### 2.1 白名单，不是黑名单
禁 `@` 只修今天这个 bug。白名单（`[A-Za-z0-9._-]{3,32}`）把下一批也关上：空白、长得一样的字母、控制字符、长度。说清楚"允许什么"，比列出所有坏字符容易。

### 2.2 先校验，再透露
用户名在查邮箱之前检查。注定要失败的请求，不该打听到邮箱是否注册过，也不该让 bank 被调一次。

### 2.3 一个框里两种标识
"邮箱或用户名"放一个框里，前提是两种写法不可能重叠。合格的邮箱都有 `@`，新用户名都没有。这样先查哪个就无所谓了。

### 2.4 两边用同一种归一
注册 trim 了、登录没有：存的名字和输的名字对不上。而且 `trim()` 在 Java（≤ U+0020）和 JavaScript（所有 Unicode 空白）里不是同一个函数。页面要用 store 的定义，不然会放行 store 要拒的名字。

---

## 3. 请求路径

1. 页面按 Java 的方式 trim 用户名、查规则，发出去的是 trim 过的名字。
2. store `AccountService#createAccount`：邮箱归一、查格式 → 用户名 trim、查规则（400 + 规则）→ "邮箱已存在 / 未验证" → "用户名已存在" → 存、开银行户。
3. 登录：`getAccountByIdentifier` / `verifyLogin` → 邮箱（归一），找不到再 `byUsername`（trim）。

---

## 4. 为什么这样做

### 4.1 方案 B（Christy 定的）
白名单，只管注册；老账号不动（本机只有两个启动时建的管理员带 `@`，就是他们自己的邮箱）。

### 4.2 红灯里看到的 🤖
和学习文档 18 一样红错了地方：假 bank 在一个测试里对每次注册都回同一个账号号码，第二次注册撞了银行号码的唯一约束（500）。现在每次注册换一个新号码。

### 4.3 审查发现了什么
0 必须修、0 建议修、5 可以不修。新用户名不会再被当成邮箱，注册也占不了 `ADMIN_EMAIL`。
- **修了**（红 `e6a9ae7` → 绿 `40bed87`）：登录和注册一样 trim 用户名；页面只去 Java 会去的字符，并发送去过的名字；加一条"`ADMIN_EMAIL` 不能注册成用户名"的回归测试；改了两句过时的注释。
- **写明**：没有保留名（能注册 `admin`，但没有任何角色）；同时注册同一个用户名，第二个是 500；用户名仍然区分大小写。

---

## 5. 这里的面试题

**结论**：说清楚允许什么，先于一切检查，两边用同一个定义。

| 题目 | 在哪 | 这里怎么做的 |
|---|---|---|
| ➕ 白名单校验 | `AccountService#USERNAME_FORMAT` | 一个小的允许字符集，而不是追着坏字符跑 |
| ➕ 同形字（homoglyph） | `UsernameRulesIntegrationTest` | 冒充 `admin` 的西里尔 `а` 被 ASCII 字符集挡住 |
| ➕ 校验顺序和信息泄露 | `createAccount` | 用户名规则排在任何关于邮箱的回答之前 |
| ➕ 前端校验和后端校验 | `RegisterPage.jsx` | 页面帮忙，store 说了算；两边意思必须一样（`trim` 不一样） |

场景句：
- "邮箱归一以后，长得像邮箱的用户名在登录时会被当成别人的账号。我们把用户名改成白名单：3 到 32 个 ASCII 字母、数字、点、下划线、连字符，两种标识再也不会重叠。审查又发现登录没像注册那样 trim，而且 JavaScript 的 trim 比 Java 的去得多。"

---

## 6. 追问链

1. **为什么不只禁 `@`？** → 能解决重叠，但空格、长得一样的字母、控制字符还在。
2. **为什么先查用户名再查邮箱？** → 注定失败的请求什么都打听不到，也不调任何服务。
3. **已有的用户名怎么办？** → 不动，规则只管注册。本机只有管理员带 `@`，就是他们自己的邮箱。
4. **`admin` 是保留名吗？** → 不是。角色来自数据库和令牌，不看名字；要不要保留名是另一个决定。

---

## 7. 怎么说

- "给字符集开白名单，别追着坏字符跑。"
- "一个框里两种标识，写法就不能重叠。"
- "前端校验是体贴，必须和后端一个意思。"

---

## 8. 延伸阅读

- OWASP Input Validation Cheat Sheet（白名单）：<https://cheatsheetseries.owasp.org/cheatsheets/Input_Validation_Cheat_Sheet.html>
- Unicode Technical Report #36, Security Considerations（易混淆字符）：<https://www.unicode.org/reports/tr36/>

---

## 9. 自测

1. 为什么登录不会再把新用户名当成邮箱？
<details><summary>答案</summary>
合格的邮箱都有 `@`，新用户名都没有，所以按邮箱查永远不会通过用户名的文字找到账号。
</details>

2. 为什么先查用户名、再查邮箱？
<details><summary>答案</summary>
用户名不合规的请求反正要失败；先查它，这个请求就拿不到"邮箱已存在 / 未验证"，也不会调 bank。
</details>

3. （真懂才答得上）页面和 store 都 "trim"，为什么页面还会放行 store 要拒的名字？
<details><summary>答案</summary>
JavaScript 的 `trim()` 去所有 Unicode 空白（全角空格、NBSP），Java 的只去 ≤ U+0020。`newuser` 加一个全角空格在页面通过、到 store 失败。现在页面只去 Java 会去的字符。
</details>

---

## 10. 回放时间

**15 分钟**：读 §1 和 §2（6 分钟）→ `git checkout 789e6e1`，跑 `./gradlew :storeService:test --tests '*UsernameRulesIntegrationTest'`，看到一串 200（4 分钟）→ `git checkout dd6570d`，跑绿（2 分钟）→ 自测（3 分钟）。
