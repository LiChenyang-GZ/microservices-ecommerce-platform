# 13 · 公开账号接口的限流

[English](13-rate-limits.md) | **中文**

> 提交。PR #33：红灯 `72d2484` → 绿灯 `9180c61`；在 kind 上发现的，红灯 `ef5ecaa` → 绿灯 `77e7021`；审查后红灯 `d534262` → 绿灯 `a828678`。
> 怎么发现的：ADR-25（激活码）的审查把"还没有限流"记成了下一步。
> 重放时间：**45 分钟**
> **后来改了**（学习文档 17、ADR-30）：`activate`、`reset-password` 还按"IP + 邮箱 15 分钟 5 次"数，码也不会再被耗掉；下面猜码的算术用的是旧的"每码 5 次"。
> 前置：[12 · 激活要邮件里的验证码](12-activation-code.zh-CN.md)（码本身的规则；这一篇讲的是谁能多频繁地要码、试码）。

---

## 1. 问题

**谁问、问多少次都没人管。** email 的 `send-verification` 公开，而且不看账号，谁都能让它给任意地址一直发信。`activate`、`reset-password` 不限速，而码猜错 5 次就作废、不管是谁猜错的：知道一个邮箱，每秒发 5 个错码，就能让它的注册或找回密码永远失败。`forgot-password` 给任何账号发信，`login` 能无限猜密码。而且 store 分不清客户端：在 compose 的 nginx 后面，所有请求都来自 nginx。

修之前证明坏事**真的发生了**的测试（红灯）：
- [SendVerificationViaStoreIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/SendVerificationViaStoreIntegrationTest.java)：store 没有 `send-verification`；发信之前没人看账号在不在
- [AuthRateLimitIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/AuthRateLimitIntegrationTest.java)：同一邮箱第 4 次要码、同一地址第 21 次、第 11 次错密码，都是 200 / 400 / 401，从来不是 429；Redis 里一个键都没有
- [RateLimitRedisDownIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/RateLimitRedisDownIntegrationTest.java)：Redis 暂停时没有 `ALERT rate_limit_unavailable`
- [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java)：不带密钥调 `send-verification` → 200，还发了信
- [RegisterPage.test.jsx](../../frontend/src/pages/RegisterPage.test.jsx)、[EmailVerificationPage.test.jsx](../../frontend/src/pages/EmailVerificationPage.test.jsx)：两个页面都还在直接调 email
- 在 kind 上发现的：[ClientIpOnKubernetesIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/ClientIpOnKubernetesIntegrationTest.java)，伪造的 `X-Forwarded-For` 被当成了客户端
- 审查推出来的：码没发出去的注册永远停在表单上；用邮箱和用户名登录是两个计数

---

## 2. 是什么（框架层）

