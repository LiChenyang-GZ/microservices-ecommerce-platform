# 17 · 猜错也不作废的码

[English](17-codes-survive-wrong-guesses.md) | **中文**

> 提交（分支 `fix/code-guess-per-ip-email`）：红 `e59f8f8` → 绿 `ac371f5`；审查后红 `329ee9b` → 绿 `2c3a050`。
> 怎么发现的：抢注审查（Obsidian 59 号 🟠2），当时写成已知限制（限流 R29）。
> 回放时间：**25 分钟**
> 先读：[12 · 激活验证码](12-activation-code.zh-CN.md)、[13 · 限流](13-rate-limits.zh-CN.md)、[14 · 认领被抢注的邮箱](14-claim-by-password-reset.zh-CN.md)。

---

## 1. 问题

**知道你邮箱的人，能让你永远用不上验证码。** 码是 6 位数字，这个邮箱猜错 5 次就作废，不管是谁猜的。一个 IP 每 15 分钟：替你要 3 次码（用完你的额度），再猜错 5 次。你信箱里的码全是死的，自己要码是 429。激活、找回密码、认领被抢注的邮箱（学习文档 14）都要码，三条路全堵。

修之前**真的发生**的错误结果（红灯）：
- [VerificationCodeRulesTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerificationCodeRulesTest.java)：猜错 20 次后对的码失败（两种码都是）；码是 6 位；复制带空格的码算错
- [AuthRateLimitIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/AuthRateLimitIntegrationTest.java)：同一 IP 对同一邮箱第 6 次照样问 email（400，不是 429）
- `EmailVerificationPage.test.jsx`、`ResetPasswordPage.test.jsx`：页面只收 6 位

---

## 2. 是什么（框架层面）

### 2.1 一个旋钮，两头
码短，就必须限制总共能猜几次，不然会被猜中；可总数少，正好让陌生人能把它用完。改成按"邮箱 + IP"记，保护了主人，却让有很多地址的攻击者多了很多次机会。两头都要，只能让每一次猜**更不值钱**：**码变长**。

### 2.2 先算一算
每天能猜的次数 ÷ 码的种数。以前：288 个码 × 5 / 10⁶ ≈ 每天 0.14%。6 位 + 按"邮箱 + IP"、1000 个地址：288 × 5000 / 10⁶，超过 1。10 位：≈ 0.014%。就是写这张表，才发现"显然的修法"是错的。

### 2.3 锁定 vs 限速
失败 N 次就锁**资源**（码），等于把"拒绝服务"交给任何能故意失败的人。限**调用方**（IP + 邮箱）的速度，只拖慢攻击者，不碰主人。和登录一样：按"IP + 账号"数，从不"锁账号"。

### 2.4 在已经认识调用方的地方数
store 有客户端地址、有 Redis 计数器；email 只看得到 store。在 store 数，email 的接口不用改，两个服务也不用分先后上线。

### 2.5 fail-open 要有兜底
Redis 挂了限流就放行（学习文档 13）。以前兜底的是 email 数据库里"猜 5 次作废"；现在兜底的是码的长度：每秒 1000 次猜一整天，约 0.86%。

---

## 3. 请求路径

1. 有人从 IP A 发 `/api/user/reset-password {email: 你, code: …}`。
2. store `AccountController#resetPassword` → `AuthRateLimits#resetPassword(ip, email)`：先数 IP（15 分钟 20 次），再数 IP + 邮箱（15 分钟 5 次）。A 对你的第 6 次 → 429，不问 email。
3. 放行 → `AccountService#resetPassword` → email `verify-password-reset` → `EmailService#checkCode`：去空格，锁行后和活码比。错 → false，码照样活着。
4. 你从 IP B 拿信箱里最新的码 → 你这一对的计数是新的 → email 说对 → 设好密码（被抢注的账号也认领了）。

---

## 4. 为什么这样做

### 4.1 10 位数字（Christy 定的）
推荐的是 8 位、32 个字符的字母表（约 10¹²）。Christy 选了 10 位数字：手机弹数字键盘，页面几乎不用改；就算对着 1000 个地址，也比以前好 100 倍。

### 4.2 红灯里看到的 🤖
store 有两条我以为本来就绿（另一个 IP 还能用码；一个 IP 还能试别的邮箱），其实红在中间那步：第 6 次是 400，不是 429。R11、R12（每 IP 20 次）要分给 4 个账号，不然会先撞上新的 5 次。从 Mailpit 读码的脚本匹配的是 `\d{6}`，10 位码会只取前 6 位。我还去掉了 `maxLength={6}`，以为浏览器会截断粘贴的码；审查发现 `Input` 从来没把它传到页面上，去不去都一样。

