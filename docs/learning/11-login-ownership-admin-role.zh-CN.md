# 11 · 登录、订单归属和管理员角色（鉴权 PR ②、③）

[English](11-login-ownership-admin-role.md) | **中文**

> 提交。PR #28（登录和归属）：红灯 `89ff488` → 绿灯 `8971b27`、`91d73c6`；审查后红灯 `ca8281a` → 绿灯 `f034e49`。PR #29（管理员角色）：红灯 `5676dbb` → 绿灯 `cf39a3d`、`322d228`；审查后红灯 `1198016` → 绿灯 `20a0cf2`。
> 怎么发现的：第 1 波审查的待办和鉴权失败场景表。2026-10 修别的问题时，store 的退款接口真的给别人一个已送达的订单退了款。
> 重放时间：**50 分钟**
> 前置：[10 · 服务间凭据](10-service-credentials.zh-CN.md)（服务之间互相证明身份；这一篇讲的是用户）。

---

## 1. 问题

**store 让任何人对任何人的订单做任何事。** 安全规则最后一行是 `anyRequest().permitAll()`，JWT 过滤器遇到坏 token 照样放行，只有 Pay Now 查了订单是谁的。不带 token、带过期的、伪造的、或者别人的 token，都能取消别人已付款的订单、看别人的订单、用别人的名义下单（请求体里的 `userId` 直接信）、给任何已付款的订单申请退款。没有调用方的接口还能不经过 bank 把订单改成 PAID、建一笔任意金额的付款、把锁住的库存还回去。

也没有管理员。管理页、改价格改库存、退款、delivery 的死信接口，任何登录用户都能用。

修之前证明请求**真的成功了**的测试（红灯）：
- [StoreAuthorizationIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/StoreAuthorizationIntegrationTest.java)：不带 / 过期 / 伪造的 token 取消订单（200，状态真的变 CANCELLED）；陌生人取消已付款的单，真的去叫了 delivery；用别人的 `userId` 下单，订单记在受害人名下
- [OldStoreEndpointsRemovedTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/OldStoreEndpointsRemovedTest.java)：没付钱的订单真的变 PROCESSING；真的建了一笔 0.01 元的付款；锁住的库存真的回来了
- [AdminRoleIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/AdminRoleIntegrationTest.java)：普通用户真的改了价格、改了库存、删了仓库；订单主人退款真的进了 REFUND_PENDING
- [DlqAdminRoleIntegrationTest.java](../../backend/deliveryService/src/test/java/com/comp5348/delivery/events/DlqAdminRoleIntegrationTest.java)：普通用户处理死信（真的被标成已处理）
- [CancelReadsLockedRowIntegrationTest.java](../../backend/storeService/src/test/java/comp5348/storeservice/auth/CancelReadsLockedRowIntegrationTest.java)（审查发现的）：取消用了旧的读取结果，一个 DELIVERED 的订单被取消了

---

## 2. 是什么（框架层面）