### 2.1 限流只放一处
5 个公开账号接口现在都在 store：浏览器向 store 要码，store 只在账号存在且没激活时才让 email 发（[AccountService#sendVerificationCode](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)）。email 不再有公开接口。另一个选项是在入口限流，但入口只能按地址数：没法按邮箱数，也没法在登录成功时清零。

### 2.2 数什么、按什么顺序数
一条限制就是一个键加一个窗口："这个邮箱、15 分钟"，"这个地址、1 小时"（[AuthRateLimits](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)）。
- **按邮箱**挡住一个信箱被轰炸；**按地址**挡住一个客户端试很多邮箱。
- **先数地址**，地址没超才数邮箱：不然攻击者自己的地址已经被挡，每个被挡的请求还在给受害人的邮箱加一。
- **登录按账号数**，不按输入的字：`alice@x.com` 和 `alice` 是同一个账号，按输入的字数就给了猜的人两份额度（审查发现的）。
- **在查密码之前计数**，超了以后密码对也是 429。错了 401、对了 200，等于告诉猜的人什么时候猜中了。

### 2.3 Redis 里的固定窗口，原子执行
[RateLimiter](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java) 跑一个 Lua 脚本：`INCR` 这个键；计数是 1、或者键不知怎么没有过期时间，就 `PEXPIRE`；返回计数和剩余时间。Redis 把一个脚本当一条命令执行。拆成两条命令的话，`INCR` 之后进程挂了，键就永远不过期，那个邮箱就永远被挡。

几种算法：
| 算法 | 代价 | 缺点 |
|---|---|---|
| 固定窗口（选的） | 每个键一个计数器 | 窗口边界上最多到 2 倍上限 |
| 滑动日志 | 每个键一个时间戳有序集合 | 内存随上限增长 |
| 滑动窗口计数 / 令牌桶 | 每个键两个计数器或一点状态 | 脚本更复杂；更平滑 |

为什么用 Redis 不用内存：store 重启计数不丢，多个副本数的是同一份。

### 2.4 哪个地址才是客户端？
`X-Forwarded-For` 是每层代理往后追加的列表。只有**我们自己的**入口写的那个值能信，它前面的都是客户端写的。所以 [ClientIp](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java) 取**最后一个**值。compose 的 nginx 用它看到的地址覆盖这个头，Envoy 往后追加。

坑在框架里，不在我们的代码里。Spring Boot 在 Kubernetes 上把 `server.forward-headers-strategy` 默认设成 NATIVE，这会打开 Tomcat 的 `RemoteIpValve`。它从右往左走，把内网地址当"可信代理"跳过，把第一个外网地址当客户端，还把用过的部分从头里删掉。kind 里的客户端是 `172.22.0.1`，是内网地址，于是 `X-Forwarded-For: 1.2.3.4` 成了"客户端"，每换一个伪造值就是新额度。compose 不是 Kubernetes，MockMvc 不经过 Tomcat，只有真集群看得到。修法是 `server.forward-headers-strategy=none`：只让一处决定，就是我们的 `ClientIp`。

### 2.5 fail-open，而且别进健康检查
Redis 出错或太慢（超时 500 毫秒），请求照常处理、不限流，打 `ALERT rate_limit_unavailable`。这是在选"故障时付出什么"：fail-closed 防猜更稳，但会让所有注册和登录都停掉。另外把 Redis 从 `/actuator/health` 里去掉：不然 compose 的 healthcheck 会因为一个辅助组件挂了，就把 store 标成不健康。

### 2.6 不会红的测试什么都没测
"Redis 挂了 store 还健康"的第一版测试断言健康检查回 200。测试环境里 RabbitMQ 故意指向没人听的端口，健康检查永远是 503：这条测试永远绿不了。第二版（"暂停 Redis 前后状态码一样"）去掉修复也是绿的。最终版打开 `show-components`，断言健康检查的组成部分里没有 `redis`，并且两个方向都验证过：去掉 `management.health.redis.enabled=false` → 红，加回来 → 绿。

---

## 3. 请求路径

**有人轰炸一个信箱**
1. `POST /api/store/user/send-verification {"email":"victim@…"}` → `AccountController#sendVerificationCode` → `AuthRateLimits#sendVerification`：先数地址，再数邮箱。
2. 15 分钟内第 1–3 次：账号存在且没激活 → store 带密钥问 email → 发信；否则 200、不发。
3. 第 4 次：**429**，`Retry-After: 894`，"Too many attempts, please try again in 894 seconds"。不问 email。

**有人猜密码**
1. `POST /api/store/user/login` → store 先查账号 → 数 `rl:login:ip:<地址>`（100）和 `rl:login:ip-account:<地址>:<account:id 的哈希>`（10）。
2. 15 分钟内第 11 次，不管用邮箱还是用户名、密码对不对：**429**。主人从别的地址照样能登录。
3. 登录成功清掉"这个地址 + 账号"的计数。

**kind 上的伪造头**
`curl -H "X-Forwarded-For: 1.2.3.4" http://127.0.0.1/api/store/user/send-verification` → Envoy 追加 `172.22.0.1` → Tomcat 不碰这个头 → `ClientIp` 取 `172.22.0.1` → 键是 `rl:send-verification:ip:172.22.0.1`。

**Redis 挂了**
`RateLimiter#hit` 500 毫秒超时 → `UNAVAILABLE` → 请求照常处理，`ALERT rate_limit_unavailable`。健康检查还是 UP。

运行中的系统上的证据：compose（`-p authcheck`）同一邮箱连要 4 次 → 200、200、200、429；经 nginx 的伪造头记成 nginx 看到的地址；停掉 Redis → 约 515 毫秒回答并打 ALERT，健康检查 UP；kind → 伪造头记成 `172.22.0.1`；两边冒烟脚本都过；`check-entry.ps1` 55/55。

---

## 4. 为什么这么做

### 4.1 只放在 store（Christy 定的）
一处读、一处测。代价：要码多了一跳，浏览器 → store → email。

### 4.2 用 Redis（Christy 定的）
关口 0 时选它，没选内存里的 map：store 现在只有一个副本，是 outbox 的限制（ADR-17），不是承诺；重启也不该白送新额度。代价：多一个容器，升级要加 `REDIS_PASSWORD`。

### 4.3 fail-open（Christy 定的）
写进失败场景表当成一个选择，而不是默认值。辅助组件的故障不该变成注册、登录的故障；ALERT 让它被看见。

### 4.4 这些数 🤖
每个邮箱 15 分钟 3 个码：页面等 60 秒后够重发两次。每个码 5 次猜测，就是每 15 分钟 15 次，6 位码平均约 350 天，这期间受害人每天收约 290 封信。登录每个地址 + 账号 15 分钟 10 次；同一地址换账号 100 次。都是按"正常使用"估的，写下来方便改。

### 4.5 审查找到了什么
没有必须修的。两条建议修的，都是**限流周围的事**：码被拒的注册，让用户拿着一个永远进不去的账号（到不了输码页，再注册是"邮箱已存在"，登录是"请先验证邮箱"）；登录按输入的字数，邮箱和用户名是两份额度。其他的记成已知限制：email 挂着也占额度、共用地址共用额度、IPv6 的 /64、Redis 的 `maxmemory`、`send-verification` 的耗时、抢注别人的邮箱。

---

## 5. 这里的面试点

**结论**：限流好不好，看它的键：数对了东西、在对的时刻数、地址是自己写的。

| 话题 | 在哪 | 这里的判断 |
|---|---|---|
| ➕ 限流算法：固定窗口、滑动窗口、令牌桶 | [RateLimiter](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java) | 固定窗口；接受边界突发 |
| ➕ Redis 原子性：Lua 脚本 vs `INCR` + `EXPIRE` | `RateLimiter` | 一个脚本，没有永不过期的键 |
| ➕ 选键：按邮箱、按地址、按账号；检查的顺序 | [AuthRateLimits](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)、`AccountController#loginUser` | 先数地址；登录按账号 |
| ➕ `X-Forwarded-For` 和可信代理 | [ClientIp](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java)、`forward-headers-strategy=none` | 只取最后一个值；关掉框架的 valve |
| ➕ fail-open vs fail-closed | `RateLimiter`、`application.properties` | open，加 ALERT |
| ➕ 健康检查该含什么 | `management.health.redis.enabled=false` | 辅助组件挂了不把 store 标成挂 |
| ➕ 429 和 `Retry-After`；别泄露"猜中了" | `AccountController#tooManyAttempts`、`#loginUser` | 查密码之前计数 |
| ➕ 用户枚举和耗时 | `AccountService#sendVerificationCode` | 所有地址都回同样的 200；耗时差记下来 |
| 📒 测试：防改坏的测试要能红 | `RateLimitRedisDownIntegrationTest` | 去掉修复验证过是红的 |

场景锚点：
- "谁都能让我们的邮件服务给任意地址一直发验证码。我把这个接口挪到 store 后面，在 Redis 里按邮箱、按客户端地址限流。"
- "在 Kubernetes 上，伪造一个 X-Forwarded-For 就是一份新额度。原因是 Spring Boot 打开了 Tomcat 的 RemoteIpValve，它信任内网地址；我把它关了，只取我们入口写的那个值。"
- "我写的'Redis 挂了 store 还健康'的测试，去掉修复也是绿的。我改到去掉修复会红为止。"

---

## 6. 追问链

1. **为什么不在网关限流？** → 网关只看得到地址。按邮箱数、"登录成功清零"都得在应用里。
2. **为什么不先 `INCR` 再 `EXPIRE`？** → 两条命令，中间挂了键就永不过期。Lua 脚本当一条命令执行。
3. **攻击者换地址呢？** → 按邮箱的限制还在：15 分钟 3 个码。挡不住的是耗掉受害人的额度；下一步是按邮箱数猜错次数。
4. **代理后面怎么拿真实客户端地址？** → 只信自己的代理写的：`X-Forwarded-For` 最后一个值，而且请求得是经过它来的。还要看框架对这个头做了什么，我们这里框架把它改写了。
5. **Redis 挂了怎么办？** → 请求照常、不限流，每个最多多等 500 毫秒，打 ALERT，健康检查不受影响。这是选择：fail-closed 更安全，但会让所有登录停掉。
6. **为什么密码对了也 429？** → 不然 429 和 200 的区别就告诉了猜的人他猜对了。
7. **IPv6 或者加了负载均衡会怎样？** → IPv6：一个人有一个 /64，要按 /64 归一。入口前面有负载均衡：它的地址成了最后一个值，要显式信任那一跳。

---

## 7. 怎么说

- "数对的东西，在检查之前数，地址用自己写的。"
- "辅助组件的故障该丢的是保护，不是可用性，而且要大声报出来。"
- "去掉修复测试还不红，这个测试就没在测修复。"

---

## 8. 延伸阅读

- Redis 的限流模式和 `INCR`：<https://redis.io/docs/latest/commands/incr/#pattern-rate-limiter>
- Redis 脚本（原子执行）：<https://redis.io/docs/latest/develop/interact/programmability/eval-intro/>
- Spring Boot 放在代理后面（`server.forward-headers-strategy`、Kubernetes 上的默认值）：<https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.use-behind-a-proxy-server>
- Envoy 的 `x-forwarded-for` 和可信跳数：<https://www.envoyproxy.io/docs/envoy/latest/configuration/http/http_conn_man/headers#x-forwarded-for>
- RFC 6585，429 Too Many Requests：<https://www.rfc-editor.org/rfc/rfc6585#section-4>
- OWASP Authentication cheat sheet（限速、枚举）：<https://cheatsheetseries.owasp.org/cheatsheets/Authentication_Cheat_Sheet.html>

---

## 9. 自检

1. 把 store `application.properties` 里的 `server.forward-headers-strategy=none` 去掉。哪个测试会红？为什么只有它？
<details><summary>答案</summary>
`ClientIpOnKubernetesIntegrationTest`：它用 `spring.main.cloud-platform=kubernetes` 起一个真的 Tomcat，Spring Boot 就会打开 `RemoteIpValve`，`1.2.3.4, 203.0.113.9` 被记成 `1.2.3.4`。这就是红灯提交 `ef5ecaa`。`AuthRateLimitIntegrationTest` 还是绿的：MockMvc 不经过 Tomcat，valve 根本不跑。
</details>

2. 为什么先数地址、再数邮箱？
<details><summary>答案</summary>
不然每个被地址挡下的请求还是会给邮箱加一，一个自己地址已经超限的攻击者，照样能让受害人的邮箱一直卡在上限。先数地址，被挡的请求就不花受害人的额度。
</details>

3. （真懂才答得上）"Redis 挂了 store 还健康"的测试前两版错得不一样。各错在哪？最终版凭什么可信？
<details><summary>答案</summary>
(a)"健康检查回 200"永远过不了：测试环境里 RabbitMQ 故意连不上，整体健康永远是 503。(b)"前后状态码一样"去掉修复也过：超时 500 毫秒时 Redis 自己的检查很快回 DOWN，两次整体都是 503。最终版断言健康检查的组成部分里没有 `redis`（打开了 `show-components`），并且跑过"去掉 `management.health.redis.enabled=false`"（红）和"加回来"（绿）。
</details>

---

## 10. 重放时间

**45 分钟**：读 §1、§2（15 分钟）→ `git checkout 72d2484`，跑 `./gradlew :storeService:test --tests '*AuthRateLimitIntegrationTest'`，看第 4 个码照样发出去（5 分钟）→ `git checkout 77e7021`，跑它和 `--tests '*ClientIpOnKubernetesIntegrationTest'`，绿（5 分钟）→ 去掉 `server.forward-headers-strategy=none`，跑 Kubernetes 那个测试看它红，再改回去（5 分钟）→ 自检（15 分钟）。
