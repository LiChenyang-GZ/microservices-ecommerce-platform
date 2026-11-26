# ADR-31 · 一个邮箱只有一种写法

[English](31-one-spelling-per-email.md) | **中文**

**状态：** 已采纳。修订 ADR-27（已知限制"邮箱大小写没有归一"）和 ADR-30（后果里"邮箱大小写打得和库里不一样"那一句）。

## 背景
store 查邮箱、存邮箱都按用户输的原样。Postgres 比较文本区分大小写，所以 `account.email` 上的唯一约束让 `Victim@x.com` 和 `victim@x.com` 成了两个账号，各开一个银行账户，两份验证码进同一个信箱。限流早就按"去空格、转小写"的邮箱数（ADR-26），账号表没有。

日常更常见的是一个小问题：**手机会把首字母自动大写。** 用 `victim@x.com` 注册的人，在手机上输成 `Victim@x.com`，`findByEmail` 找不到，登录退回按用户名找，回答"邮箱或密码错误"；"忘记密码"回答"邮箱不存在"。粘贴时前后带空格也一样，注册更是直接拒绝。

选方案之前查了本机的两套 compose 数据库：没有只差大小写的两个账号；store 的账号、delivery 的配送单、email 的验证码里存的邮箱全是小写、没有空格。这里没有要迁移的数据。

## 决定（Christy 定：方案 A）

- **`EmailAddress#normalize`**：先 `trim()`，再 `toLowerCase(Locale.ROOT)`；`null` 原样返回。用 `Locale.ROOT` 是因为土耳其语里 `"I".toLowerCase()` 是没有点的 `ı`。`AuthRateLimits` 改用同一个函数，计数和账号的写法不会再分叉。
- **两个卡口**，以后新加的调用方忘了也不会错：
  - 写：`Account#setEmail` 里归一。不管哪条路径存账号，这一列都只有一种写法，所以原来那个区分大小写的唯一约束就等于"一个信箱一个账号"；
  - 读：`AccountRepository#findByEmail` 先归一，再精确匹配存着的那一列（`findByStoredEmail`）。没人用、又能绕过它的 `findByEmailOrUsername` 删掉了。
- **store 往外传的也是归一后的。** email 按邮箱存验证码，所以 `AccountService` 在 `createAccount`、`activateAccount`、`sendVerificationCode`、`sendPasswordResetEmail`、`resetPassword` 开头各归一一次：码按一种写法存、按一种写法查，bank 也按这种写法开户。`createAccount` 先归一再做格式校验，所以前后带空格也能注册。
- `AdminBootstrap` 把 `ADMIN_EMAIL` 归一：配成 `Admin@Shop.com` 时会碰到已有的 `admin@shop.com`（和以前一样不提升），而不是给同一个信箱另建一个管理员账号。
- email、bank、delivery 不改：它们看到的邮箱都是 store 传过去的，现在已经归一了。
- 不改表结构、不自动迁移（方案 B、C 没选）：`lower(email)` 的函数索引需要 store 没有的 schema 管理（现在是 `ddl-auto=update`）；有重复时留哪个账号要人来定。

## 后果
- 一个信箱一个账号。抢注的人注册 `VICTIM@x.com`，占的就是 `victim@x.com` 那一行，主人会被告知去认领（ADR-27），而不是另得一个账号。
- 登录、要码、激活、重置密码，不管输什么大小写、前后有没有空格都能用。
- RFC 5321 规定 `@` 前面那段**可以**区分大小写。主流邮箱都不区分；真依赖大小写的地址会在小写写法下收信。几乎所有网站都是这个取舍。
- 用户名仍然区分大小写（`Bob` 和 `bob` 是两个人），这次不动。
- 数据库本身不保证写法：手工插进去的大写邮箱查不到。靠的是两个卡口。
- **用户名长得像别人的邮箱。** 登录和其他按"标识"查账号的地方先按邮箱找（现在归一了），再按用户名找（精确）。用户名是 `Bob@X.com`、而另一个账号的邮箱是 `bob@x.com` 时，现在会找到那个账号，用用户名登录不了（用自己的邮箱还能登），这次失败还算在那个账号的登录额度上。注册对用户名什么都收，包括 `@`；根治是用户名不许带 `@`，另一项。
- 两个请求同时用 `A@x.com` 和 `a@x.com` 注册，现在撞的是同一个唯一约束，和以前两个人用同一种写法同时注册一样：只有一个账号、一个银行账户（插入在调 bank 之前就失败了）。第二个请求得到 500，不是"Email already exists"：老问题，没变。
- Pay Now 按账号**库里**的邮箱去查银行账户，不按令牌里的（审查 75）：迁移前签的令牌可能是别的大小写，bank 精确匹配、查不到就开一个空户，会给这个用户第二个银行账户。

