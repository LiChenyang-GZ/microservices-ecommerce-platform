# 故障场景和容错机制文档

## 📋 概述

本文档列出了系统中已实现的故障场景和容错机制，用于展示系统的**可用性（Availability）**和**容错性（Fault Tolerance）**。

---

## 🎯 已实现的故障场景和容错机制

### **场景 1：DeliveryService 挂掉后重启** ✅

**故障场景：**
- DeliveryService 在处理配送状态更新时挂掉
- 消息仍在 RabbitMQ 队列中（未确认）
- 服务重启后，消息会被重新分发并处理

**容错机制：**
- **RabbitMQ 队列持久化**：队列和消息都设置为持久化（`durable=true`）
- **消息持久化**：消息被标记为持久化（`DeliveryMode.PERSISTENT`）
- **自动恢复**：服务重启后，RabbitMQ 会重新分发未确认的消息

**展示点：**
- ✅ 消息不丢失
- ✅ 自动恢复处理
- ✅ 最终一致性

**测试方法：**
1. 下单并等待配送创建
2. 在 DeliveryService 处理消息时停止服务
3. 等待 5 秒后重启服务
4. 验证：配送状态更新继续处理

---

### **场景 2：Bank Service 暂时不可用**

**故障场景：**
- 用户下单后，在支付处理阶段 Bank Service 暂时挂掉或网络超时
- StoreService 调用 Bank Service 转账失败

**容错机制：**
- **重试机制**：`BankAdapter.transfer()` 自动重试 3 次，每次间隔 1 秒
- **超时控制**：连接超时 5 秒，读取超时 5 秒
- **降级处理**：重试失败后，标记支付为 FAILED，触发补偿流程

**展示点：**
- ✅ 自动重试（提高成功率）
- ✅ 超时控制（避免长时间等待）
- ✅ 优雅降级（失败后触发补偿）

**测试方法：**
1. 停止 Bank Service
2. 用户下单
3. 验证：StoreService 会重试 3 次，每次间隔 1 秒
4. 3 次失败后，支付标记为 FAILED，触发库存释放

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/adapter/BankAdapter.java`
- 重试逻辑：第 68-96 行

---

### **场景 3：DeliveryService 挂掉导致配送创建失败**

**故障场景：**
- 支付成功后，需要创建配送请求
- DeliveryService 暂时不可用（挂掉或网络问题）

**容错机制：**
- **重试机制**：`DeliveryAdapter.createDelivery()` 自动重试 3 次，每次间隔 1 秒
- **超时控制**：连接超时 5 秒，读取超时 5 秒
- **补偿机制**：重试失败后，等待 30 秒后触发补偿（退款 + 取消订单）

**展示点：**
- ✅ 自动重试（提高成功率）
- ✅ 延迟补偿（等待服务恢复，避免误退款）
- ✅ 自动退款（保护用户权益）

**测试方法：**
1. 停止 DeliveryService
2. 用户下单并支付成功
3. 验证：StoreService 会重试 3 次创建配送
4. 3 次失败后，创建 `DELIVERY_FAILED` 事件
5. 等待 30 秒后，自动触发退款和订单取消

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/adapter/DeliveryAdapter.java`
- 重试逻辑：第 47-87 行
- 补偿逻辑：`OutboxProcessor.processDeliveryFailed()` - 等待 30 秒后触发

---

### **场景 4：支付失败后的库存自动释放**

**故障场景：**
- 用户下单成功（库存已预留）
- 支付处理失败（余额不足、Bank Service 错误等）

**容错机制：**
- **补偿事务**：创建 `PAYMENT_FAILED` 事件到 Outbox
- **自动释放库存**：OutboxProcessor 处理失败事件时，自动释放预留的库存
- **邮件通知**：发送支付失败通知邮件给用户

**展示点：**
- ✅ 自动回滚库存（防止库存锁定）
- ✅ 最终一致性（通过 Outbox 模式）
- ✅ 用户通知（及时反馈）

