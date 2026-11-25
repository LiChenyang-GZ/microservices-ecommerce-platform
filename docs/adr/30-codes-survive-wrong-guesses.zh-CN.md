# ADR-30 · 码长到猜错也不用作废；猜的次数由 store 按"IP + 邮箱"限

[English](30-codes-survive-wrong-guesses.md) | **中文**

**状态：** 已采纳。修订 ADR-25（码猜错 5 次作废）和 ADR-26（`activate` / `reset-password` 只按 IP 限；"别人能耗掉你的码"那条后果）。

## 背景
码是 6 位数字（10⁶ 种）。种类这么少，ADR-25 只能限制一个码总共能被猜几次：一个邮箱猜错 5 次，它的活码就作废，**不管是谁猜的**。ADR-26 后来给 `activate`、`reset-password` 按 IP 限了速（15 分钟 20 次），可这挡不住：一个地址猜 5 次就够了。

所以一个地址每 15 分钟就能：替你的邮箱要 3 次码（用完它的额度，最后一封是唯一的活码），再猜错 5 次。你信箱里的码全是死的，自己要码是 429。注册激活、找回密码都做不了，别人先注册了你的邮箱时也认领不回来（ADR-27：认领走的就是重置码）。

只把错猜改成按"邮箱 + IP"记也不行。那个上限保护的是一个很好猜的码：不设每码总数，1000 个地址（IPv6 一个 /64 远不止这么多）每个码就能猜 5000 次，一天最多 288 个码，一天之内就能猜中。"每个码总共少猜几次"和"别人猜不死你的码"是同一个旋钮的两头，**除非码变长**。

| 方案 | 码 | 别人能猜死你的码吗 | 攻击者一天猜中的概率 |
|---|---|---|---|
| 以前 | 6 位数字 | 能，1 个地址 | 288 × 5 / 10⁶ ≈ 0.14% |
| 按"邮箱 + IP"、不设总数 | 6 位数字 | 不能 | 1000 个地址：几乎必中 |
| 按"邮箱 + IP" + 每码 20 次 | 6 位数字 | 能，4 个地址 | ≈ 0.58% |
| **选的：码变长，只按"IP + 邮箱"限** | **10 位数字** | **不能** | 1000 个地址：288 × 5000 / 10¹⁰ ≈ 0.014% |

## 决定（Christy 定的）

- **码改成 10 位数字**（10¹⁰ 种），`SecureRandom` 生成。Christy 选了数字，没选 8 位字母数字（约 10¹²，难猜 100 倍）：手机弹数字键盘，页面改动也小。
- **猜错不作废码**：`EmailService#checkCode` 回 false，码照样活着。5 分钟过期（V15）、"新码顶掉旧码"（V14）不变。比对前去掉空格（从邮件复制可能带进来）。`failed_attempts` 不再计数，列留着。
- **store 按"IP + 邮箱"限校验次数**：`activate`、`reset-password` 先数 IP（15 分钟 20 次，和以前一样），再数这个 IP + 这个邮箱（15 分钟 5 次，邮箱去空格、转小写，Redis 里存哈希）。被 IP 挡住的请求不计入这一对。两个检查都在问 email 之前。
- 计数放在 store、不放在 email：store 已经知道客户端地址（`ClientIp`），也已经有 Redis 计数器，email 的接口不用改，两个服务谁先上线都行。
- 验证页、重置页收 10 位数字，别的字符去掉。过渡期也收 6 位（上线前刚发的码 email 还认）；码框让手机弹数字键盘、提示填入邮件里的码（`inputMode="numeric"`、`autoComplete="one-time-code"`）。

## 后果
- **别人猜不死你的码了。** 别人还是能用完你 15 分钟 3 封的发信额度，但他触发的每封码都进你的信箱、而且一直活着，最新那封能用。ADR-27 写的"认领会被一直堵住"这条路关上了。
- **暴力猜码靠码的长度兜底。** Redis 挂了时限流全放行（ADR-26，fail-open）；一个客户端每秒猜 1000 次、猜一整天，约 0.86%。这是 fail-open 的代价，所以 Redis 挂了要告警。
- **猜码的上限现在全靠"按地址区分客户端"。** IPv6 下一个客户端通常有一整个 /64，每个请求换个地址就是新的计数；云上负载均衡后面，`X-Forwarded-For` 的最后一个值是负载均衡，所有人共用一个地址（"每 IP + 邮箱 5 次"变成全站"每邮箱 5 次"，Q16 以 429 的形式回来）。本地（compose、kind：IPv4，没有多一跳）都碰不到。**按 /64 归一、在负载均衡后面核对客户端地址的规则，现在是上云的前提**，不是锦上添花：它们是猜码的唯一上限。改之前一个码不管谁猜总共 5 次，IPv6 下也成立。
- **同一个 NAT 后面的人共用这一对的计数**：和你同一个公网地址的人能让你对这个邮箱等 15 分钟。
- **每次都算，对的错的、主人自己的都算**：同一地址对同一邮箱试 5 次（手抖输错、email 挂着时的 503、邮箱大小写和注册时不一样而数据库匹配不上）就要等 15 分钟；码只活 5 分钟，等完要重新要码。以前猜错 5 次码作废，主人可以马上再要一个。
- **发布顺序：前端先上，email 后上。** 页面收 6 或 10 位，所以新前端配老 email、配上线前发的码都能用。上线前打开的页面还是旧代码（单页应用不会自己刷新），会把 10 位码截成 6 位，失败还用掉 5 次：这些用户要刷新页面。下一个版本再只收 10 位。
- 码更长、更难输。错猜除了限流计数器，哪里都不再记录。

## 代码在哪
- email [EmailService.java](../../backend/emailService/src/main/java/com/comp5348/email/service/EmailService.java)（`CODE_DIGITS`、`generateVerificationCode`、`checkCode`），`EmailOutbox`（`failedAttempts`，不再计数）
- store [AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)（`activate`、`resetPassword`、`codeCheck`），[AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)
- 前端 `EmailVerificationPage.jsx`、`ResetPasswordPage.jsx`；脚本 `backend/docker/mailpit.ps1`
- 测试：email `VerificationCodeRulesTest`；store `AuthRateLimitIntegrationTest`；前端 `EmailVerificationPage.test.jsx`、`ResetPasswordPage.test.jsx`
- [docs/learning/17-codes-survive-wrong-guesses.zh-CN.md](../learning/17-codes-survive-wrong-guesses.zh-CN.md)
