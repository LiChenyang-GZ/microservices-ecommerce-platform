# ADR-24 · store 要登录，用户只能动自己的订单，管理接口要管理员角色

[English](24-login-ownership-admin-role.md) | **中文**

**状态：** 已采纳，由 25 补充（激活要邮件里的验证码）

## 背景
ADR-23 之后，服务之间不带密钥已经调不通了，但 store 的安全规则最后一行还是 `anyRequest().permitAll()`。它的 JWT 过滤器在 token 有效时把邮箱放进请求，无效时照样放行。只有 Pay Now 自己查了订单是谁的。所以不带 token、或者带着别人的 token，任何人都能：

- 取消别人已付款的订单（store 接着让 delivery 停下、写退款任务）；
- 看别人的订单、付款和地址；
- 用别人的名义下单（请求体里的 `userId` 直接信），钱从那个人的银行账号扣；
- 给任何已付款的订单申请退款，已经送达的也行。

还有一批**没有任何调用方**、也不查任何东西的接口：`payment-success` 不经过 bank 就把订单改成 PAID，其他的能把订单改成任意状态、建一笔任意金额的付款、把锁住的库存还回去、按 id 读任何订单和付款。入口的允许名单（ADR-15）从外面挡住了大部分，网络里面都是开着的。

也没有"管理员"这个概念。账号没有角色，JWT 里只有 `userId` 和邮箱，所以管理页、改仓库和价格、退款、delivery 的死信接口，任何登录用户都能用。

这份记录覆盖两个 PR：登录和归属（#28），然后是管理员角色（#29）。

## 决定

### 登录
- store 的路径都要有效的用户 token，除了：注册、登录、激活、找回 / 重置密码、`validate-token`（delivery 和 tracking 调它）、商品目录（`GET /api/products`、`/api/products/*`、`/api/products/search/price`）和 `/actuator/health/**`。其他路径没有有效 token → **401**（`HttpStatusEntryPoint`；不配它，Spring 对匿名请求回 403）。
- 坏 token、过期 token **不在过滤器里拦**：请求当成匿名。要登录的路径照样 401，浏览器里留着旧 token 时商品目录也能看。否则前端的 401 处理会因为看商品就把访客送去登录页。
- 过滤器在一个 `try` 里只解析一次 token，解析本身就同时检查签名和过期。

### 归属
- 在 **service 层**、集中在一个地方查：`OrderProductService#ownOrder` 读出订单，比对它的 `userId` 和登录的账号。查单、取消、Pay Now 都走它。
- **别人的订单回 404，和不存在一样。** 订单号是连续的，回 403 等于告诉陌生人"这个号有订单，只是不是你的"。别人的订单**列表**回 403：`userId` 是调用方自己填的，说"不是你的"不泄露任何东西。管理员在这里也不例外，管理员看全站订单走 `GET /api/orders`。
- `create-with-payment` 的 `userId` 取自 token，请求体里的值**忽略**而不是拒绝，前端不用改。
- `OrderService#cancelOrder` 拿到行锁之后重新读一次订单（`entityManager.refresh`）。归属检查已经把订单读进了同一个事务，Hibernate 的加锁查询会直接返回那份已经读过的实例，双击取消、或者中间来了一条 delivery 事件时，判断用的是旧状态。主人不会变，所以锁之前查归属没问题；状态会变，所以锁之后再读。

### 管理员角色
- `Account.role` 是 `USER` 或 `ADMIN`。Hibernate 把这一列加成可空；`AccountRoleUpgrade` 启动时把老行设成 `USER`（ADR-21 的做法），`Account#getRole` 读到 NULL 也当 USER。
- JWT 带 `role` claim，登录和 `validate-token` 的回复里也有。**没有角色或不认识的角色都是 USER，永远不是 ADMIN**：角色上线前签的 token 没有这个 claim。用户自己写的 `role: ADMIN` 过不了签名检查，请求就是匿名的。
- **每次请求的角色都从 token 里读，不查数据库。** 代价见"代价与影响"。
- 只给管理员：`GET /api/orders`、`/api/warehouses` 下的所有接口、商品的新建 / 修改 / 分配仓库、`/api/products/{id}/inventory-records`（不限 HTTP 方法：HEAD 会走 GET 的处理器），以及退款。
- **退款只给管理员（决定 4）**，任何订单都行。用户拿回钱的办法是取件前取消。主人在送达后退款，等于白拿货。付款不是 SUCCESS 时退款回 409 并说明付款状态，不再回"Refund requested"。订单页没有退款按钮，入口也不再转发这个接口。
- delivery 的死信接口只给管理员。controller 标了 `@AdminOnly`，一个 `HandlerInterceptor` **在 Spring 选好 handler 之后**按 store `validate-token` 回的角色检查。之前用过滤器拿原始 URI 比 `/api/admin/` 前缀，被 `/api/admin;x/...`、`/api/%61dmin/...` 绕过了：Spring MVC 按解码、去掉 `;参数` 以后的路径路由，过滤器看到的是原始文本。
- **第一个管理员**在启动时按 `ADMIN_EMAIL` / `ADMIN_PASSWORD` 建（BCrypt、已激活）。没配就不建，打 WARN，store 照常启动。已经有这个邮箱的普通账号**不提升**：激活还不查验证码，任何人都可能抢先注册这个邮箱。如果这个邮箱已经是别人的用户名，管理员改用 `admin-xxxxxxxx`，而不是撞唯一约束让 store 起不来。

