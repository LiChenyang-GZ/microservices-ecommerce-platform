# 14 · 邮箱被抢注，用找回密码认领

[English](14-claim-by-password-reset.md) | **中文**

> 提交。PR #36：红灯 `9f63cec` → 绿灯 `286ecb2`；审查后红灯 `0552938` → 绿灯 `c5ca529`。
> 怎么发现的：限流的审查（"用别人的邮箱先注册"），最早记在 A11 的失败场景表里。
> 重放时间：**30 分钟**
> 前置：[12 · 激活要邮件里的验证码](12-activation-code.zh-CN.md)（证明邮箱）和 [13 · 公开账号接口的限流](13-rate-limits.zh-CN.md)（谁能多频繁地要码）。

---

## 1. 问题

**从没验证过的账号，照样占着邮箱。** 任何人都能用自己的密码注册 `victim@x.com`。他激活不了，但主人再注册是 "Email already exists!"，找回密码改完密码账号还是没激活，登录说 "Please verify your email first" 又没地方可去。主人永远用不了自己的邮箱。

修之前证明坏事**真的发生了**的测试（红灯）：
- [ClaimByPasswordResetIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClaimByPasswordResetIntegrationTest.java)：重置码对了以后，被抢注的账号还是没验证、没激活，主人的新密码永远登不上；注册也不提示能认领
- [RegisterPage.test.jsx](../../frontend/src/pages/RegisterPage.test.jsx)：没有去找回密码的链接
- 审查推出来的：[ForgotPasswordPage.test.jsx](../../frontend/src/pages/ForgotPasswordPage.test.jsx)，429 把主人留在表单上

---

## 2. 是什么（框架层）

### 2.1 邮箱属于谁？属于证明了它的人
只有有人证明自己能读这个信箱，邮箱才属于某个账号。在那之前，这一行只是谁都能提的"声明"。所以规则是：**任何一种能证明信箱的方式都算数**，证明的人来设密码。

