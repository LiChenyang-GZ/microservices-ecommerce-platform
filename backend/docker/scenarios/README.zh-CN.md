# 场景脚本

[English](README.md) | **中文**

像用户一样试跑起来的整套系统，之后再去查每个服务自己的数据库。每个脚本都会注册一个新用户，可以反复跑。

先把系统起起来，再在仓库根目录运行（PowerShell 7）：

```
docker compose -f backend/compose.yml up -d --wait
pwsh backend/docker/scenarios/<脚本>.ps1
```

| 脚本 | 用户做了什么 | 必须成立的结果 | 时间 |
|---|---|---|---|
| `01-paynow-twice.ps1` | 付款失败（钱不够），充值，然后在两个标签页同时点 Pay Now | 钱只扣一次；PAY 任务一共 2 条（被拒的那次 + 一次 Pay Now） | 约 20 秒 |
| `02-cancel-after-paid.ps1` | 付完款马上取消，配送单还没建 | 订单 CANCELLED，没建配送单，退款 1 笔，余额和库存回到原样 | 约 20 秒 |
| `02-cancel-after-paid.ps1 -AfterDelivery` | 付完款，等配送单建好，在取件前取消 | 同上，而且配送单变成 CANCELLED。配送单建好约 10 秒后就会取件，没赶上就再跑一次 | 约 25 秒 |
| `03-bank-down-during-payment.ps1` | bank 挂着时下单，结果未知时点 Pay Now，然后 bank 恢复 | 出现 `PAY_UNKNOWN` 和 `ALERT payment_unknown` 日志；Pay Now 不会开新的一次尝试；bank 恢复后用同一个键重试，钱只扣一次。脚本最后一定会把 bank 启动回来 | 约 11 分钟 |
| `04-kafka-down.ps1` | Kafka 挂着时下单，然后 Kafka 恢复 | 订单照常到 PAID，store / delivery 仍然健康；OrderPaid 留在 store 的 `event_outbox` 里；Kafka 回来后配送单建出来。脚本最后一定会把 Kafka 启动回来 | 约 1 分钟 |
| `05-rabbitmq-down-at-delivery.ps1` | RabbitMQ 挂着时下单（配送单正好这时建），然后 RabbitMQ 恢复 | 订单不取消、不退款；RabbitMQ 回来后配送单离开 CREATED。第 2 波集成发现 I1：修之前会一直停在 CREATED。脚本最后一定会把 RabbitMQ 启动回来 | 约 1.5 分钟 |

上一层的 `smoke-order.ps1` 是正常路径：一单从 PAID 走到 IN_TRANSIT。

注意：
- 初始数据里 `iPhone 13` 的库存很少，脚本 01 一次买两台。多跑几次以后，下单会报 "Insufficient stock"。
- 脚本 03 要等慢重试（`payment.bank.slow-retry-minutes`，默认 10 分钟）。