### 4.3 审查发现了什么
0 条必须修、1 条建议修、5 条可以修。"IP + 邮箱"的键绕不开：计数对邮箱的归一化比数据库匹配更粗，能碰到某个码的输入只会落在一个键上；email 的两个校验接口也只有 store 调。
- **建议修（写明，不改代码）**：猜码的上限现在全靠"按地址区分客户端"。IPv6（每个请求换个地址）或云上负载均衡后面（所有人看起来都是负载均衡）就没了，或者变成全站"每邮箱 5 次"。所以按 /64 归一、核对客户端地址规则成了上云的前提。
- **修了**（红 `329ee9b` → 绿 `2c3a050`）：过渡期页面收 6 或 10 位，前端可以先上；`Input` 透传 `inputMode` / `autoComplete`，手机弹数字键盘（选数字的一半理由）；补了两条"IP + 邮箱"键的回归测试（activate 不分大小写；被 IP 挡的请求不留这一对的键）；一条测试改了名，名字说清楚测的是什么；`lockCodes` 的注释不再提计数。
- **写明**：主人自己输错、503 也算次数（等 15 分钟）；上线前打开的页面要刷新；ADR-27、学习文档 12 / 13 指向这里。

---

## 5. 这里的面试考点

**结论**：别锁主人要用的东西；拖慢调用方，让每次猜都更不值钱。

| 考点 | 在哪 | 这里的选择 |
|---|---|---|
| ➕ 暴力破解 vs 拒绝服务 | `EmailService#checkCode` | 谁都能触发的锁定就是 DoS；改成限调用方 |
| ➕ 熵的算术 | ADR-30 的表 | 每个方案算：每天猜的次数 ÷ 码的种数 |
| ➕ 限流的键 | `AuthRateLimits#codeCheck` | 先 IP，再 IP + 归一化邮箱；被 IP 挡的不计入这一对 |
| ➕ fail-open 的兜底 | ADR-26、ADR-30 | 限流挂了，剩下的就是码的长度 |
| ➕ 格式在半路上变 | `checkCode`、两个页面 | 旧的 6 位码照样比对；页面要 10 位，用户点重发 |

场景锚点：
- "我们的验证码是 6 位，谁猜错 5 次都作废，所以一个 IP 就能让别人永远重置不了密码。只按 IP 数的话，一千个 IP 一天内就能暴力猜中；我把账算了一遍，最后是把码改成 10 位，猜错就不用作废了，再在 store 加一条按 IP + 邮箱的限流。"

---

## 6. 追问链

1. **为什么不直接按"邮箱 + IP"数？** → 6 位、不设总数，很多地址一天内就能猜中。
2. **为什么不留一个每码总数，比如 20？** → 那 4 个地址又能猜死它，暴力猜还容易 4 倍。
3. **为什么在 store 数，不在 email？** → store 知道调用方地址、已经有 Redis；email 的接口不变。
4. **Redis 挂了呢？** → 没有限流；10¹⁰ 种码，每秒 1000 次猜一整天约 0.86%。
5. **攻击者还能堵住主人吗？** → 能用完 15 分钟 3 封的额度，但每封码都发给主人、一直活着；或者和主人同一个 NAT，让他等 15 分钟。

---

## 7. 怎么说

- "谁都能触发的锁定，就是一个拒绝服务功能。"
- "猜的次数只能少，那就让每次猜更不值钱。"
- "限调用方，不锁资源。"

---

## 8. 延伸阅读

- OWASP，*Blocking Brute Force Attacks*（锁定 vs 限速）：<https://owasp.org/www-community/controls/Blocking_Brute_Force_Attacks>
- NIST SP 800-63B §5.1.3（带外验证器）和 §5.2.2（限速）：<https://pages.nist.gov/800-63-3/sp800-63b.html>

---

## 9. 自测

1. 以前为什么猜错 5 次就作废，代价是什么？
<details><summary>答案</summary>
6 位只有 10⁶ 种，一个码只能让人猜很少几次。可这个上限不管是谁猜的，所以一个 IP 每 15 分钟就能把主人的活码猜死。
</details>

2. "IP + 邮箱"的限制为什么先查 IP？
<details><summary>答案</summary>
被 IP 挡住的请求不该用掉这一对的 5 次（和发码的限制一样，R9）；而且每 IP 20 次还能挡住一个地址去试很多邮箱。
</details>

3. （真懂才答得出）攻击者用完了你 15 分钟 3 封的额度。为什么现在你还能重置密码，以前不能？
<details><summary>答案</summary>
每次发码都发到你的信箱，并顶掉旧码，最新那封是活的。以前攻击者接着从一个 IP 把它猜死；现在猜错不作废，他的猜测记在"他的 IP + 你的邮箱"上，不是你的，所以你用信箱里最新那封就行。
</details>

---

## 10. 回放时间

**25 分钟**：读 §1、§2（10 分钟）→ `git checkout e59f8f8`，跑 `./gradlew :emailService:test --tests '*VerificationCodeRulesTest'`，看码被猜死（5 分钟）→ `git checkout ac371f5`，跑它和 `:storeService:test --tests '*AuthRateLimitIntegrationTest'`，全绿（5 分钟）→ 自测（5 分钟）。
