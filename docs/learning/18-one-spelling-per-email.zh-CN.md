# 18 · 一个邮箱一种写法

[English](18-one-spelling-per-email.md) | **中文**

> 提交（分支 `fix/email-case-normalize`）：红 `8602a4e` → 绿 `b107644`；审查后红 `31ac233` → 绿 `d2b7241`。
> 怎么发现的：做抢注时顺带发现（Obsidian 58 号决定 3），当时作为已知限制写进 ADR-27。
> 回放时间：**20 分钟**
> 先读：[13 · 限流](13-rate-limits.zh-CN.md)、[14 · 认领被抢注的邮箱](14-claim-by-password-reset.zh-CN.md)。

---

## 1. 问题

**`Victim@x.com` 和 `victim@x.com` 是两个账号。** store 按用户输的原样存邮箱、查邮箱，Postgres 比较文本区分大小写，所以唯一约束两个都放进来：两个账号、两个银行账户、同一个信箱收两份码。日常碰到的是更小的版本：**手机会把首字母自动大写**，用 `victim@x.com` 注册的人在手机上输成 `Victim@x.com`，看到"邮箱或密码错误"；"忘记密码"说没有这个邮箱。粘贴时带了空格也一样。

修之前**真的发生过**的错误（红灯）：
- [EmailCaseIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailCaseIntegrationTest.java)：换个大小写再注册是 200；大写登录 401；存进去的邮箱带着大写；大写找回密码 400；带空格注册 400；用别的写法查仓库查不到
- [ClaimByPasswordResetIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClaimByPasswordResetIntegrationTest.java)：抢注的人的 `VICTIM@…` 和主人的 `victim@…` 成了两个账号
- [AdminBootstrapStartupTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/AdminBootstrapStartupTest.java)：已有普通账号 `taken@…` 时，`ADMIN_EMAIL=Taken@…` **给同一个信箱另建了一个账号，而且是管理员**
- [EmailAddressTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailAddressTest.java)：归一函数（红灯提交里是桩）原样留着大写和空格

---

## 2. 是什么（框架层面）

### 2.1 在边界上规范化
一个真实的东西（一个信箱）有很多种写法。修法是选一种规范写法，任何输入在存、比较之前都先变成它。限流的键早就这么做了（学习文档 13），账号没有，所以两边对不上。

### 2.2 卡口，而不是调用点
在每个 service 方法里归一，能用到有人新加一个方法为止。这里有两个每条路径都会经过的地方：实体的 setter（所有写）和仓库的查询（所有读）。Spring Data 的仓库接口可以有 `default` 方法，所以 `findByEmail` 先归一再调一个精确匹配的 `@Query`；再用 ArchUnit 测试保证别处没人直接调那个精确查询。

### 2.3 约束跟着数据走
唯一约束区分大小写。所有写都存一种写法以后，"唯一"就等于"一个信箱一个账号"，不用改表结构。`lower(email)` 的函数索引能在数据库层说同样的话，但 store 没有 schema 管理（`ddl-auto=update`），这次没加。

### 2.4 `Locale.ROOT`
`String#toLowerCase()` 用服务器的语言。土耳其语里 `"I"` 会变成没有点的 `"ı"`，`TIM@X.COM` 就对不上 `tim@x.com`。标识符一律用 `Locale.ROOT` 转小写。

### 2.5 不是你写进去的数据
今天加的规则不会改昨天写进去的行。Hibernate 按字段直接加载，所以老的 `Victim@x.com` 那一行，归一后的查询永远找不到。本机数据先查过（干净）；别的环境按 ADR-31：先检查、重复组人工处理、再更新。

---

## 3. 请求路径

1. 手机发 `/api/user/login {email: "Victim@x.com", …}`。
2. `AccountController#loginUser` → `AccountService#getAccountByIdentifier` → `AccountRepository#findByEmail("Victim@x.com")` → `EmailAddress#normalize` → `victim@x.com` → `findByStoredEmail` 找到账号。限流键是 `account:<id>`，不管输的是哪种写法。
3. `verifyLogin` 走同一个查询，验密码；令牌里是库里存的 `victim@x.com`。
4. 注册、要码、激活、重置密码在各自 `AccountService` 方法开头归一，所以 email 按 `victim@x.com` 存码、验码，bank 也按它开户。

---

## 4. 为什么这样做

### 4.1 方案 A（Christy 定的）
只在 store 归一，两个卡口；不改表结构、不自动迁移（有重复要人定），email 不改（它只看得到 store 传过去的）。

### 4.2 红灯里看到的 🤖
管理员那条比预想的重：`ADMIN_EMAIL` 换个大小写，会给已经有普通账号的信箱另建一个管理员账号。有一条测试第一次红错了地方：假 bank 两次开户都回同一个账号号码，第二次插入撞了银行账号的唯一约束（500），没体现"两个账号"；改成只给抢注的人开户。

