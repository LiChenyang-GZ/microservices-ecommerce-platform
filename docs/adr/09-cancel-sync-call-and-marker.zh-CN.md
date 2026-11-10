# ADR-09 · 取消订单：同步调用 + 取消标记

[English](09-cancel-sync-call-and-marker.md) | **中文**

**状态：** 已采纳

## 背景
用户想马上知道取消成没成功，所以不能等事件。但取消请求可能比 delivery 读到 `OrderPaid`（读到才会建配送单）**还早**到 delivery（时序图 C）。不处理的话，迟到的 `OrderPaid` 会给一个已经取消的订单建配送单。

## 决定
- 订单已经动过钱：store 先锁订单行，再同步调 delivery：`POST /internal/deliveries/cancel-by-order/{orderId}`（超时 5 秒）。没付款的订单不可能有配送单，所以 store 不调；不过 delivery 读到这个订单的 `OrderCancelled` 时，仍然会留一个取消标记，防止之后扣款又成功。
- delivery 按情况回答，而且接口是幂等的：
  - 还没有配送单 → 插一条**取消标记**（这个订单的一行，状态 CANCELLED）→ `CANCELLED_BEFORE_CREATED`
  - 有配送单、还没揽收 → CANCELLED
  - 已经取消过 → `ALREADY_CANCELLED`
  - 已经揽收 → `TOO_LATE`（store 拒绝取消）
- 迟到的 `OrderPaid` 到了以后，因为 `order_id` 唯一（ADR-07），它会碰到这条标记，什么都不建。delivery 会再发一次 `Cancelled`；store 看到订单已经是 CANCELLED，直接跳过。
- delivery 同意后，store 取消订单、写退款任务、还库存，并记录 `OrderCancelled`。同一时刻到达的扣款结果会等订单锁，拿到锁后看到 CANCELLED，自己写退款任务。

## 代价与影响
- 竞态由一个唯一索引加一把行锁裁决，总能分出先后。
- 取消依赖 delivery 在线：它不回答，store 就提示用户稍后再试。
- `/internal/...` 还没有服务间鉴权，现在只靠入口白名单挡在外面。

## 代码在哪
- delivery [DeliveryService.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/service/DeliveryService.java)（`cancelByOrderId`、`CancelOutcome`）、[InternalDeliveryController.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/controller/InternalDeliveryController.java)
- store [DeliveryAdapter.java](../../backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java)、[OrderService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java)
- 场景 `08-sequence-c-cancel-before-delivery-reads.ps1`
