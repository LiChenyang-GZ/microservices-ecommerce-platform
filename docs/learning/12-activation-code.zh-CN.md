# 12 · 激活要邮件里的验证码（A11）

[English](12-activation-code.md) | **中文**

> 提交。PR #31：红灯 `4d6cdef` → 绿灯 `0cb47b3`、`f762b70`；审查后红灯 `ba883eb` → 绿灯 `44de260`。
> 怎么发现的：写鉴权 PR ② 的失败场景表时（场景 A11），当时记成"这个 PR 不修"。
> 重放时间：**40 分钟**
> **后来改了**（学习文档 17、ADR-30）：码改成 10 位，猜错不再作废；下面"猜错 5 次作废"是当时的做法。现在猜的次数由 store 按"IP + 邮箱 5 次"限。
> 前置：[11 · 登录、订单归属和管理员角色](11-login-ownership-admin-role.zh-CN.md)（谁能动哪个订单；这一篇讲的是一开始怎么证明你是谁）。

---

## 1. 问题

**任何人都能激活用别人邮箱注册的账号。** 验证页先拿码去调 email 公开的 `verify-code`，再只带邮箱调 store 的 `activate`。store 看不到第一步，所以 `POST /api/store/user/activate {"email":"victim@…"}` 能激活任何账号。ADR-24 之后每个订单都属于某个账号，可账号上的邮箱什么都证明不了。

码本身也弱：这个地址的任何一个 SENT 码都算对，不看类型（找回密码的码能激活账号）；猜错不限次数；重发不作废旧码；码用 `java.util.Random` 生成。而且本地根本收不到信：没配 Gmail 就发信失败，所以脚本只带邮箱调 `activate`——恰好就是这次要堵的洞。

修之前证明坏事**真的发生了**的测试（红灯）：
- [ActivationNeedsCodeIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ActivationNeedsCodeIntegrationTest.java)：不带码、空码、错码 → 200 且账号被激活；email 回 401、断开连接、一直不回 → 照样激活；带对的码时 store 根本没去问 email
- [VerificationCodeRulesTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerificationCodeRulesTest.java)：重置码能通过注册校验，反过来也行；猜错 5 次后对的码还能过；重发后旧码还能过
- [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java)：不带 / 带错服务凭据调 `verify-code` → 200
- [EmailVerificationPage.test.jsx](../../frontend/src/pages/EmailVerificationPage.test.jsx)：页面只把邮箱传给 `activate`，还在直接调 email
- 审查推出来的：[VerifyEndpointErrorsTest.java](../../backend/emailService/src/test/java/com/comp5348/email/VerifyEndpointErrorsTest.java)（email 自己出错回 400"码不对"）、email 回 404 在 store 变成"码不对"、重发时发信失败把用户手里的码弄没了

---

## 2. 是什么（框架层）