### 2.2 重置码也是证明
注册码和重置码进的是同一个信箱。ADR-25 让它们分开（一码一用途），但哪一个都能证明"我读得到这个信箱"。所以 [AccountService#resetPassword](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) 在重置码通过以后，把从没验证过的账号也标成验证过、激活（`markEmailProved`，和激活共用）。密码是主人刚填的，抢注的人的密码在这一刻就失效了。

### 2.3 预先劫持，以及为什么顺序要紧
这种攻击危险的版本不是"主人被挡住"，而是"主人激活了攻击者的账号"：攻击者先用自己知道的密码注册，主人后来证明了邮箱，攻击者就能登录一个验证过的账号。这里激活这条路之所以不会出事，只是因为验证页只有"注册成功"才到得了，也就是填密码的那个人。所以 `activateAccount` 不设密码，认领走找回密码（会设密码），注释也写明不要给没验证的账号别的路去验证页。

### 2.4 为什么不"注册时覆盖没验证的账号"？
看起来更简单，但有竞态。主人注册、收到码。他输码之前，攻击者用自己的密码再注册一次，而且不要码。主人输入手里的码，激活的账号密码却是攻击者的。要堵住就得把码和某一次注册绑在一起，状态更多。

### 2.5 每一条路都要扛得住"把额度用完"的攻击者
审查发现：攻击者用完这个邮箱 3 封信的额度后，找回密码页会把主人留在表单上（429），而信箱里其实已经有码了。现在 429 也跳到重置页并带上提示。更深的问题留着：攻击者还能把唯一活着的码猜死（猜错 5 次作废，不管是谁猜的），主人的码就全是死的。后续是按"邮箱 + 客户端地址"数猜错次数。

---

## 3. 请求路径

**抢注的人**
`POST /api/store/user {username, email: victim@…, password: 他的}` → 200，一个没激活的账号。

**主人**
1. 注册 → `AccountService#createAccount` 找到没验证的那一行 → `EmailNotVerifiedException` → **400** `emailNotVerified: true`，"If it is yours, reset the password to claim it." → 页面给出 `/forgot-password` 的链接。
2. `forgot-password` → 重置码进主人的信箱（429 也照样去重置页）。
3. `reset-password {email, code, newPassword}` → email 校验 PASSWORD_RESET 码 → 换掉密码 → `markEmailProved`：验证过、激活 → **200**。
4. 用新密码登录 → 200。抢注的人的密码 → 401。

运行中的系统上的证据（compose `-p authcheck`，经 nginx）：就是上面这几步，码从 Mailpit 读；抢注的人的密码用邮箱、用户名都登不上；`smoke-order.ps1` 通过。

---

## 4. 为什么这么做

### 4.1 找回密码认领（Christy 定的）
关口 0 时选的，没选覆盖、过期清理、待验证表：不改表，主人马上就能用，证明信箱的人设密码。代价：用户名还是抢注的人起的。

### 4.2 说出"注册了但没验证" 🤖
它会告诉陌生人"这个邮箱注册了、没验证"。登录本来就会说，而主人需要知道该怎么办；提示里直接给了解决办法。

### 4.3 审查找到了什么
没有必须修的。两条建议修的都和**把额度用完的攻击者**有关：找回密码页把主人卡住（修了），码能被猜死（写明，是后续）。审查还纠正了文档：初稿说"以后加'去验证'按钮也不会出事"，和 §2.3 正好相反。

---

## 5. 这里的面试点

**结论**：邮箱属于证明了信箱的人，密码由那个人设。

| 话题 | 在哪 | 这里的判断 |
|---|---|---|
| ➕ 预先劫持（pre-account hijacking） | `AccountService#activateAccount`、`#resetPassword` | 认领时设主人的密码；验证页没有别的入口 |
| ➕ 什么能证明一个邮箱 | `markEmailProved` | 两种码都能证明信箱 |
| ➕ "重新注册就覆盖"的竞态 | ADR-27 没选的选项 | 码没有和某一次注册绑定 |
| ➕ 用户枚举 vs 有用的提示 | `EmailNotVerifiedException` | 登录本来就会说；告诉用户怎么解决 |
| ➕ 丢失更新：读、等、存整行 | `activateAccount` | 窗口很窄，写在注释里 |
| ➕ 借限流和猜码做拒绝服务 | `ForgotPasswordPage`、R29 | 别把用户卡住；根本修法是按邮箱数 |

场景锚点：
- "任何人都能用自己的密码注册别人的邮箱，然后一直占着。我让找回密码来认领账号：重置码证明了信箱，由主人设密码，抢注的人的密码就失效了。"
- "看起来最简单的修法是注册时覆盖没验证的账号。它有竞态：攻击者在主人注册和激活之间再注册一次，主人激活的就是攻击者的密码。"

---

## 6. 追问链

1. **为什么重置码能证明邮箱？** → 它发到了这个信箱。读到它和读到注册码是同一种证明。
2. **抢注的人能自己认领吗？** → 他得拿到重置码，而码进的是主人的信箱。
3. **以后给登录页加个"去验证"按钮会怎样？** → 主人就可能激活一个抢注的人设过密码的账号。没验证的用户必须走找回密码。
4. **"注册了但没验证"不算枚举泄露吗？** → 登录本来就会说；这条提示是主人能恢复的前提。
5. **攻击者还能挡住主人吗？** → 能：用完发信额度，再把活着的码猜死。按"邮箱 + 客户端地址"数猜错次数，别人的错猜就杀不了你的码。

---

## 7. 怎么说

- "谁证明了信箱，邮箱就是谁的，密码也由他设。"
- "没验证的账号只是一个声明，不是主人。"
- "每一条找回的路，都要在有人花你额度的时候照样走得通。"

---

## 8. 延伸阅读

- Sudhodanan & Paverd，*Pre-hijacked accounts: An Empirical Study of Security Failures in User Account Creation on the Web*（USENIX Security 2022）：<https://www.usenix.org/conference/usenixsecurity22/presentation/sudhodanan>
- OWASP Forgot Password cheat sheet：<https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>
- OWASP Authentication cheat sheet（账号枚举）：<https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>

---

## 9. 自检

1. 有人抢注了你的邮箱。说一遍你怎么拿回账号，以及为什么抢注的人进不来了。
<details><summary>答案</summary>
注册会说这个邮箱注册了但没验证，并给出找回密码的链接。重置码进你的信箱；`reset-password` 接受它，把密码换成你的，并把账号标成验证过、激活。抢注的人的密码对不上了，他也从没看到过码。
</details>

2. 为什么 `activate` 不设密码？因此什么东西绝对不能加？
<details><summary>答案</summary>
账号用的是注册时填的密码，这之所以安全，只因为到验证页的就是填密码的那个人。给没验证的账号再开一条去验证页的路，主人就可能激活抢注的人的密码。
</details>

3. （真懂才答得上）"注册时覆盖没验证的账号"因为竞态没选。写出具体的先后顺序，并说要加什么状态才能堵住。
<details><summary>答案</summary>
主人注册（密码 P1），收到码 C。抢注的人用密码 P2 再注册一次，不要码，所以 C 还活着。主人输入 C → 账号以 P2 激活。要堵住，码得属于某一次注册（和码一起存注册 id 或密码哈希的版本，激活时核对），这样后来的注册会让 C 失效。
</details>

---

## 10. 重放时间

**30 分钟**：读 §1、§2（10 分钟）→ `git checkout 9f63cec`，跑 `./gradlew :storeService:test --tests '*ClaimByPasswordResetIntegrationTest'`，看重置后账号还是没激活（5 分钟）→ `git checkout 286ecb2`，跑它，绿（5 分钟）→ 自检（10 分钟）。