### 4.3 审查发现了什么
0 必须修、1 建议修、6 可以不修。没有绕过两个卡口的路径；登录发令牌的账号一定是验过密码的那个；限流键和账号写法现在完全一致。
- **建议修（文档）**：迁移步骤不完整。bank 的 `owner_name` 没有唯一约束，盲目更新会留下同名的两行、让 bank 的查询出错；ADR-31 现在也查 bank、写明被放弃的账号怎么处理、三个库一个一个来、和 Java 一样去掉制表符，最后加一条核对 SQL。这些 SQL 在本机库上用 `BEGIN … ROLLBACK` 跑过。
- **修了**（红 `31ac233` → 绿 `d2b7241`）：Pay Now 按令牌里的邮箱去 bank 查银行账户。拿着迁移前签的令牌，bank 找不到就会新开一个空户。现在用库里的邮箱。另加两条回归测试：管理员任何大小写都能登录；只有 `AccountRepository` 能调精确查询。
- **写明**：用户名长得像别人的邮箱、只差大小写时，现在先找到那个人（根治是用户名不许带 `@`）；两个请求同时注册只建一个账号、第二个是 500；有大小写混杂老数据时不迁移就上线，那些主人会被锁在外面。

---

## 5. 这里的面试题

**结论**：一个东西一种写法：在每条路径都经过的地方归一，而不是在记得的地方。

| 题目 | 在哪 | 这里怎么做的 |
|---|---|---|
| ➕ 规范化 | `EmailAddress#normalize` | trim + `toLowerCase(Locale.ROOT)`；账号和限流键用同一个函数 |
| ➕ 卡口 | `Account#setEmail`、`AccountRepository#findByEmail` | 仓库的 `default` 方法挡在精确 `@Query` 前面，ArchUnit 守着 |
| ➕ 唯一约束和大小写 | `account.email` | 所有写都规范化以后，区分大小写的约束就够了 |
| ➕ locale 的坑 | `EmailAddressTest` | 土耳其语没有点的 i |
| ➕ 数据迁移 | ADR-31 | 检查、重复组人工处理、按库更新、核对 |

场景句：
- "我们的登录按输入原样查邮箱，手机把首字母大写就登不上，`Victim@` 和 `victim@` 还是两个账号。我在每条路径都经过的两个地方归一：实体 setter 和仓库查询；审查又抓到一条还在用令牌里写法去 bank 查户主的路径。"

---

## 6. 追问链

1. **为什么不用 `findByEmailIgnoreCase`？** → 它在查询时比较 `upper(email)`：列上的索引用不上，而且只差大小写的两行照样能插进去。
2. **为什么不加 `lower(email)` 唯一索引？** → 原则上对；store 还没有迁移工具，而所有写都规范化以后，现有约束已经够了。
3. **`@` 前面真的不区分大小写吗？** → RFC 5321 允许区分；主流邮箱都不区分。几乎所有网站都是这个取舍。
4. **老数据怎么办？** → store 和 bank 都查重、人来定、按 store → bank → delivery 更新、核对。不迁移就上线比不上线更糟。
5. **老令牌呢？** → store 通过归一后的查询照样解析；Pay Now 用库里的邮箱；delivery 精确比较，所以这些用户重新登录一次。

---

## 7. 怎么说

- "在边界上规范化，比较规范后的形式。"
- "规则放在每条路径都经过的地方，再测一下没人绕过去。"
- "新规则不会改写老数据。"

---

## 8. 延伸阅读

- RFC 5321 §2.4（`@` 前面那段可以区分大小写）：<https://www.rfc-editor.org/rfc/rfc5321#section-2.4>
- `String#toLowerCase(Locale)` 的 Javadoc（土耳其语的例子）：<https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/lang/String.html#toLowerCase(java.util.Locale)>

---

## 9. 自测

1. 为什么现在那个区分大小写的唯一约束就够了？
<details><summary>答案</summary>
`Account#setEmail` 把每个地址都存成一种写法，同一个信箱的两种写法变成同一个值，约束会拒掉第二个。
</details>

2. 仓库已经归一了，为什么 `AccountService` 还要在五个方法里归一？
<details><summary>答案</summary>
这几个方法要把邮箱传给 email 或 bank。email 按邮箱存码、验码，存的写法和验的写法必须一样。
</details>

3. （真懂才答得上）代码是绿的。为什么在刚迁移完的环境里 Pay Now 还是错的？
<details><summary>答案</summary>
它把登录令牌里的邮箱发给 bank。迁移前签的令牌还是老的大小写，bank 精确匹配、找不到就新开一个空户，store 会把这个号码存下来。现在 Pay Now 用账号库里的邮箱。
</details>

---

## 10. 回放时间

**20 分钟**：读 §1 和 §2（8 分钟）→ `git checkout 8602a4e`，跑 `./gradlew :storeService:test --tests '*EmailCaseIntegrationTest'`，看到第二个账号和 401（5 分钟）→ `git checkout b107644`，跑绿（3 分钟）→ 自测（4 分钟）。