### 没人调用的接口
删掉，不加保护（决定 3）：`payment-success`、`payment-failure`、`PUT /api/orders/{id}/status`、`PUT /api/orders/{id}/cancel`、`POST /api/orders`、`POST /api/payments`、`GET /api/orders/{id}`、`/api/orders/user/{userId}`、`/api/orders/status/{status}`、`/api/orders/{id}/inventory-records`、`GET /api/payments/order/{orderId}`、`/api/payments/{paymentId}`、`PUT /api/warehouses/unhold`、一个会锁库存的 `GET /api/warehouses/available/...`、两个按订单读库存记录的接口、`check-email`、`check-verified`，以及 delivery 的 `GET /api/deliveries/all/{email}`。

### 途中定下的小事
- `BankAdapter#getAccountByOwnerEmail` 把邮箱作为严格编码的 URI 变量放进 URL（`/` → `%2F`、`;` → `%3B`、`+` → `%2B`）。原样拼进去时，`x/../victim@…` 或 `victim@…;x` 到了 bank 就成了查受害人的账号，攻击者之后下单扣的是受害人的钱。注册时邮箱里有 `A-Z a-z 0-9 . _ % + -` 以外的字符也直接拒绝。

## 代价与影响
- **改了角色，下次登录才生效。** 在数据库里提升、降级、停用一个人，对已经签出去的 token 没有作用，它最多还有效 24 小时（`jwt.expiration`）。换来的是：每次请求不用查库，delivery 也能直接信 `validate-token`，不用自己存一份账号。要堵这个空档，就得每次请求查库里的角色，或者做 token 黑名单。
- **"登录了"本身还很弱。** `POST /api/user/activate` 只要邮箱，不查验证码（场景 A11），所以任何人都能用任何地址注册并激活。归属检查仍然成立（用户只看得到自己建的东西），但邮箱地址证明不了什么。这是下一个要修的，和直接调激活接口的脚本一起改。已由 ADR-25 修复。
- **管理员退款不会取消订单和配送。** 订单可能还是 PAID 或在配送中，付款却已经 REFUNDED。售后流程（送达后申请、管理员批准）以后再做。现在没有页面调退款接口。
- **管理页依赖配置。** 没配 `ADMIN_EMAIL` / `ADMIN_PASSWORD` 就没有管理员。之后改 `ADMIN_PASSWORD` 不会改已有管理员的密码，这两个变量只用来建第一个。
- 前端对非管理员藏起管理页，只是体验。改了本地保存的角色的用户，只会看到一个每个请求都 403 的空管理页。
- "别人的订单回 404"只用在单个订单上。列表和管理接口回 403，两个状态码的意思是故意不同的。
- store 的路径规则和 delivery 的 `@AdminOnly` 是两套机制。store 新加管理接口要加进 `SecurityConfig` 和 `PathAccessRegressionTest`；delivery 新加的要标注解。

## 代码在哪
- store [SecurityConfig.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/SecurityConfig.java)、[JwtAuthenticationFilter.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/JwtAuthenticationFilter.java)、[JwtUtil.java](../../backend/storeService/src/main/java/comp5348/storeservice/utils/JwtUtil.java)、[Role.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/Role.java)、[AccountRoleUpgrade.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AccountRoleUpgrade.java)、[AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java)
- store 归属：[OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java)（`ownOrder`、`cancelOwnOrder`、`createOrderWithPaymentFor`）、[OrderService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java)（`cancelOrder`）、[OrderNotFoundException.java](../../backend/storeService/src/main/java/comp5348/storeservice/exception/OrderNotFoundException.java)、[NotYourOrdersException.java](../../backend/storeService/src/main/java/comp5348/storeservice/exception/NotYourOrdersException.java)、[PaymentController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/PaymentController.java)
- store 邮箱：[BankAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java)、[AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)
- delivery [AdminOnly.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnly.java)、[AdminOnlyInterceptor.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/AdminOnlyInterceptor.java)、[TokenAuthenticationFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/TokenAuthenticationFilter.java)、[TokenValidationClient.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/utils/TokenValidationClient.java)
- 前端 [AuthContext.js](../../frontend/src/contexts/AuthContext.js)、[AdminDashboard.jsx](../../frontend/src/pages/admin/AdminDashboard.jsx)、[adminService.js](../../frontend/src/services/adminService.js)
- 部署：[compose.yml](../../backend/compose.yml)、[.env.example](../../backend/.env.example)、[apply-secrets.ps1](../../k8s/scripts/apply-secrets.ps1)、[check-entry.ps1](../../k8s/scripts/check-entry.ps1)
- 测试：store `StoreAuthorizationIntegrationTest`、`OldStoreEndpointsRemovedTest`、`CancelReadsLockedRowIntegrationTest`、`AdminRoleIntegrationTest`、`PathAccessRegressionTest`、`AdminBootstrapStartupTest`、`BankOwnerLookupEncodingIntegrationTest`、`RegisterEmailFormatIntegrationTest`；delivery `DlqAdminRoleIntegrationTest`、`OldDeliveryEndpointsRemovedTest`；前端 `OrderDetailPage.test.jsx`、`AdminDashboard.test.jsx`
- [docs/learning/11-login-ownership-admin-role.zh-CN.md](../learning/11-login-ownership-admin-role.zh-CN.md)
