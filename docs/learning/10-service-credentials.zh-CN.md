# 10 · 服务间凭据：谁能调 bank、delivery、email（鉴权 PR ①）

[English](10-service-credentials.md) | **中文**

> 对应 commit（PR #26）：红 `9dad7eb` `550bb26` `584f482` `92e5433` `dc1672f` `775c043` `0ea58ef` `03c1aea` `b47d980` `1c85326` → 绿 `8dbd151`（bank）`f3145d2`（delivery）`d36fbd2`（email）`a391340` `896510b` `e38b223`（store），`eed42cc` `76d57da`（部署）
> 怎么发现的：第 1 波审查待办。读代码发现网络里面没有任何地方检查"是谁在调"（ADR-23 "背景"）。
> 预计回放时间：**45 分钟**
> 前置：[05 · gRPC 调 bank](05-grpc-bank-call.zh-CN.md)（store → bank 走 gRPC）、[08 · K8s 部署](08-kubernetes-on-kind.zh-CN.md)（入口允许名单）。

---

## 1. 问题

**入口的允许名单是唯一的一把锁，而且只朝外。** 已经在里面的东西（集群里的 pod、compose 网络里的容器、本机进程）能：

- 调 bank 的 gRPC `Transfer` / `Refund` 动钱（没有拦截器）；
- 调 `POST /api/bank/transfer`，从 `SYSTEM` 给任何账户转钱（bank 的 REST 过滤器不带 token 也放行）；
- 调 delivery 的 `/internal/deliveries/cancel-by-order/{id}` 取消任何配送单，store 就会退款（过滤器只管 `/api/*`）；
- 调 email 的通知接口，以商店的名义给任意邮箱发信。

修之前证明"请求**真的成功了**"的测试（红灯）：
- bank [BankGrpcAuthIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankGrpcAuthIntegrationTest.java)：不带 token 转账，余额真的变了。[BankRestAuthIntegrationTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/BankRestAuthIntegrationTest.java)、[OldBankRestEndpointsRemovedTest.java](../../backend/bankService/src/test/java/com/comp5348/bank/OldBankRestEndpointsRemovedTest.java)
- delivery [CancelByOrderAuthIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/CancelByOrderAuthIntegrationTest.java)：取消标记写进去了，Cancelled 事件也发出去了
- email [EmailServiceTokenIntegrationTest.java](../../backend/emailService/src/test/java/com/comp5348/email/EmailServiceTokenIntegrationTest.java)（email 的第一批测试）、[OldEmailEndpointsRemovedTest.java](../../backend/emailService/src/test/java/com/comp5348/email/OldEmailEndpointsRemovedTest.java)
- store [BankServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/adapter/BankServiceTokenIntegrationTest.java)、[DeliveryServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/events/DeliveryServiceTokenIntegrationTest.java)、[EmailServiceTokenIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailServiceTokenIntegrationTest.java)：store 不带凭据，也不打告警
- 每个服务一个 `*StartupWithoutServiceTokenTest`：没配密钥照样启动

---

## 2. 是什么（框架层）

