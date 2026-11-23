# ADR-26 · store 的公开账号接口限流，计数存在 Redis

[English](26-rate-limits-in-redis.md) | **中文**

**状态：** 已采纳。修订 ADR-23（email 不再有公开接口）和 ADR-25（验证码改向 store 要；"还没有限流"那条后果）。

## 背景
ADR-25 之后，账号要邮件里的码才能激活，但谁问、问多少次都没人管：
- email 的 `send-verification` 公开，而且不看账号：谁都能让它给任意地址发信，想发多少发多少。
- store 的 `activate`、`reset-password` 不限速。码猜错 5 次就作废（ADR-25），不管是谁猜错的；知道某个邮箱的人每秒发 5 个错码，就能让这个地址的注册或找回密码永远成功不了。
- `forgot-password` 给任何存在的账号发信，不限次数；`login` 能无限猜密码。
- store 不知道客户端的地址：compose 里的 nginx 不传，所有请求看起来都来自 nginx。

## 决定

### 只放在一处：store
- 浏览器向 store 要注册验证码（`POST /api/user/send-verification`）。只有账号存在且没激活时，store 才带 email 的服务密钥让 email 发；其他地址同样回 200、不发信。email 拒绝密钥、发信失败、挂了或太慢 → 503（401 时打 `ALERT email_auth_failed`）。
- email 的 `ServiceTokenFilter.PUBLIC_PATHS` 变空：所有接口都要 store 的密钥。nginx 和 gateway 不再往 email 转发任何路径。
- 这样 5 个公开账号接口都在 store，限流只写一处。（Christy 定的。）
- 没选：在入口限流。nginx 的 `limit_req` 在每个 worker 的内存里数；Envoy 的全局限流要另起一个限流服务；而且两者都没法按邮箱数，也没法在登录成功时清零。

### 规则
| 接口（`/api/user/...`） | 按什么数 | 上限 |
|---|---|---|
| `send-verification`、`forgot-password` | 先数客户端地址，再数邮箱 | 同一地址 1 小时 20 次；同一邮箱 15 分钟 3 次 |
| `activate`、`reset-password` | 客户端地址 | 15 分钟 20 次 |
| `login` | 客户端地址 + 账号 | 15 分钟 10 次，登录成功就清零 |
| `login` | 客户端地址 | 15 分钟 100 次 |

- 每个接口各数各的。邮箱先转小写、去空格（`" Victim@X.com"` 和 `victim@x.com` 进同一个信箱），再只存 SHA-256 的前 16 字节：Redis 里没有邮箱地址。
- **先数地址**，地址没超才数邮箱：被地址挡下的请求，不占那个邮箱的额度。
- **登录按账号数**，不按输入的字：登录框能填邮箱也能填用户名。store 先查到账号，数 `account:<id>`；查不到账号的，数 `typed:<输入>`。计数在查密码之前，超了以后密码对也是 429（不然这个回答就等于告诉对方猜中了）。
- 每个邮箱 15 分钟 3 封，够用户"没收到再发两次"；页面本来就要等 60 秒才能重发。猜码的人每 15 分钟最多拿到 3 个码 × 5 次 = 15 次猜测，一天约 1440 次：6 位码平均要猜约 350 天，这期间受害人每天要收约 290 封信。

### 超了怎么回
`429 Too Many Requests`，`Retry-After: <这个窗口还剩几秒>`（正整数），body `{"success":false,"message":"Too many attempts, please try again in N seconds"}`。页面本来就显示 `message`。

### 计数：Redis 里的固定窗口
- **用 Redis**，不放内存：store 重启计数不丢，多副本时也准。compose 和 kind 各跑一个（7.4、`--requirepass`、不发布端口、不存快照）。（Christy 定的。）
- **固定窗口**：一个 Lua 脚本里 `INCR`，计数是 1 或者键没有过期时间时 `PEXPIRE`，返回计数和剩余时间。必须在一个脚本里：拆成 `INCR`、`EXPIRE` 两条命令，中间进程挂了，这个键就永远不过期，那个邮箱 / 地址就永远被挡。
- 没选：滑动窗口、令牌桶。边界上更平滑，但每个键要一个有序集合或更复杂的脚本；固定窗口的已知缺点（边界上能连用两个窗口的额度）接受。