### 2.1 Spring Security：谁来决定 401 和 403
每个请求在到 controller 之前都要过 `SecurityFilterChain`。这里有三块：
- **我们的过滤器**（[JwtAuthenticationFilter](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java)）只回答*你是谁*：token 解析成功，就往 `SecurityContext` 放一个带 `ROLE_USER` 或 `ROLE_ADMIN` 权限的 `Authentication`；解析失败，什么都不做。
- **规则**（[SecurityConfig#filterChain](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java)）回答*每个路径要什么*：`permitAll()`、`authenticated()` 或 `hasRole("ADMIN")`。从上往下匹配，第一条命中的算数，所以管理员规则放在最前面。
- **规则不通过时回什么**：匿名请求 → `AuthenticationEntryPoint`（我们设成 `HttpStatusEntryPoint(401)`；无状态 API 不配表单登录时，Spring 默认回 403，会让前端"重新登录"的处理失效）。登录了但角色不够 → `AccessDeniedHandler` → 403。

为什么过滤器自己不拦坏 token：商品目录是公开的。如果过滤器对过期 token 回 401，浏览器里留着旧 token 的访客看个手机就会被送去登录页。把请求留成匿名，让*规则*来决定：公开路径照常用，要登录的路径 401。

### 2.2 归属检查放在 service 层
安全链只知道路径和角色。"42 号订单是不是你的"要查数据库，所以放在读订单的地方：[OrderProductService#ownOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java)。新加的接口只要读或改一个订单，就调它；service 方法要求传入用户的邮箱，controller 不会不小心漏掉。

**别人的订单回 404，不是 403。** 订单号是递增的。403 等于说"1043 号订单存在，只是不是你的"，谁都能借此数订单、试探 id。"不存在"和"不是你的"都抛 [OrderNotFoundException](../../backend/storeService/src/main/java/comp5348/storeservice/exception/OrderNotFoundException.java)，消息一样。查某个用户的订单*列表*时，`userId` 是调用方自己给的，回 403 不泄露新东西（[NotYourOrdersException](../../backend/storeService/src/main/java/comp5348/storeservice/exception/NotYourOrdersException.java)）。

**身份永远不从请求体里拿。** `create-with-payment` 以前从 JSON 里读 `userId`。现在 [OrderProductService#createOrderWithPaymentFor](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) 用 token 对应的账号覆盖它。不一致时忽略而不是拒绝，前端一行都不用改。

### 2.3 JPA 的坑：加锁查询不会刷新你已经读过的东西
PR ② 的审查发现的。`cancelOwnOrder` 先调 `ownOrder`（把 `Order` 读进事务的持久化上下文），再由 `OrderService#cancelOrder` 调 `findByIdForUpdate`。Hibernate 确实发了 `SELECT … FOR UPDATE`、确实拿到了锁，但发现这个 id 已经在持久化上下文里，就**直接返回手上那个实例**，不把数据库里更新的列值拷进去。所以取消判断用的是加锁之前的状态。

确定性的测试不用起两个线程：在一个事务里读订单；在另一个事务（`REQUIRES_NEW`）里把它改成 DELIVERED 并提交；再取消。修之前取消成功了。修法是加锁之后一行：`entityManager.refresh(order)`。代码里的规则 1（"先锁行、再读"）本身是对的，是同一个事务里在锁*之前*先读了一次，悄悄破坏了它。

### 2.4 JWT 里的角色
角色是 token 里的一个 claim：[JwtUtil#generateToken](../../backend/storeService/src/main/java/comp5348/storeservice/utils/JwtUtil.java) 加上 `role`，过滤器把它变成 `ROLE_ADMIN` 或 `ROLE_USER`。三条规则：
- **没有就是 USER。** 改动之前签的 token 没有这个 claim，还能用 24 小时。[Role#of](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java) 只有完全等于 `ADMIN` 的字符串才返回 ADMIN。
- **用户自己写不了。** token 用 store 的密钥做 HMAC 签名；内容写着 `role: ADMIN`、但用别的密钥签的 token 解析失败，请求就是匿名的。
- **从 token 里读，不查数据库。** 每次请求不用查库，delivery 也能直接信 `validate-token` 的回答。代价：在数据库里改了角色，要等下次登录才生效。

这一列本身：`ddl-auto=update` 在已有的库上把 `account.role` 加成可空，老行是 NULL。[AccountRoleUpgrade](../../backend/storeService/src/main/java/comp5348/storeservice/config/AccountRoleUpgrade.java) 启动时跑 `UPDATE account SET role='USER' WHERE role IS NULL`（ADR-21）。在 compose 的库上它补了 19 个真实账号。

### 2.5 在过滤器里按路径判断 vs. 在路由之后判断
delivery 的第一版在 token 过滤器里判断 `request.getRequestURI().startsWith("/api/admin/")`。审查找到两种绕法：`/api/admin;x/dlq/delivery` 和 `/api/%61dmin/dlq/delivery`。
- Tomcat 和 Spring MVC 按**解码、去掉 `;参数` 以后**的路径路由：`admin;x` → `admin`，`%61dmin` → `admin`。请求到了 `DeliveryDeadLetterController`。
- `getRequestURI()` 返回的是**原始**文本，不是 `/api/admin/` 开头。检查被跳过了。

store 不受影响：Spring Security 的 `StrictHttpFirewall` 在任何规则之前就拒绝 `;` 和编码字符。delivery 没装 Spring Security。修法换了一个没有这种缝隙的问题：*Spring 选中的那个 handler 是不是只给管理员？* controller 标 [@AdminOnly](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnly.java)，一个 `HandlerInterceptor`（[AdminOnlyInterceptor](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java)）在路由之后检查。在真 Tomcat 上验证过：三种写法普通用户都 403；管理员用 `;x` 写法 200，说明这种写法确实路由到了 DLQ。

store 里一个小号的同类问题：只写了 `GET` 的规则匹配不到 `HEAD`，但 Spring MVC 用 GET 的处理器响应 HEAD。`GET /api/orders` 只给管理员，`HEAD /api/orders` 却对任何人跑了一次全表查询。现在管理员规则不限方法。

### 2.6 把一个值编码进 URL 路径
`BankAdapter#getAccountByOwnerEmail` 原来拼的是 `bankUrl + "/by-owner/" + email`。邮箱写成 `x/../victim@…` 就多出两段路径，bank 的 Tomcat 把 `..` 规范化掉：请求变成查受害人的账号，store 把它存成了攻击者的。Spring 的两个细节：
- `UriComponentsBuilder.pathSegment(value).encode()` 只编码在路径段里非法的字符，`;`、`+`、`=` 原样保留，`{b}` 还会被当成模板变量。在 `encode()` **之后展开 URI 变量**（`path("/by-owner/{email}").encode().buildAndExpand(email)`）才是严格编码。
- 交给 `RestTemplate` 的要是 `URI`，不是 `String`；给 `String` 会再编码一次（`%2F` → `%252F`）。

---

## 3. 请求路径

**用户取消别人已付款的订单**
1. `JwtAuthenticationFilter` 解析 token → `Authentication(用户 B, ROLE_USER)`。
2. `SecurityConfig`：`POST /api/store/orders/{id}/cancel` 要 `authenticated()` → 通过。
3. `StoreController#cancelOrder` → `OrderProductService#cancelOwnOrder` → `ownOrder` 发现订单是 A 的 → `OrderNotFoundException` → **404**。没问 delivery、没写退款、订单也没被锁。

**普通用户想调管理员退款**
1. 过滤器 → `ROLE_USER`。2. 规则 `POST /api/payments/*/refund → hasRole("ADMIN")` 不通过 → **403**，到不了 controller。

**管理员退款**
1. 过滤器 → `ROLE_ADMIN`（来自 token）。2. 规则通过。3. 付款是 SUCCESS 时 `PaymentService#requestRefund` 写 REFUND 任务 → 200；否则 409"Nothing to refund: the payment is PENDING"。

运行中系统的证据（compose `-p authcheck`、kind）：用户拿到的 401 / 403 / 404 和上面一致；管理员退款后付款真的变成 REFUNDED；smoke 和场景 01、02、03、06、07、08 通过；`check-entry.ps1` 两个入口 54 / 54，其中 14 行要求不带 token 必须 401。

---

## 4. 为什么这样做

### 4.1 别人的订单回 404 还是 403：AI 建议，Christy 同意 🤖
404 不暴露 id 存不存在；403 对迷路的真实用户更诚实。订单号是连续的，存在与否值得藏。列表用 403，因为调用方已经点名了用户。

### 4.2 角色放在 token 里，不每次查库 🤖
| 选项 | 得到 | 代价 |
|---|---|---|
| **放在 JWT 的 claim 里**（选了） | 每次请求不查库；delivery 直接信 `validate-token` | 改角色要等下次登录（≤ 24 小时） |
| 每次请求查账号 | 改了马上生效 | 每次请求一次查询；delivery 每个请求都得找 store |
| 短 token + refresh token | 几分钟内生效 | 前端要做刷新流程 |

### 4.3 退款只给管理员（决定 4，Christy 定）
Christy 指出取消和退款是两个意思：取消是"发货前不要了"，退款是售后。送达后还能用的用户退款按钮，等于白拿货的按钮。真正的售后流程（申请、管理员批准）以后再做；在那之前只有管理员能退款。

### 4.4 第一个管理员来自配置，绝不提升已有账号 🤖
注册是开放的，激活也不查验证码（A11），所以"管理员邮箱对应的那个账号"可能是任何人的。AdminBootstrap 只在邮箱没人用时建管理员，从不提升已有账号。后来又发现：这个邮箱还可能是别人的*用户名*，撞唯一约束会让 store 起不来；这时管理员改用 `admin-xxxxxxxx`。

### 4.5 审查发现了什么
PR ② 的审查：取消用了旧的读取结果（§2.3），登录用户还能给任何订单退款，`;` 和 `{` 没编码，公开规则 `/api/products/**` 顺带公开了出库记录。PR ③ 的审查：delivery 的前缀判断能被绕过（§2.5），管理员邮箱是别人的用户名时 store 起不来，管理员读接口漏了 HEAD，没东西可退时退款照样说"成功"。每一条都先写了红灯测试；旧读取和绕过这两条审查只是推出来的，红灯测试证明了它们是真的。**审查找到失败场景表没问的东西；红灯测试证明审查不是在想象。**

---

## 5. 这里的八股

**结论**：认证大多是框架的事；授权是应用自己的事，bug 都出在层与层之间的缝里（过滤器 vs. 路由，读取 vs. 加锁，GET vs. HEAD）。

| 八股 | 在哪 | 这里怎么定的 |
|---|---|---|
| ➕ 认证 vs. 授权 | `JwtAuthenticationFilter`（谁）、`SecurityConfig`（要什么）、`ownOrder`（哪一单） | 三层，各查一件事 |
| ➕ 401 vs. 403、`AuthenticationEntryPoint` | [SecurityConfig](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java) | 匿名 → 401（要自己设，默认是 403）；角色不够 → 403 |
| ➕ IDOR（越权访问对象） | [OrderProductService#ownOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java) | 在读对象的地方查归属；别人的回 404 |
| ➕ JWT 里的 RBAC | [Role](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java)、`JwtUtil` | 没有角色 = 最小权限；改了下次登录生效 |
| ➕ JPA 持久化上下文和加锁 | [OrderService#cancelOrder](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java) | 加锁查询返回已加载的实例；锁后 `refresh` |
| ➕ 路径规范化绕过 | [AdminOnlyInterceptor](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java) | 在路由之后检查，不看原始 URI |
| ➕ URL 编码 / 路径穿越 | [BankAdapter#getAccountByOwnerEmail](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java) | 在 `encode()` 之后展开 URI 变量；传 `URI` |
| 📒 没人调用的就删 | `OldStoreEndpointsRemovedTest` | 先用红灯证明它现在能用，再删 |

场景锚点：
- "任何人都能取消、退款任何人的订单。我让 store 必须登录，在 service 层查订单归属，别人的订单回 404，让订单号没法被试探。"
- "审查发现我们的取消用的是旧状态：归属检查已经把订单读进来了，Hibernate 的加锁查询返回的就是那一份。一个确定性的测试证明了它，锁后一行 `refresh` 修好了。"
- "delivery 的管理员检查比的是原始 URL，`/api/admin;x/...` 从它旁边溜过去了，Spring 照样路由过去。我把检查挪到了路由之后。"

---

## 6. 追问链

1. **为什么不在 controller 里查归属？** → 每个新接口都得记得查。放在 service 里，读订单的方法本身就要求传入用户，不会被不小心跳过。
2. **为什么 404 不是 403？** → 订单号连续。403 等于确认订单存在，404 不会。列表用 403，因为调用方已经点名了用户。
3. **角色在 token 里，怎么立刻降级一个人？** → 现在做不到，要等下次登录（≤ 24 小时）。办法：每次请求查库里的角色、缩短 token 有效期加 refresh token、或者维护 token id 黑名单。
4. **旧读取是怎么发生的、怎么证明的？** → 归属检查加载了实体；加锁查询返回那个实例，不带新值。测试先读、再从另一个事务提交一个改动、最后取消；修之前它取消了一个 DELIVERED 的订单。
5. **为什么 store 没有 `;` 绕过？** → Spring Security 的 `StrictHttpFirewall` 在匹配之前就拒绝 `;` 和编码字符。delivery 没有 Spring Security，比的是原始 URI。

---

## 7. 怎么说

- "过滤器只说你是谁，规则说路径要什么，service 说这一单是不是你的。"
- "别人的订单回 404 不回 403：订单号是连续的，存在本身就是信息。"
- "权限要查在路由选中的那个 handler 上，不要查在客户端发来的 URL 文本上。"

---

## 8. 延伸阅读

- Spring Security，HTTP 请求的授权和 `ExceptionTranslationFilter`：<https://docs.spring.io/spring-security/reference/servlet/authorization/authorize-http-requests.html>、<https://docs.spring.io/spring-security/reference/servlet/architecture.html#servlet-exceptiontranslationfilter>
- `StrictHttpFirewall`：<https://docs.spring.io/spring-security/reference/servlet/exploits/firewall.html>
- OWASP，越权访问对象（IDOR）：<https://cheatsheetseries.owasp.org/cheatsheets/Insecure_Direct_Object_Reference_Prevention_Cheat_Sheet.html>
- Jakarta Persistence `EntityManager.refresh` 和悲观锁：<https://jakarta.ee/specifications/persistence/3.1/apidocs/jakarta.persistence/jakarta/persistence/entitymanager>
- Spring `UriComponentsBuilder` 的编码：<https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-uri-building.html#uri-encoding>
- Spring MVC `HandlerInterceptor`：<https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-config/interceptors.html>

---

## 9. 自测

1. 把 `SecurityConfig` 里的 `exceptionHandling(... HttpStatusEntryPoint(401))` 删掉，前端不带 token 会看到什么？
<details><summary>答案</summary>
403 而不是 401。前端的拦截器只在 401 时登出并跳转，所以 token 过期的用户会停在一个每个请求都报"禁止"的页面上。
</details>

2. `findByIdForUpdate` 已经跑了 `SELECT … FOR UPDATE`，为什么取消还要在锁后 `refresh`？
<details><summary>答案</summary>
归属检查在同一个事务里更早地加载了这个订单。Hibernate 在一个持久化上下文里对同一个 id 只保留一个实例；加锁查询返回这个实例，不用更新的行去覆盖它的字段。`refresh` 在拿到锁之后重新读这一行。
</details>

3. （真懂才答得出）store 和 delivery 都有按路径的管理员检查，为什么只有 delivery 的能被 `/api/admin;x/...` 绕过？
<details><summary>答案</summary>
store 跑的是 Spring Security：`StrictHttpFirewall` 拒绝 `;` 和编码字符，它的匹配器和 Spring MVC 用同一个解码后的路径。delivery 在普通的 servlet 过滤器里比 `getRequestURI()`（原始），而 Spring MVC 按解码、去掉 `;参数` 以后的路径路由。两层读路径的方式不一样，检查恰好放在了和路由意见不一致的那一层。
</details>

---

## 10. 重放时间

**50 分钟**：读 §1 和 §2（20 分钟）→ 跑红灯：`git checkout 89ff488`，然后 `./gradlew :storeService:test --tests '*StoreAuthorizationIntegrationTest'`，看陌生人取消已付款的订单（5 分钟）→ `git checkout ca8281a`，跑 `--tests '*CancelReadsLockedRowIntegrationTest'`，看一个 DELIVERED 的订单被取消（5 分钟）→ `git checkout main`，两个都跑绿（5 分钟）→ 自测（15 分钟）。