### 2.1 共享密钥，常数时间比较
每个被调方读一个值（`bank.service-token`，来自 `BANK_SERVICE_TOKEN`），请求里带的必须正好是它。[ServiceToken#matches](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java) 用 `MessageDigest.isEqual` 比：不管前面有几个字节对上，花的时间都一样。`String.equals` 遇到第一个不同的字节就返回，调用方测很多次回复时间，理论上能一个字节一个字节猜出密钥。64 位随机密钥隔着网络很难这样猜，但常数时间比较没有任何代价。

### 2.2 没配就不启动
`ServiceToken` 的构造函数发现值是空的就抛 `IllegalStateException`，信息里点名键名。bean 建不出来，Spring 容器启动失败，服务直接退出。不这样做的话，没配密钥的服务要么拒绝所有请求（看起来健康，其实每笔付款都失败），要么写了个 `if (token == null) 放行` 就跳过了检查。

路上的坑：`application.properties` 必须显式写 `bank.service-token=${BANK_SERVICE_TOKEN:}`。Spring 的宽松绑定把环境变量 `BANK_SERVICE_TOKEN` 对到 `bank.service.token`（下划线 → 点），对不到 `bank.service-token`；带连字符的键要显式写占位符。

### 2.3 servlet 过滤器，只注册到指定路径
[ServiceTokenFilter](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceTokenFilter.java) 继承 `OncePerRequestFilter`：凭据不对 → 401，不调 `filterChain.doFilter`，controller 根本不会执行。

它**没有** `@Component`。Spring Boot 会把每个 `Filter` bean 注册到**所有**路径（`/*`）。delivery 要在 `/internal/*` 上用服务凭据过滤器、在 `/api/*` 上用用户 token 过滤器，所以两个都是普通类，由 [FilterConfig](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java) 用 `FilterRegistrationBean` + `addUrlPatterns(...)` 注册。

测试必须经过过滤器。用 `standaloneSetup` 或关掉过滤器的 MockMvc 会绕过它，鉴权测试就会"假绿"；所以 delivery 的 `CancelByOrderApiIntegrationTest` 从 `mvc()` 改成了 `mvcWithFilters()`。

### 2.4 gRPC 两头都有拦截器
- 服务端：[ServiceTokenServerInterceptor](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java) 读 metadata 的 `x-service-token`。不对 → `call.close(Status.UNAUTHENTICATED, …)`，返回一个空 listener，方法不会开始执行。[GrpcServerLifecycle#start](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/GrpcServerLifecycle.java) 注册的是 `ServerInterceptors.intercept(bankGrpcService, interceptor)`：整个服务的每个方法都被包住，以后新加的方法也是。
- 客户端：[BankAdapter](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java) 建 channel 时 `.intercept(MetadataUtils.newAttachHeadersInterceptor(metadata))`，这个 channel 上的每次调用都带上凭据。
- gRPC 的 metadata 键必须小写（HTTP/2 的头部规则）。HTTP 头是 `X-Service-Token`，gRPC 的键是 `x-service-token`。

servlet 过滤器完全看不到 gRPC：gRPC 跑在 9090 端口自己的 Netty 服务器上。只保护 REST 的话，动钱的那条路还是开着的；第 2 波 B 的审查说 gRPC 是"第二扇门"，就是这个意思。

### 2.5 允许名单 vs 拒绝名单（email）
email 有两个公开接口（注册页在用），其余只给 store 调。过滤器有两种写法：
- **拒绝名单**：把知道是内部的路径保护起来。以后新加的接口，在有人记得加进去之前都是开着的。
- **允许名单**（选了这个）：[ServiceTokenFilter#PUBLIC_PATHS](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java) 列出两个公开路径，**精确**匹配；`/api/*` 下别的路径（不管存不存在）都要凭据。删掉的接口对外人回 401（看不出它已经没了），对 store 回 404。

旧过滤器用 `startsWith`，`/api/email/send-verification-随便什么` 也算公开。

### 2.6 每个被调方一个 HTTP 客户端
store 在建每个被调方的 `RestTemplate` 时，用 `RestTemplateBuilder#defaultHeader` 挂上头。每个 `RestTemplate` 只连一个服务，bank 的密钥不会发到 delivery 去。`RestTemplateBuilder` 是不可变的：`defaultHeader` 返回一个新的 builder，共享的那个 builder bean 不会被改。

路上碰到的两个坑：
- `RestTemplateBuilder` 默认选 JDK 的 `HttpClient`，它会先试着升级到 HTTP/2（h2c）。WireMock 直接重置连接（`RST_STREAM`）；原来的 `new RestTemplate()` 用的是普通 HTTP/1.1。[AccountService](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java) 和两个 adapter 一样，显式用 `SimpleClientHttpRequestFactory`。
- 原来调 email 的客户端没有超时。email 卡住时，每个找回密码请求占着一个 Tomcat 线程。现在连接、读取各 5 秒（[EmailCallTimeoutIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/account/EmailCallTimeoutIntegrationTest.java)，审查发现的）。

### 2.7 NetworkPolicy
NetworkPolicy 选中一组 pod（`app: bank`），写明谁能连哪个端口。一旦有策略在入站方向选中了某个 pod，没写的就一律丢弃。[bank.yaml](../../k8s/apps/bank.yaml) 最后那条：9090 只许 `app: store`；8084 谁都能连（探针要用）。Kubernetes 只是存下这个对象，真正执行的是**网络插件**。kind 默认的 kindnet 从 v0.24 起会执行；在 v0.33 上从真 pod 里面测过：store → bank:9090 能连，delivery → bank:9090 被挡。

---

## 3. 请求路径导读

**密钥对的时候付一笔款**
1. `OutboxProcessor` 拿到 PAY 任务 → `BankAdapter#transfer` 在 channel 上调 `Transfer`，客户端拦截器加上 `x-service-token`。
2. bank 的 `ServiceTokenServerInterceptor` 比较 → 相等 → `BankGrpcService#transfer` 照旧执行（ADR-04、ADR-22）。

**同一笔款，密钥错了**（store 和 bank 配了不同的值）
1. bank 的拦截器 → `UNAUTHENTICATED`，记一行 `Rejected gRPC …: wrong service token`。钱没动。
2. store 把所有 gRPC 错误都当 UNKNOWN（ADR-22）→ 同一个键重试，之后 PAY_UNKNOWN；**同时** `BankAdapter#alertIfAuthFailed` 打 `ALERT bank_auth_failed key=…`。没有这一行，日志看起来就是一串超时。
3. 把密钥改对、重启 → 下一次用同一个键重试就成功；不会扣两次（ADR-04）。

运行中的系统上的证据（compose `-p authcheck` 和 kind）：smoke、场景 06 / 07 / 08 / 01 全过，没有 `ALERT`；手动请求：不带或带错密钥，bank REST、delivery `/internal`、email 找回密码都是 401；`localhost:9090` 连不上。

---

## 4. 为什么这样用

### 4.1 共享密钥、mTLS 还是签名 JWT：🤖 推荐，Christy 同意
| 选项 | 得到什么 | 代价 |
|---|---|---|
| **每个被调方一把共享密钥**（选了这个） | 知道调用方是谁，compose 和 kind 当天就能跑 | 泄露 = 那个服务敞开；换密钥有空档；密钥在网络上是明文 |
| mTLS | 身份 + 加密，请求里不带密钥 | CA、证书、续期、探针也要走 TLS：好几天的基础设施工作 |
| 调用方签发的短期 JWT | 会过期，不共享密钥 | 多一套密钥体系 |

生产环境一般是通过服务网格（Istio、Linkerd）上 mTLS，或者用 SPIFFE 身份。取舍写在 ADR-23 里。

### 4.2 每个被调方一把，而不是大家共用一把 🤖
只有一把的话，拿着它的任何服务都能调任何别的服务。每个被调方一把，delivery 的密钥就动不了钱。`check-config-keys.ps1` 发现某个 pod 拿着别的服务的密钥就报 FAIL；只有 store（三个都要调）拿着三把。

### 4.3 被拒时 store 怎么办 🤖
原则：**照"不知道结果"时的做法回答，再加一行告警。**
- bank 拒绝 → UNKNOWN，绝不当 REJECTED。REJECTED 会把付款标成 FAILED，Pay Now 就会用新键再扣一次；密钥配错是我们的错，不是买家的错。
- delivery 拒绝 → "稍后再试"，不是"太晚了"：包裹并没有被揽收。
- email 拒绝 → 密码不改。最坏的改法是"校验没成功但也没抛异常"，于是不用验证码就能改密码。

### 4.4 删掉，而不是加保护 🤖
bank 的 REST 转账 / 退款、email 的通知接口都没有调用方（钱走 gRPC，邮件走 RabbitMQ）。删掉的接口不会被攻击，也不用维护测试。每个都先写一条红灯测试证明它真的能调通，再删。

### 4.5 审查发现了什么
全绿以后，另开一个会话审查这个分支。有一条要紧：bank 的 **gRPC** 拒绝时 store 会打告警，bank 的 **REST**（开户、按邮箱查账号）拒绝时不会。密钥配错期间注册的用户没有银行账号，密钥修好以后还是付不了款。失败场景表里只有 gRPC 那一行（C3），所以红灯只测了 gRPC，代码只修了 gRPC，全都是绿的。补了一行 C3b，先写红灯测试（`1c85326`），再修（`e38b223`）。**测试全绿只能证明表里写的，证明不了表里漏的。**

---

## 5. 八股在这里

**结论**：这一步用的大多是 Web 安全的基本功，不是新框架：请求在哪里检查、拒绝对钱意味着什么、检查怎么做到"不认就拒"。

| 八股 | 在哪 | 这里的判断 |
|---|---|---|
| ➕ 常数时间比较 | [ServiceToken#matches](../../backend/bankService/src/main/java/com/comp5348/bank/config/ServiceToken.java) | `MessageDigest.isEqual` 而不是 `equals`，只泄露长度；密钥是 64 位随机十六进制。**不是密钥的值不需要** |
| ➕ servlet 过滤器 vs `@Component` 自动注册 | [delivery FilterConfig](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/FilterConfig.java) | `Filter` bean 会管所有路径；两个过滤器管两组路径 → `FilterRegistrationBean`。**一个过滤器真的要管全部路径时不需要** |
| ➕ gRPC 拦截器 | [ServiceTokenServerInterceptor](../../backend/bankService/src/main/java/com/comp5348/bank/grpc/ServiceTokenServerInterceptor.java)、`MetadataUtils.newAttachHeadersInterceptor` | gRPC 不经过 servlet 过滤器；包住整个服务，新方法也覆盖到 |
| ➕ 允许名单 vs 拒绝名单 | [email ServiceTokenFilter](../../backend/emailService/src/main/java/com/comp5348/email/config/ServiceTokenFilter.java) | 默认关；精确匹配，不用 `startsWith` |
| ➕ 配置错了就启动失败 | `ServiceToken` / `ServiceTokens#require` | 空密钥直接启动失败并点名键；挡得住"空"，挡不住"错" |
| 📒 C3 监控 / 埋点 | `ALERT bank_auth_failed`、`delivery_auth_failed`、`email_auth_failed` | 密钥错了不会像超时那样自己好；告警行写清楚该改哪个。不写邮箱 |
| ➕ NetworkPolicy 要靠网络插件 | [bank.yaml](../../k8s/apps/bank.yaml) | 对象只是声明；要确认真的执行了（kindnet ≥ v0.24） |

场景锚点：
- "集群里面谁都能调 bank 的 gRPC Transfer。我给每个被调方一把自己的密钥，由 gRPC 拦截器和 servlet 过滤器检查，没配就不启动。"
- "bank 拒绝 store 的密钥时，这笔付款仍然是'不知道'，用同一个键重试，绝不当成'被拒绝'，因为密钥错是我们的错。告警会说该去改哪把密钥。"

---

## 6. 面试追问链

1. **入口已经挡住这些路径了，为什么服务里还要再查？** → 入口只朝外。被攻破的 pod、compose 里的任何容器、本机进程都绕过它。纵深防御。
2. **为什么不用 mTLS？** → mTLS 还能加密、不用共享密钥，但要 CA、证书和续期，本地要好几天。共享密钥当天就让两个平台都有了身份检查；取舍写下来了（ADR-23）。
3. **密钥泄露了怎么办？** → 在换掉之前，那一个被调方是敞开的，其他的不受影响（每个被调方一把）。现在换密钥要两边都重启；被调方同时认"当前"和"上一把"就能消掉空档。
4. **为什么要常数时间比较？** → `equals` 在第一个不同处就停；测很多次回复时间可能一个字节一个字节猜出密钥。`MessageDigest.isEqual` 只泄露长度。
5. **怎么知道检查真的生效了？** → 红灯证明修之前请求真的成功了（钱动了）；修完后同样的请求得到 401 / `UNAUTHENTICATED`，余额不变；运行中的系统上手动请求，以及 pod 之间的连通性测试。

---

## 7. 英文说法

- "The gateway only keeps outsiders out. Inside the network, each service now checks a per-service secret, and won't start without one."
- "For money, a refused call isn't a failure. It stays unknown, is retried with the same key, and raises an alert."
- "Shared secrets were the practical choice; in production I'd move to mTLS through a service mesh."

---

## 8. 资料

- `MessageDigest.isEqual`（Java 6u17 起是常数时间）：<https://docs.oracle.com/en/java/javase/17/docs/api/java.base/java/security/MessageDigest.html>
- Spring Boot 注册 servlet 过滤器、`FilterRegistrationBean`：<https://docs.spring.io/spring-boot/how-to/webserver.html#howto.webserver.add-servlet-filter-listener>
- Spring Boot 环境变量的宽松绑定：<https://docs.spring.io/spring-boot/reference/features/external-config.html#features.external-config.typesafe-configuration-properties.relaxed-binding.environment-variables>
- gRPC 拦截器和 metadata：<https://grpc.io/docs/guides/interceptors/>、<https://grpc.io/docs/guides/metadata/>
- `RestTemplateBuilder` 怎么选 HTTP 客户端：<https://docs.spring.io/spring-boot/reference/io/rest-client.html#io.rest-client.clienthttprequestfactory>
- Kubernetes NetworkPolicy：<https://kubernetes.io/docs/concepts/services-networking/network-policies/>
- kind v0.24.0 发布说明（kindnet 支持 NetworkPolicy）：<https://github.com/kubernetes-sigs/kind/releases/tag/v0.24.0>

---

## 9. 自测题

1. 给 delivery 的 `ServiceTokenFilter` 加上 `@Component`，会坏什么？
<details><summary>答案</summary>
Spring Boot 会把它注册到所有路径，前端调的 `/api/deliveries/...`（带的是用户 token，不是服务密钥）全部 401。FilterRegistrationBean 把它限定在 `/internal/*`。
</details>

2. bank 回 `UNAUTHENTICATED`，store 为什么不能把付款标成 REJECTED？
<details><summary>答案</summary>
REJECTED 会把付款变成 FAILED，Pay Now 就会用新键再扣。钱确实没动，但密钥错是 store 的错：改对以后用同一个键重试是安全、也是对的。UNKNOWN + 告警守住了钱的规则（ADR-05）。
</details>

3. （真懂才答得出）测试全绿，审查还是找出了一个漏掉的告警。为什么测试没抓到？
<details><summary>答案</summary>
红灯测试是照着失败场景表写的，表里只有 bank gRPC 拒绝这一行（C3）。store 用 REST 调 bank（开户、按邮箱查账号）不在表里，就没有测试，代码也没改那里。测试证明的是表里要求的东西，看不出表漏了什么。审查把四条调用路径放在一起比，才发现有一条没有告警。
</details>

---

## 10. 预计回放时间

**45 分钟**：读 §1 和 §2（15 分钟）→ 跑红：`git checkout 9dad7eb`，然后 `./gradlew :bankService:test --tests '*BankGrpcAuthIntegrationTest'`，看不带 token 的转账真的成功了（5 分钟）→ `git checkout main` 跑同一个测试看绿（5 分钟）→ 跑 `./gradlew :storeService:test --tests '*BankServiceTokenIntegrationTest'`，看日志里的 `ALERT` 行（5 分钟）→ 自测题（15 分钟）。