### 老数据：上线前先迁移
**库里有大小写混杂的邮箱时，不迁移就上线比不上线更糟。** 每种写法都先归一再查，存成 `Victim@x.com` 的那一行永远找不到：主人登录不了；而且任何人都能再注册一个 `victim@x.com`（没激活的新行），之后主人的登录和老令牌都落到这一行上（他激活不了，所以是把主人锁在外面，不是让他进来）。如果 `Victim@x.com` 和 `victim@x.com` 两行都在，前者的令牌会被解析成后者的账号。本机两套 compose 库是干净的；别的环境（包括 kind）先跑检查。

1. **检查**（都必须一行都没有）：
   ```sql
   -- store_db：一个信箱两个账号
   SELECT lower(btrim(email, E' \t\r\n')), count(*) FROM account
    WHERE email IS NOT NULL GROUP BY 1 HAVING count(*) > 1;
   -- bank_db：owner_name 没有唯一约束，也要查
   SELECT lower(btrim(owner_name, E' \t\r\n')), count(*) FROM bank_accounts
    WHERE owner_name LIKE '%@%' GROUP BY 1 HAVING count(*) > 1;
   ```
   给 `btrim` 写明要去的空白，是因为它默认只去空格，而 Java 的 `trim()` 还会去掉制表符和换行。
2. **每个重复组先手工处理完，再做任何更新。** 两个账号一般都是从同一个信箱激活的，属于同一个人。由人决定哪个账号保留这个邮箱；另一个要么停用（邮箱改成 `retired-<id>@invalid`，bank 户名同步改，两边都不会再被找到），要么合并（它的订单 `user_id` 改到保留的账号；第二个银行账户的余额怎么办是决定，不是脚本）。合并前看一下两边的配送单：更新以后，拿着保留邮箱的人都能看到。
3. **更新**，每个库各自一个事务，按这个顺序（三个库，放不进一个事务）：
   ```sql
   -- store_db（这里撞唯一约束，说明漏了重复组：停下来）
   UPDATE account SET email = lower(btrim(email, E' \t\r\n')) WHERE email <> lower(btrim(email, E' \t\r\n'));
   -- bank_db（只改是邮箱的户名；SYSTEM、Online Store 和种子客户不动）
   UPDATE bank_accounts SET owner_name = lower(btrim(owner_name, E' \t\r\n'))
    WHERE owner_name LIKE '%@%' AND owner_name <> lower(btrim(owner_name, E' \t\r\n'));
   -- delivery_db
   UPDATE delivery SET email = lower(btrim(email, E' \t\r\n')) WHERE email <> lower(btrim(email, E' \t\r\n'));
   ```
4. **核对**：不应该有不是"存储写法"的邮箱（`AccountService` 格式校验的小写版）；查出来的手工处理。
   ```sql
   SELECT id, email FROM account WHERE email !~ '^[a-z0-9._%+-]+@[a-z0-9.-]+\.[a-z]{2,}$';
   ```

bank 和 delivery 也要改，是因为 store 会按户主邮箱去 bank 查银行账户（账号上还没有账户号时），delivery 按登录令牌里的邮箱判断配送单是谁的。迁移前签发的令牌带的是老写法：store 用库里的写法（读的卡口；Pay Now 见上），delivery 却是精确比较，所以这些用户要重新登录一次。email 的验证码只活 5 分钟，不用管。

（以上 SQL 2026-11-26 在本机 authcheck 库上用 `BEGIN … ROLLBACK` 跑过：检查和核对都是 0 行，三条更新都是 0 行。）

## 代码在哪
- store [EmailAddress.java](../../backend/storeService/src/main/java/comp5348/storeservice/utils/EmailAddress.java)、[Account.java](../../backend/storeService/src/main/java/comp5348/storeservice/model/Account.java)（`setEmail`）、[AccountRepository.java](../../backend/storeService/src/main/java/comp5348/storeservice/repository/AccountRepository.java)（`findByEmail`、`findByStoredEmail`）、[AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)、[AdminBootstrap.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/AdminBootstrap.java)、[AuthRateLimits.java](../../backend/storeService/src/main/java/comp5348/storeservice/ratelimit/AuthRateLimits.java)（`fingerprint`）、[OrderProductService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderProductService.java)（`retryOrderPayment`）
- 测试：store `EmailAddressTest`、`EmailCaseIntegrationTest`、`ClaimByPasswordResetIntegrationTest`、`AdminBootstrapStartupTest`、`EmailLookupArchitectureTest`（只有 `AccountRepository` 能调 `findByStoredEmail`）
- [docs/learning/18-one-spelling-per-email.zh-CN.md](../learning/18-one-spelling-per-email.zh-CN.md)