### 2.1 不能让客户端来报告"检查通过了"
老流程是"浏览器问 email，浏览器告诉 store"。浏览器说的话都能跳过、能伪造；只有 store 自己查到的才算数。修法把检查挪到服务之间：浏览器把**码**交给 store，store 带着服务凭据自己去问 email（[AccountService#activateAccount](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)）。找回密码本来就是这个做法。

另一个办法是 email 签一个一次性凭证，浏览器转交给 store。能用（OAuth 的授权码、JWT 就是这个思路），但要多一把共享密钥、一个过期时间、一张防重放的"已用凭证"表，只为了传一个 store 直接问就能知道的事实。

### 2.2 6 位验证码靠什么才安全
6 位数只有一百万种。它安全全靠周围的规则（[EmailService#checkCode](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java)）：
- **次数少**：错 5 次作废，猜的人只有百万分之五的机会，而不是无限次。
- **只有一个活码**：如果每次重发旧码都还活着，重发十次就有十个靶子。新码会作废旧码。
- **一码一用途**：注册的码不能拿去重置密码。
- **寿命短**：5 分钟。
- **猜不出来**：`java.util.Random` 是线性同余生成器，内部只有 48 位状态，看到几个输出就能算出后面的。`SecureRandom` 从操作系统的熵源取随机数。

### 2.3 计数器要加锁，锁要有事务
两个请求同时"读 failed_attempts、加一、写回"会丢更新：都读到 3，都写回 4。同样的竞态也会让一个对的码被用两次。[EmailOutboxRepository#lockCodes](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java) 用 `@Lock(PESSIMISTIC_WRITE)`（`SELECT … FOR UPDATE`）读活码：第二个请求等第一个提交以后才继续，看到的是它改完的值。

两个细节：
- **事务要加在经过 Spring 代理调用的方法上。** `checkCode` 是 private、没注解；`verifyCode`（public，controller 调它）有 `@Transactional`，所以锁一直持有到整个校验结束。`@Transactional` 加在 private 方法上、或者加在同一个类内部调用的方法上，都不生效：调用根本没经过代理。
- **按固定顺序加锁。** 一个事务先锁 A 再锁 B，另一个先锁 B 再锁 A，就会死锁，Postgres 杀掉其中一个。`order by o.id` 让所有事务按同一个顺序加锁。

审查问"锁真的生效吗？"，两条并发测试回答了它：去掉 `@Lock` 跑 3 次，3 次都红。

### 2.4 按"是谁的错"给错误分类
| 原因 | 回什么 | 为什么 |
|---|---|---|
| 码错或过期 | 400 | 用户自己能改 |
| email 不认 store 的凭据 | 503 + ALERT | 要运维来修，用户只能等 |
| email 挂了、慢、404、5xx | 503 + 日志 | 同上 |
| email 校验时自己出错 | email 回 500 → store 回 503 | 同上 |

审查之前，email 自己抛异常也回 400，store 把所有 4xx 都当成"码不对"：数据库坏了看起来像用户手误，用户反复重试，没人收到告警。规则是：**只有**真正表示"用户的错"的那个状态码才映射成用户错误，其他一律"稍后再试"。

### 2.5 新的有了，才毁掉旧的
第一版先作废旧码，再存新码、发信。发信失败时，用户手里能用的码已经没了。现在 [EmailService#markSent](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) 在信发出去**以后**才作废别的码（`expireOtherLiveCodes`，排除新的那一行）。和"先写新文件，再 rename 覆盖旧文件"是一个道理。

### 2.6 本地用的假 SMTP 服务器
Mailpit 在 1025 收 SMTP，在 8025 用网页和 REST API 展示每一封信。compose 和 kind 通过 `MAIL_HOST` / `MAIL_PORT` / `MAIL_SMTP_AUTH` / `MAIL_SMTP_STARTTLS` 让 email 发给它；脚本用 `GET /api/v1/search?query=to:"…"` 和 `GET /api/v1/message/{id}` 读码（[mailpit.ps1](../../backend/docker/mailpit.ps1)）。于是脚本测的是真的注册流程，而不是一个后门。它只给本机用：API 没有登录，放进共享集群就等于把所有码交给任何人。

---

## 3. 请求路径

**攻击者激活别人的账号**
1. `POST /api/store/user/activate {"email":"victim@…"}` → `AccountController#activateAccount` → `AccountService#activateAccount`：没码 → `INVALID_CODE` → **400**。根本不问 email。
2. 带一个猜的码：store 去问 email；`EmailService#checkCode` 锁住活的 REGISTRATION 码，对不上，记一次错 → email 400 → store **400**。第 5 次猜错，码变 EXPIRED。
3. 直接打 email：经入口调 `POST /api/email/verify-code` → 入口自己的 **404**；在网络里面 → `ServiceTokenFilter` **401**。

**真用户激活**
1. 注册页：store 建账号，email 发码（本地发到 Mailpit）。
2. 验证页 → store `activate {email, code}` → store 带凭据问 email → email 锁行、标 VERIFIED、提交 → 200 → store 设成已激活 → **200** → 跳登录页。

**email 挂了**
store 的调用 5 秒内失败 → `EMAIL_UNAVAILABLE` → **503** "稍后再试"；账号还是未激活，码也没被用掉。

运行中的证据（compose `-p authcheck`、kind）：compose 上冒烟和场景 01 经 Mailpit 通过，kind 上冒烟和场景 06 通过；不带码 / 错码 → 400；直连 `verify-code` → 401，经入口 → 404；在浏览器里注册，页面只调了 store 的 `activate`；两个入口 `check-entry.ps1` 都是 54/54。

---

## 4. 为什么这么做

### 4.1 store 去问 email，而不是签凭证（Christy 定的）
AI 推荐、Christy 在关口 0 选的。复用找回密码的做法，不加密钥也不加表。代价：激活要靠 email 活着——发码本来就要靠它。

### 4.2 用 Mailpit，而不是让脚本改库（Christy 定的）
更省事的办法是脚本用 SQL 设 `active = true`。Christy 选了 Mailpit：这样脚本覆盖的是真的激活流程，本地在浏览器里注册也第一次能用了。代价：多一个容器、四个新配置，配过 Gmail 的人默认值变了（启动时的 WARN）。

### 4.3 错 5 次作废 🤖
不限次数时，猜的人在一百万个值里无限次试。限了以后，他只能把码耗掉：每秒 5 个错码，受害人就永远激活 / 重置不了。这是捣乱，不是接管；真正的解法是限流（按地址和 IP），记成了下一步。

### 4.4 审查找到了什么
没有必须修的。有用的发现都在边上：email 自己出错和 404 被说成"码不对"（§2.4）；先作废再发信，发信失败时用户的码丢了（§2.5）；锁没有测试（§2.3）；Mailpit 放在 `k8s/base`，以后上云的 overlay 会把它带上；`.env` 里只有 Gmail 账号密码的人，现在会悄悄改发到 Mailpit。**审查问的是表里没问的：修复周围的东西出错时会怎样。**

---

## 5. 这里的面试点

**结论**：验证码有多强，取决于它周围的规则，而且必须由服务端自己检查。

| 点 | 在哪 | 这里的做法 |
|---|---|---|
| ➕ 信任边界：客户端不能为检查作保 | [AccountService#activateAccount](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) | store 自己问 email；浏览器只传码 |
| ➕ 一次性验证码的设计：次数、寿命、单一活码、用途 | [EmailService#checkCode](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java) | 5 次、5 分钟、每类一个、`SecureRandom` |
| ➕ 丢失更新、`SELECT … FOR UPDATE`、加锁顺序 | [EmailOutboxRepository#lockCodes](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java) | 按 id 顺序加悲观锁；并发测试证明 |
| ➕ Spring `@Transactional` 和自调用 | `EmailService#verifyCode` → `checkCode` | 注解加在代理看得到的 public 方法上 |
| ➕ 4xx 和 5xx、错误分类 | `AccountService#activateAccount`、`EmailController#verifyCode` | 只有 400 算用户的错 |
| ➕ 副作用的顺序 | `EmailService#markSent` | 新码发出去后再作废旧码 |
| 📒 用邮件收集器让开发环境接近生产 | [mailpit.yaml](../../k8s/base/mailpit.yaml)、[compose.yml](../../backend/compose.yml) | 脚本走真流程；只给本机用 |

场景锚点：
- "激活信任浏览器说'邮箱验证过了'。我把检查挪到了服务端：store 收码，自己去问 email 服务。"
- "6 位码只有一百万种。它只有在限次数、只留一个活码、寿命短的情况下才安全；三个我都加了，还加了行锁，让并发的猜测绕不过计数器。"
- "审查发现 email 服务的数据库错误被当成'码不对'告诉用户。我改成只有真正的 400 才算用户的错，其他都回 503。"

---

## 6. 追问链

1. **为什么不让前端先调 email 再调 store？** → 第二个请求可以单独发。浏览器报告的只是声明，不是事实。
2. **5 次上限让攻击者能把用户锁在外面，不是更糟吗？** → 不限的话他能拿走账号。限了以后他只能捣乱；按地址和 IP 限流能挡掉大部分。
3. **怎么知道锁生效了？** → 两个线程拿同一个对的码：恰好一个成功。5 个并发错码：码照样作废。去掉锁两条都红。
4. **加锁的查询是从 private 方法里调的，它在事务里吗？** → 在，因为调它的 public `verifyCode` 有 `@Transactional`，而且是经过代理调用的。只加在 private 方法上的话注解不起作用。
5. **store 回了 503，可码已经没了，怎么回事？** → email 已经提交了 VERIFIED，但回复晚于 store 的 5 秒超时到达。用户重发一个码。要做到恰好一次，得给校验加幂等键；为了少一次重发不值得。
6. **为什么生产上不用 Mailpit？** → 它的 API 没有登录：集群里谁都能读到所有重置码。它是本机工具。

---

## 7. 怎么说

- "浏览器只负责带码，检查归服务端。"
- "短的码要配短的寿命、少的次数，而且同一时间只能有一个活着。"
- "只有用户的错才应该看起来像用户的错。"

---

## 8. 延伸阅读

- OWASP 的 Forgot Password 和 Authentication 速查表（码、次数、回复）：<https://cheatsheetseries.owasp.org/cheatsheets/Forgot_Password_Cheat_Sheet.html>、<https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>
- NIST SP 800-63B，认证器的限速：<https://pages.nist.gov/800-63-3/sp800-63b.html#throttle>
- Java `SecureRandom`：<https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/SecureRandom.html>
- PostgreSQL 行锁和死锁：<https://www.postgresql.org/docs/16/explicit-locking.html>
- Spring 事务和代理（自调用）：<https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html>
- Mailpit：<https://mailpit.axllent.org/docs/>

---

## 9. 自检

1. 把 `EmailService#verifyCode` 上的 `@Transactional` 去掉，会怎样？
<details><summary>答案</summary>
`lockCodes` 用了悲观锁，必须在事务里。2026-11-23 实际试过：每次校验都抛 `InvalidDataAccessApiUsageException: Query requires transaction be in progress`；`EmailController` 回 500，store 回 503，谁都激活不了。如果连锁也一起去掉，它会"能用"，但会丢计数，一个码能用两次。
</details>

2. 账号不存在时，store 为什么不去问 email？
<details><summary>答案</summary>
去问的话，会给一个真人的邮箱记一次错、或者用掉他一个对的码，而这个账号根本不存在。反正结果都是回 400。
</details>

3. （真懂才答得出）第一版先作废旧码再存新码。这造成了哪两个问题？"发出去以后再作废"怎么把两个都解决了？
<details><summary>答案</summary>
(a) 发信失败时旧码已经死了，用户一个码都没有。(b) 作废和插入是两条分开的语句，顺序是"先毁再建"。改成在 `markSent` 里、信发出去以后、排除新行 id 再作废，旧码只在替代品真的到了用户手里时才死。（两个重发在同一刻发生时仍可能互相作废，用户再点一次重发。）
</details>

---

## 10. 重放时间

**40 分钟**：读 §1、§2（15 分钟）→ `git checkout 4d6cdef`，跑 `./gradlew :storeService:test --tests '*ActivationNeedsCodeIntegrationTest'`，看不带码也能激活（5 分钟）→ `git checkout 44de260`，跑它和 `:emailService:test --tests '*VerificationCodeRulesTest'`，全绿（5 分钟）→ 去掉 `lockCodes` 上的 `@Lock`，跑两条并发测试看它们红，再改回去（5 分钟）→ 自检（10 分钟）。
