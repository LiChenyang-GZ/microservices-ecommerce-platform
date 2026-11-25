# ADR-25 · 激活要邮件里的验证码，由 store 去问 email

[English](25-activation-needs-the-emailed-code.md) | **中文**

**状态：** 已采纳，由 26 补充（验证码改向 store 要；限流），由 27 补充（找回密码能认领没激活的账号），由 30 补充（码改 10 位，猜错不再作废）。修订 ADR-23（email 的 `verify-code` 不再公开）和 ADR-24（激活现在能证明邮箱）。

## 背景
ADR-24 之后，store 要登录、查订单归属，但"你是谁"的起点还是一个没证明过的邮箱。`POST /api/user/activate` 只收 `{email}`。验证页先拿码去调 email 公开的 `verify-code`，再告诉 store"激活这个地址"；store 看不到第一步有没有发生。所以任何人都能用别人的邮箱注册，再发一个请求就激活（场景 A11）。

码本身也有问题：
- email 只要找到这个地址的一个 SENT 码就算对，不看类型：找回密码的码能激活账号，注册码能重置密码。
- 猜错不限次数。重发不作废旧码，几个码可以同时有效。
- 码用 `java.util.Random` 生成。
- 本地根本收不到信：compose 和 kind 只有 Gmail 的配置，没配就发信失败，码停在 FAILED，永远校验不过。脚本绕开了这个问题：直接只带邮箱调 `activate`。

## 决定

### store 去问 email
- `activate` 收 `{email, code}`。store 带着 email 的服务凭据（ADR-23）把它们发给 email 的 `verify-code`，和 `reset-password` 校验重置码是同一个做法。email 说对了，store 才把账号设成已激活、已验证（注册时 bank 没开成户的，这时补开）。
- 没选的做法：email 签一个一次性凭证，浏览器转交给 store。要多一把共享密钥、一张防重放的"已用凭证"表、一个过期时间，只是为了传一个 store 直接问 email 就能知道的事实。
- `activate` 不加 `@Transactional`：store 等 email 最多 5 秒，这段时间不开着数据库事务。

### 怎么回
| 情况 | store 回 |
|---|---|
| 没带码、码错或过期（email 回 400）、邮箱没注册 | **400** "Verification code is invalid or expired"，三种一样。没注册的邮箱不去问 email，不会白白耗掉谁的码 |
| 已经激活过 | **200**，不去问 email：双击、刷新不耗码 |
| email 说码不对，但这时账号已经激活了（两个标签页、双击） | **200** "已激活"，不说"码不对" |
| email 回 401 | **503** 并打 `ALERT email_auth_failed`（store 的凭据配错了） |
| email 挂了、5 秒不回、回别的 4xx（404：`email.service.url` 指错了）或 5xx | **503**，打日志。不是用户的错 |

email 只在码错或过期时回 400。它自己校验时出的错（库断了、死锁、提交失败）回 **500**，不带异常信息。

### `verify-code` 只给 store
email 的 `ServiceTokenFilter` 允许名单里只剩 `send-verification` 是公开的。nginx 和 gateway 不再转发 `verify-code`；`check-entry.ps1` 检查入口自己回 404。

### 码的规则
- **各管各的**：注册只认 REGISTRATION 的码，重置只认 PASSWORD_RESET 的码。
- **错 5 次作废。** 每猜错一次，这个地址同类型的活码都记一次；到 5 次就变 EXPIRED，之后对的码也不行。一个 6 位码从"无限次猜"变成"一百万里猜 5 次"。
- **同一时间只有一个活码。** 新码发出去**以后**，同类型的旧码作废：发信失败的话，用户手里的码照常能用。
- 码用 **`SecureRandom`** 生成。
- **校验时锁行。** 在校验的事务里用 `SELECT … FOR UPDATE` 按 id 顺序读出活码。两个请求拿同一个对的码：只有一个成功。5 个错码同时到：计数一个不丢。按 id 排序，两个事务加锁的顺序一样，不会互相死锁。