**测试方法：**
1. 创建一个余额不足的用户账户
2. 用户下单
3. 验证：支付失败后，库存自动释放，订单状态更新为 CANCELLED

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java`
- `processPaymentFailed()` 方法：第 353-397 行

---

### **场景 5：配送丢失（LOST）的自动退款**

**故障场景：**
- 配送过程中包裹丢失（模拟或真实场景）
- DeliveryService 将状态更新为 LOST

**容错机制：**
- **自动检测**：StoreService 收到 LOST 状态通知
- **自动退款**：自动调用 `PaymentService.refundPayment()`
- **订单取消**：订单状态更新为 CANCELLED
- **双重通知**：发送订单取消邮件和退款成功邮件

**展示点：**
- ✅ 自动补偿（保护用户权益）
- ✅ 状态同步（订单状态自动更新）
- ✅ 用户通知（及时反馈）

**测试方法：**
1. 创建订单并支付成功
2. 等待配送状态变为 LOST（可通过代码模拟或等待随机事件）
3. 验证：订单自动退款，状态变为 CANCELLED，用户收到邮件

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java`
- `handleDeliveryUpdate()` 方法：第 297-321 行

---

### **场景 6：Outbox 事件处理失败的重试机制**

**故障场景：**
- OutboxProcessor 处理事件时失败（网络问题、服务暂时不可用等）
- 事件处理失败，但事件记录保留在数据库中

**容错机制：**
- **定时重试**：OutboxProcessor 每 5 秒扫描一次，自动重试失败的事件
- **重试次数限制**：最多重试 3 次（可配置）
- **状态追踪**：记录重试次数和状态（PENDING → PROCESSED / FAILED）

**展示点：**
- ✅ 自动重试（提高事件处理成功率）
- ✅ 防止消息丢失（事件持久化）
- ✅ 最终一致性（保证事件最终被处理）

**测试方法：**
1. 临时停止 Bank Service 或 DeliveryService
2. 触发一个需要调用外部服务的事件（如支付成功事件）
3. 验证：OutboxProcessor 会每 5 秒重试一次，最多 3 次
4. 恢复服务后，事件会被成功处理

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/scheduler/OutboxProcessor.java`
- `processOutbox()` 方法：第 98-122 行

---

### **场景 7：Webhook 通知失败的死信队列处理**

**故障场景：**
- DeliveryService 向 StoreService 发送 Webhook 通知时失败
- 网络问题或 StoreService 暂时不可用

**容错机制：**
- **死信队列**：失败的消息进入死信队列
- **自动重试**：DeadLetterQueueService 监听死信队列，自动重试最多 5 次
- **最终放弃**：超过最大重试次数后，记录日志并放弃

**展示点：**
- ✅ 消息不丢失（死信队列保存）
- ✅ 自动重试（提高通知成功率）
- ✅ 优雅降级（超过重试次数后放弃）

**测试方法：**
1. 停止 StoreService
2. 配送状态更新，触发 Webhook 通知
3. 验证：消息进入死信队列，DeadLetterQueueService 自动重试
4. 重启 StoreService 后，通知会成功

**代码位置：**
- `backend/deliveryService/src/main/java/com/comp5348/delivery/service/messaging/DeadLetterQueueService.java`
- 重试逻辑：第 25-45 行

---

### **场景 8：并发下单的库存保护**

**故障场景：**
- 两个用户同时下单，库存只有 1 个
- 两者都通过库存检查，尝试预留库存

**容错机制：**
- **乐观锁**：`WarehouseProduct` 实体使用 `@Version` 字段（乐观锁）
- **事务保护**：订单创建和库存预留在同一事务中
- **并发冲突处理**：第二个用户会因为乐观锁冲突而失败，事务回滚

**展示点：**
- ✅ 防止超卖（乐观锁保护）
- ✅ 数据一致性（事务保证）
- ✅ 并发安全（只有一个人成功）

**测试方法：**
1. 设置商品库存为 1
2. 两个用户同时（或几乎同时）下单该商品
3. 验证：只有一个订单会成功，另一个会收到"库存已被占用"的错误

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/model/WarehouseProduct.java`
- 乐观锁：第 35-36 行（`@Version` 字段）

---

### **场景 9：支付记录的幂等性保证**

**故障场景：**
- 由于网络重试或前端重复提交，同一个订单的支付记录被多次创建
- 或者服务重启导致事件重复处理

**容错机制：**
- **幂等性检查**：`PaymentService.createPayment()` 检查是否已存在支付记录
- **状态恢复**：如果支付记录存在但状态为 PENDING，补发事件
- **失败恢复**：如果状态为 FAILED，重置为 PENDING 并补发事件
- **成功幂等**：如果状态为 SUCCESS，补发 PAYMENT_SUCCESS 事件（避免下游事件缺失）

**展示点：**
- ✅ 幂等性保证（重复调用不影响结果）
- ✅ 状态恢复（自动处理异常状态）
- ✅ 最终一致性（补发事件确保下游处理）

