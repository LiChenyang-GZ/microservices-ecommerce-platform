# ADR-27 · 没激活的账号不占邮箱：找回密码就是认领

[English](27-password-reset-claims-unverified-account.md) | **中文**

**状态：** 已采纳，由 30 补充（认领不会再被"把重置码猜死"堵住）。修订 ADR-25（"没激活的账号占着邮箱"）和 ADR-26（"别人还是能先用你的邮箱注册"那条后果）。

## 背景
注册会在证明邮箱之前就建好账号（ADR-25）。所以任何人都能用别人的邮箱、自己的密码注册。他激活不了，但这一行会一直占着这个邮箱：
- 主人注册时回 "Email already exists!"；
- 找回密码能改密码，但改完账号还是没激活；
- 登录一直回 "Please verify your email first"，没有任何地方能去验证页。

ADR-26 的后果里说主人"现在能到验证页"，其实到不了：注册页只有注册成功后才会跳过去。

## 决定

### 找回密码就是认领（Christy 定的）
- `reset-password` 的码对了，账号又从没验证过，store 就顺带把它标成验证过、激活，注册时没开成的银行账户也补开。重置码只会进主人的信箱，所以它和注册码一样能证明邮箱。
- 密码是主人刚设的，先注册的人就再也不能用他的密码登录了。
- 激活和认领共用一步（`AccountService#markEmailProved`）。

### 注册时告诉用户怎么认领
- 已存在但从没验证过的邮箱，回 400，带 `emailNotVerified: true` 和 "This email is registered but not verified. If it is yours, reset the password to claim it."，注册页显示一个去找回密码的链接。验证过的邮箱还是回 "Email already exists!"。
- 这会说出"这个邮箱注册了但没验证"。登录接口本来就会说（"Please verify your email first"）。

### 找回密码页不把主人卡住
`forgot-password` 回 429（最近发过码，可能是抢注的人要的）时，现在也跳到重置页，带上邮箱和提示：信箱里最新的那封码还能用。

### 没选的
| 选项 | 为什么不选 |
|---|---|
| 注册时覆盖没激活的账号 | 有竞态：主人注册、收到码；激活之前抢注的人再注册一次（不要码）；主人用手里的码激活，密码却是抢注的人的 |
| 没激活的账号 24 小时后删掉 | 主人最多被挡一天，而且脚本能在邮箱空出来的那一刻再注册 |
| 证明邮箱之前不建账号（待验证表） | 最干净，但注册、激活、开户、页面都要改，待验证表也有同样的竞态 |

### 验证页只给刚注册的人
`activate` 不设密码：账号用的是注册时填的那个。这之所以安全，是因为只有刚注册的人才到得了验证页。没激活的账号不能再有别的路去验证页（比如登录页加个"去验证"按钮）：主人会激活抢注的人设的密码。这种用户要引到找回密码。`AccountService#activateAccount` 的注释写了这一点。

## 代价与影响
- **用户名还是抢注的人起的。** 改用户名是另一个功能。
- **认领还是能被拖住。** 就算只用一个地址，也能先用完这个邮箱 15 分钟 3 封信的额度，再把唯一活着的码猜死（猜错 5 次作废，不管是谁猜的；ADR-25），主人手里的码就全是死的。后续是按"邮箱 + 客户端地址"数猜错次数（别人的错猜不杀你的码）。
- **注册还是不限次数**，没激活的账号也从不清理。
- **邮箱大小写没归一**：`Victim@x.com` 和 `victim@x.com` 是两个账号。单独一项。
- 认领在 `resetPassword` 的事务里做，要补开银行账户时，bank 慢就会一直占着一个数据库连接。只有注册时没连上 bank 才会走到，少见。
- `activateAccount` 先读账号、等 email、再存整行；中间要是有一次认领提交了，会被旧密码覆盖回去。这要同时用一个注册码和一个重置码，抢注的人安排不了。写在注释里。

## 代码位置
- store [AccountService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/AccountService.java)（`resetPassword`、`markEmailProved`、`createAccount`、`EmailNotVerifiedException`、`activateAccount`）、[AccountController.java](../../backend/storeService/src/main/java/comp5348/storeservice/controller/AccountController.java)（`createAccount`、`CreateAccountResponse.emailNotVerified`）
- 前端 [RegisterPage.jsx](../../frontend/src/pages/RegisterPage.jsx)、[ForgotPasswordPage.jsx](../../frontend/src/pages/ForgotPasswordPage.jsx)、[ResetPasswordPage.jsx](../../frontend/src/pages/ResetPasswordPage.jsx)
- 测试：store `ClaimByPasswordResetIntegrationTest`；前端 `RegisterPage.test.jsx`、`ForgotPasswordPage.test.jsx`
- [docs/learning/14-claim-by-password-reset.zh-CN.md](../learning/14-claim-by-password-reset.zh-CN.md)