### 本地邮件发到 Mailpit
- compose 和 kind 把邮件发到 **Mailpit**：一个假的 SMTP 服务器，带网页和 API（compose 上是 http://localhost:8025；kind 上 `kubectl port-forward svc/mailpit 8025:8025`）。没有它，本地注册从来就走不通。
- 脚本照验证页的做法：让 email 发码，从 Mailpit 的 API 读出来（`backend/docker/mailpit.ps1`），带着它调 `activate`。冒烟和场景脚本因此走的是真的激活流程。
- SMTP 的地址、端口、认证、STARTTLS 来自 `MAIL_HOST`、`MAIL_PORT`、`MAIL_SMTP_AUTH`、`MAIL_SMTP_STARTTLS`。compose 默认指向 Mailpit；从 IDE 跑时 email 默认还是 Gmail。
- email 启动时打一行 `Mail goes to host:port`；配了 `MAIL_USERNAME` 却发往 Mailpit 时打 WARN。JavaMail 的连接、读、写各 5 秒超时（它默认一直等）。

## 代价与影响
- **还没有限流。** `send-verification`、`activate`、`reset-password` 都公开、不限速。知道某个邮箱的人能让 email 给它发一堆码，也能每秒发 5 个错码，让这个地址的注册或找回密码一直失败。这个决定之前，同一个人能直接把码猜出来；现在只能捣乱。下一步是按地址和 IP 限速（ADR-26 做了）。
- **码可能用掉了却没激活。** email 把码标成 VERIFIED 之后 store 存库失败，或者 email 的"对"晚于 store 的 5 秒超时到达（store 已经回了 503），码就没了，用户要重发。
- **没激活的账号占着邮箱。** 别人先用你的地址注册，他激活不了，但你注册时会看到"Email already exists"，直到那一行被处理（ADR-27 之后，找回密码就能认领它）。`AdminBootstrap` 仍然不提升已有账号，也是这个原因。
- **Mailpit 只给本机用。** 它的 API 没有登录；放在真集群里，任何 pod 都能读到所有注册码和重置码，真用户也收不到任何信。部署到真环境前，去掉 `k8s/base/mailpit.yaml`，把 email 的 `MAIL_*` 指向真的 SMTP。
- **升级后 compose 的邮件去向变了。** `backend/.env` 里只有 `MAIL_USERNAME` / `MAIL_PASSWORD`（以前的 Gmail 配法）的，现在发到 Mailpit；启动时的 WARN 会提示。要继续发真邮件，设好四个 `MAIL_*`，这时脚本就读不到码了。
- **两个标签页同一刻重发**，可能互相把对方的码作废，用户再点一次重发。页面有 60 秒倒计时，很少碰到。
- store 开着 `spring.jpa.open-in-view`，激活等 email 时可能仍占着 JDBC 连接。还没核实。

## 代码位置
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)（`activateAccount`、`codeRefused`）、[AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)
- email [EmailService.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java)（`checkCode`、`newCode`、`markSent`）、[EmailOutboxRepository.java](../../backend/emailService/src/main/java/com/comp5348/email/repository/EmailOutboxRepository.java)（`lockCodes`、`expireOtherLiveCodes`）、[EmailController.java](../../backend/emailService/src/main/java/com/comp5348/email/controller/EmailController.java)、[ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java)、[MailTargetLog.java](../../backend/emailService/src/main/java/com/comp5348/email/config/MailTargetLog.java)、[application.properties](../../backend/emailService/src/main/resources/application.properties)
- 前端 [EmailVerificationPage.jsx](../../frontend/src/pages/EmailVerificationPage.jsx)、[api.js](../../frontend/src/services/api.js)、[nginx.conf](../../frontend/nginx.conf)
- 部署和脚本：[compose.yml](../../backend/compose.yml)、[.env.example](../../backend/.env.example)、[mailpit.yaml](../../k8s/base/mailpit.yaml)、[email.yaml](../../k8s/apps/email.yaml)、[routes.yaml](../../k8s/gateway/routes.yaml)、[mailpit.ps1](../../backend/docker/mailpit.ps1)、[check-entry.ps1](../../k8s/scripts/check-entry.ps1)
- 测试：store `ActivationNeedsCodeIntegrationTest`；email `VerificationCodeRulesTest`、`VerifyEndpointErrorsTest`、`EmailServiceTokenIntegrationTest`；前端 `EmailVerificationPage.test.jsx`
- [docs/learning/12-activation-code.zh-CN.md](../learning/12-activation-code.zh-CN.md)