### 客户端地址
- store 取 `X-Forwarded-For` 的**最后一个**值，也就是我们的入口写的那个；没有这个头就用连接的地址。前面的值是客户端自己写的，不信。
- compose：nginx **覆盖**这个头（`proxy_set_header X-Forwarded-For $remote_addr`），浏览器自己带的头到不了 store。kind：Envoy 把它看到的地址追加在最后。
- store 设 `server.forward-headers-strategy=none`。否则 Spring Boot 在 Kubernetes 上会自动打开 Tomcat 的 `RemoteIpValve`，它把内网地址当可信代理跳过，取第一个外网地址。kind 里的客户端是 `172.22.0.1`（内网地址），于是伪造的 `X-Forwarded-For: 1.2.3.4` 成了"客户端"，每换一个值就是新额度。compose 不是 Kubernetes，MockMvc 不经过 Tomcat，都测不出来；是在 kind 上实测发现的。
- 绕过入口直接访问 store 的人能随便写这个头。store 只在本机（compose 只发布在 127.0.0.1）和集群里能直接访问到。

### Redis 挂了：fail-open
- Redis 出错或 500 毫秒内没回答，请求照常处理、不限流，store 打 `ALERT rate_limit_unavailable`。每个请求最多多等约 0.5 秒（登录成功约 1 秒：计数一次、清零一次）。（Christy 定的。）
- 没选：fail-closed（Redis 挂着就 503）。防猜更稳，但 Redis 一挂，谁都注册不了、登录不了。
- store 的健康检查不含 Redis（`management.health.redis.enabled=false`）；否则 compose 用 `/actuator/health` 做的 healthcheck 会在 Redis 挂时把 store 也标成不健康。

## 代价与影响
- **限流还挡不住的：**
  - 用很多地址的人，还是能把别人的码耗掉，或者用完那个邮箱 15 分钟 3 封信的额度，让对方一段时间内注册 / 找回密码失败；猜错的次数还没按邮箱数；
  - 很多地址一起猜密码，要验证码或异常检测；
  - 注册接口本身不限，能批量建垃圾账号（激活不了，每个 15 分钟最多 3 封信）；
  - 固定窗口在边界上能连用两个窗口的额度。
- **email 挂着也占额度**：点了 3 次"重发"都是 503，email 恢复后第 4 次要等约 15 分钟。
- **共用地址就共用额度**：很多人共用一个公网地址（学校、公司、手机运营商的 NAT）时共用按地址的计数。
- **IPv6**：一个人通常有一整个 /64，按地址的限制对他不起作用；每个新地址还会在没设 `maxmemory` 的 Redis 里多几个键。上云前要做：按 /64 归一，设 `maxmemory` 加 `volatile-ttl`。
- **Envoy 前面如果还有负载均衡**（上云多半会有），`X-Forwarded-For` 的最后一个值就是负载均衡的地址，所有人共用一份额度。到时要让 Envoy 信任那一跳，再核对 `ClientIp`。
- **`send-verification` 的耗时**：对所有地址都回 200，但"有账号、没激活"的要等 email 发完，会慢一些，能看出哪些地址是这种状态；注册接口本来就会回"Email already exists!"，几乎没多泄露。
- **别人还是能先用你的邮箱注册**（这个决定之前就有）：现在你能到验证页并激活，但账号的密码是他设的。
- **升级**：`backend/.env` 要加 `REDIS_PASSWORD`，没有它 compose 起不来；kind 要再跑一次 `up.ps1`。脚本跑一整轮大约注册 10 个用户，所以一小时大约两轮；README 写了怎么清零。
- **多一个容器**，单副本，和所有有状态组件一样（ADR-13）。

## 代码位置
- store [RateLimiter.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/RateLimiter.java)（Lua 脚本、fail-open）、[AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)（规则）、[ClientIp.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/ClientIp.java)、[AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)（`loginUser`、`sendVerificationCode`、`tooManyAttempts`）、[AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)（`sendVerificationCode`）、[application.properties](../../backend/storeService/src/main/resources/application.properties)
- email [ServiceTokenFilter.java](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java)
- 前端 [RegisterPage.jsx](../../frontend/src/pages/RegisterPage.jsx)、[EmailVerificationPage.jsx](../../frontend/src/pages/EmailVerificationPage.jsx)、[api.js](../../frontend/src/services/api.js)、[nginx.conf](../../frontend/nginx.conf)
- 部署和脚本：[compose.yml](../../backend/compose.yml)、[.env.example](../../backend/.env.example)、[redis.yaml](../../k8s/base/redis.yaml)、[store.yaml](../../k8s/apps/store.yaml)、[routes.yaml](../../k8s/gateway/routes.yaml)、[apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1)、[mailpit.ps1](../../backend/docker/mailpit.ps1)
- 测试：store `SendVerificationViaStoreIntegrationTest`、`AuthRateLimitIntegrationTest`、`RateLimitRedisDownIntegrationTest`、`ClientIpOnKubernetesIntegrationTest`；email `EmailServiceTokenIntegrationTest`；前端 `RegisterPage.test.jsx`、`EmailVerificationPage.test.jsx`
- [docs/learning/13-rate-limits.zh-CN.md](../learning/13-rate-limits.zh-CN.md)
