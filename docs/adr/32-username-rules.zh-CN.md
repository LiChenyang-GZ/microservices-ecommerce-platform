# ADR-32 · 用户名是 3-32 个字母、数字、点、下划线或连字符

[English](32-username-rules.md) | **中文**

**状态：** 已采纳。修订 ADR-31（后果里"用户名长得像别人的邮箱"那条）。

## 背景
注册时查邮箱格式，用户名除了"没被占用"（区分大小写）什么都不查。用户名可以带 `@`、空格、前后空白、任何 Unicode 字母、控制字符，也可以有几百个字符。

最要紧的是 `@`。登录，以及其他"邮箱或用户名"都收的查询，先按邮箱找、再按用户名找。ADR-31 把邮箱那一步归一以后，用户名 `Bob@X.com` 会先找到邮箱是 `bob@x.com` 的账号：这个人用用户名登录不了，失败还算在别人的登录额度上（审查 75）。长得像邮箱的用户名，在显示名字的地方还能冒充别人；抢先把 `ADMIN_EMAIL` 注册成用户名，也曾让 store 起不来（审查 47）。

选方案之前查了本机库：authcheck 42 个账号、shop 27 个，用户名带 `@` 的只有两个启动时建的管理员，用的就是他们自己的邮箱；没有普通用户的用户名不符合下面的规则。

## 决定（Christy 定：方案 B）

- **注册时用户名先 trim，再要求 `^[A-Za-z0-9._-]{3,32}$`**，否则 400 "Username must be 3-32 characters: letters, digits, dot, underscore or hyphen"。`AccountService#createAccount` 在邮箱格式校验之后、**查邮箱是否存在之前**检查，所以用户名不合规的请求打听不到邮箱的状态，也不会调 bank。查重和存储都用 trim 之后的名字。
- **登录时用户名那一步同样 trim**（`AccountService#byUsername`），用 `"  bob "` 注册（存成 `bob`）的人，带不带空格都能登录（审查 79）。
- **注册页在发请求前用同一条规则、同一句提示**，发出去的也是 trim 过的名字。页面只去 Java `String#trim()` 会去的字符（≤ U+0020）：JavaScript 的 `trim()` 还会去全角空格、NBSP，页面就会放行一个 store 要拒的名字（审查 79）。
- 只管注册。老账号的用户名不动；`AdminBootstrap` 照样把第一个管理员的用户名设成他自己的邮箱（不走注册），登录按邮箱也找得到他。
- 没选：只禁 `@`（方案 A：空格、长得一样的字母、控制字符还在）；再把登录改成"带 `@` 的只当邮箱"（方案 C：注册禁了 `@` 以后，只影响那两个管理员，而他们的用户名就是自己的邮箱）。

## 后果
- 新用户名不可能等于任何邮箱（合格的邮箱都有 `@`），登录不会再把两者认错；注册也占不了 `ADMIN_EMAIL`。`AdminBootstrap` 的 `admin-xxxxxxxx` 退路为老数据留着。
- 没有空格、长得一样的字母（`аdmin` 里的西里尔 `а`）、控制字符、超长的名字。代价：不能用中文等非拉丁文字做用户名。
- 用户名仍然区分大小写（`Bob` 和 `bob` 是两个人），也没有保留名（`admin`、`support` 都能注册；在配送单的收件人名上看起来像工作人员，但没有任何角色：角色在 `role` 列和令牌里）。
- 两个请求同时注册同一个用户名：唯一约束只留一个，第二个是 500，不是 "Username already exists!"（和 ADR-31 里同邮箱并发是同一个老问题）。
- 用"邮箱 `@` 前面那段"当用户名的脚本，碰到 `a+b@x.com` 会失败（`+`、`%` 在邮箱里合法，用户名里不合法）。现在的脚本用的都是普通前缀，能过。
- 以后如果加"改用户名"的接口，要用同一条规则。
- 规则那句话写了四份（store、页面、两个测试），改的时候一起改。

## 代码在哪
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)（`USERNAME_FORMAT`、`USERNAME_RULE`、`createAccount`、`byUsername`）、[AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java)（`freeUsername` 的注释）
- 前端 `RegisterPage.jsx`（`trimmedUsername`、`validateForm`）
- 测试：store `UsernameRulesIntegrationTest`、`AdminBootstrapStartupTest#signingUpWithTheAdminEmailAsUsername_isRefused`；前端 `RegisterPage.test.jsx`
- [docs/learning/19-username-rules.zh-CN.md](../learning/19-username-rules.zh-CN.md)
