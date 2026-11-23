# ADR-14 · 这次设计不动的东西

[English](14-out-of-scope.md) | **中文**

**状态：** 已采纳

## 背景
这次重新设计的重点是事件和钱。课程版本里有些弱点和重点无关，有意留着没动。

## 决定
- **token 还是由 store 校验。** delivery、tracking 回调 store 校验用户的 token（ADR-23 之后 bank 不再收用户 token）。
- **邮件还是走 RabbitMQ**，不在业务事务里。
- **订单和库存留在同一个服务**（store）。

## 代价与影响
- store 挂了，其他服务也没法校验 token。
- 极少数情况下会丢一封邮件（比如发的那一刻 RabbitMQ 挂着）；store 只打日志、继续往下走。可以接受，因为邮件既不影响钱也不影响货。
- ADR-23 之后服务之间用共享密钥鉴权；用户登录、订单归属和管理员角色见 ADR-24。

## 代码在哪
- delivery [TokenAuthenticationFilter.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/TokenAuthenticationFilter.java)、tracking [auth.go](../../backend/trackingService/internal/auth/auth.go)
- store [EmailAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/EmailAdapter.java)