**测试方法：**
1. 创建订单后，多次调用 `createPayment()` 方法
2. 验证：只有第一次调用会创建新记录，后续调用会使用现有记录并补发事件

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/service/PaymentService.java`
- `createPayment()` 方法：第 52-110 行

---

### **场景 10：订单取消的补偿流程**

**故障场景：**
- 用户主动取消订单
- 或者系统自动取消订单（支付失败、配送失败等）

**容错机制：**
- **完整补偿**：
  1. 取消配送（如果还未提货）
  2. 释放库存预留（回滚库存）
  3. 退款处理（如果已支付）
  4. 发送通知邮件
- **容错处理**：每个步骤都有 try-catch，即使某一步失败，其他步骤仍会执行

**展示点：**
- ✅ 完整回滚（库存、配送、支付）
- ✅ 容错设计（部分失败不影响整体）
- ✅ 用户通知（及时反馈）

**测试方法：**
1. 创建订单并支付成功
2. 在配送提货前取消订单
3. 验证：库存释放、退款成功、配送取消、用户收到邮件

**代码位置：**
- `backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java`
- `cancelOrder()` 方法：第 198-259 行

---

## 📊 容错机制总结

### **1. 重试机制**
- ✅ BankAdapter：支付/退款重试 3 次
- ✅ DeliveryAdapter：配送创建重试 3 次
- ✅ OutboxProcessor：事件处理自动重试（最多 3 次）
- ✅ DeadLetterQueueService：Webhook 通知重试（最多 5 次）

### **2. 超时控制**
- ✅ BankAdapter：连接超时 5 秒，读取超时 5 秒
- ✅ DeliveryAdapter：连接超时 5 秒，读取超时 5 秒

### **3. 消息持久化**
- ✅ RabbitMQ 队列持久化（`durable=true`）
- ✅ 消息持久化（`DeliveryMode.PERSISTENT`）
- ✅ Outbox 事件持久化（数据库）

### **4. 补偿机制**
- ✅ 支付失败 → 自动释放库存
- ✅ 配送失败 → 等待 30 秒后自动退款
- ✅ 配送丢失 → 自动退款和订单取消

### **5. 并发控制**
- ✅ 乐观锁（`@Version`）防止库存超卖
- ✅ 事务保护保证原子性

### **6. 幂等性**
- ✅ 支付记录创建支持幂等调用
- ✅ 订单创建和库存预留在同一事务中

### **7. 最终一致性**
- ✅ Outbox 模式确保事件最终被处理
- ✅ 定时任务自动处理失败的事件

---

## 🎬 推荐的演示场景

### **场景 A：服务暂时不可用（推荐）** ⭐⭐⭐
1. 停止 Bank Service
2. 用户下单 → 验证：StoreService 自动重试 3 次
3. 恢复 Bank Service → 验证：支付成功

**展示点：** 自动重试、超时控制、优雅降级

---

### **场景 B：DeliveryService 挂掉（推荐）** ⭐⭐⭐
1. 用户下单并支付成功
2. 停止 DeliveryService
3. 验证：StoreService 重试 3 次创建配送
4. 等待 30 秒 → 验证：自动触发退款和订单取消

**展示点：** 重试机制、延迟补偿、自动退款

---

### **场景 C：并发下单** ⭐⭐
1. 设置商品库存为 1
2. 两个用户同时下单
3. 验证：只有一个成功，另一个失败

**展示点：** 乐观锁、并发安全、防止超卖

---

### **场景 D：配送丢失** ⭐⭐
1. 创建订单并支付成功
2. 等待配送状态变为 LOST（或通过代码模拟）
3. 验证：自动退款、订单取消、用户收到邮件

**展示点：** 自动补偿、状态同步、用户通知

---

## 📝 总结

你的系统实现了以下容错机制：

1. ✅ **服务故障恢复**：DeliveryService 挂掉后重启，消息自动恢复
2. ✅ **自动重试**：Bank/Delivery Service 调用失败时自动重试
3. ✅ **补偿机制**：支付/配送失败后自动回滚和退款
4. ✅ **消息持久化**：RabbitMQ 消息和 Outbox 事件持久化
5. ✅ **并发控制**：乐观锁防止库存超卖
6. ✅ **幂等性**：支付记录创建支持重复调用
7. ✅ **最终一致性**：Outbox 模式确保事件最终被处理
8. ✅ **死信队列**：Webhook 通知失败后进入死信队列重试

这些机制确保了系统的**高可用性**和**强容错性**！🎉

