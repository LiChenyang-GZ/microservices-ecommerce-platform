# 下单流程详细说明

## 📋 总体流程概览

```
用户下单请求
    ↓
【阶段1：同步流程 - 在 OrderProductService.createOrderWithPayment 中】
    ├─ 0. 预估总价和检查余额
    ├─ 1. 创建订单（包含库存检查和预留）
    ├─ 2. 更新订单状态为 PENDING_PAYMENT
    └─ 3. 创建支付记录（Payment 实体）
    ↓
【阶段2：异步流程 - 通过 Outbox 模式处理】
    ├─ 4. OutboxProcessor 定时处理 PAYMENT_PENDING 事件
    ├─ 5. 执行实际支付（调用 Bank 服务）
    ├─ 6. 支付成功 → 创建 PAYMENT_SUCCESS 事件
    ├─ 7. OutboxProcessor 处理 PAYMENT_SUCCESS 事件
    └─ 8. 创建配送请求
```

---

## 🔍 详细步骤说明

### **阶段1：同步流程（用户请求期间完成）**

#### **入口：`OrderProductService.createOrderWithPayment()`**

**步骤 0：预检查和余额验证**
```java
// 位置：OrderProductService.createOrderWithPayment (第64-93行)
1. 计算订单预估总价（基于商品价格和数量）
2. 获取用户银行账户信息
3. 检查银行账户余额是否充足
   - 余额不足 → 抛出 IllegalArgumentException
   - 余额充足 → 继续
```

**步骤 1：创建订单（`OrderService.createOrder()`）**
```java
// 位置：OrderService.createOrder (第99-177行)
// 事务：整个方法在 @Transactional 中，保证原子性

1.1. 验证请求参数
   - 检查订单项数量（必须是1个）
   - 检查商品数量是否有效（> 0）

1.2. 检查库存（第一次检查）
   // 位置：第114行
   int totalStock = warehouseService.getProductQuantity(productId);
   if (totalStock < quantity) {
       throw new RuntimeException("Insufficient stock");
   }
   
1.3. 创建临时订单记录
   // 位置：第122-130行
   - 创建 Order 对象（状态：PENDING_STOCK_HOLD）
   - 保存到数据库，获取 orderId（saveAndFlush）
   
1.4. 库存预留（关键步骤）
   // 位置：第133行
   var whDTO = warehouseService.getAndUpdateAvailableWarehouse(
       productId, quantity, orderId);
   
   // 库存预留逻辑（在 WarehouseService 中）：
   //   a. 查询所有可用仓库的该商品库存
   //   b. 计算总可用库存
   //   c. 如果总库存 < 需求量 → 返回 null（失败）
   //   d. 从多个仓库按需分配库存（在内存中计算）
   //   e. 一次性保存所有库存更新（saveAll）
   //   f. 创建库存事务记录（HOLD 类型）
   //   g. 使用乐观锁（@Version）防止并发冲突
   
   // 如果预留失败（返回 null）：
   //   - 抛出 RuntimeException
   //   - 整个事务回滚，订单不会被创建
   
1.5. 更新订单状态和库存事务ID
   // 位置：第139-148行
   - 保存库存事务ID到订单
   - 更新订单状态为 PLACED
   - 保存订单
   
1.6. 创建 Outbox 事件（PAYMENT_PENDING）
   // 位置：第151-174行
   - 创建 PaymentOutbox 记录
   - EventType: PENDING
   - Status: PENDING
   - 保存到数据库
   
   // 如果创建 Outbox 失败：
   //   - 抛出异常，整个事务回滚
```

**步骤 2：更新订单状态**
```java
// 位置：OrderProductService.createOrderWithPayment (第99行)
orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING_PAYMENT);
```

**步骤 3：创建支付记录**
```java
// 位置：OrderProductService.createOrderWithPayment (第102-115行)
// 位置：PaymentService.createPayment (第52-110行)

3.1. 幂等性检查
   - 如果该订单已存在支付记录：
     * PENDING 状态 → 补发 PAYMENT_PENDING 事件，返回现有记录
     * FAILED 状态 → 重置为 PENDING，补发事件
     * SUCCESS 状态 → 补发 PAYMENT_SUCCESS 事件（幂等）

3.2. 创建新支付记录
   - 创建 Payment 实体（状态：PENDING）
   - 保存到数据库
   - 创建 PAYMENT_PENDING 事件到 Outbox（通过 OutboxService）
   
3.3. 异常处理
   - 如果创建支付失败 → 取消订单（释放库存）
```

**返回结果**
```java
// 用户收到订单创建成功的响应
// 此时：
//   - 订单状态：PENDING_PAYMENT
//   - 库存已预留（HOLD 状态）
//   - 支付记录已创建（状态：PENDING）
//   - PAYMENT_PENDING 事件已写入 Outbox
```

---

### **阶段2：异步流程（后台定时处理）**

#### **OutboxProcessor 定时任务（每5秒执行一次）**

**步骤 4：处理 PAYMENT_PENDING 事件**
```java
// 位置：OutboxProcessor.processOutbox() (第100-122行)
// 位置：OutboxProcessor.processPaymentPending() (第215-230行)

1. 查询所有 PENDING 状态的 Outbox 记录
   List<PaymentOutbox> pendingOutboxes = 
       outboxRepository.findByStatusAndRetryCountLessThan("PENDING", maxRetries);

2. 对每个 PAYMENT_PENDING 事件：
   - 解析 payload，获取 orderId
   - 调用 PaymentService.processPayment(orderId)
   
3. 处理结果：
   - 成功 → Outbox 状态更新为 PROCESSED
   - 失败 → 重试次数 +1，如果超过最大次数则标记为 FAILED
```

**步骤 5：执行实际支付（`PaymentService.processPayment()`）**
```java
// 位置：PaymentService.processPayment (第116-168行)

5.1. 查询支付记录
   - 验证支付记录存在且状态为 PENDING
   
5.2. 选择付款账户
   - 优先使用用户绑定的银行账户
   - 如果没有，使用默认客户账户
   
5.3. 调用 Bank 服务转账
   - 构造 BankTransferRequest
   - 调用 bankAdapter.transfer()
   
5.4. 处理支付结果
   
   【成功情况】
   // 位置：PaymentService.handlePaymentSuccess (第174-185行)
   - 更新 Payment 状态为 SUCCESS
   - 保存 bankTxnId
   - 创建 PAYMENT_SUCCESS 事件到 Outbox
   
   【失败情况】
   // 位置：PaymentService.handlePaymentFailure (第190-202行)
   - 更新 Payment 状态为 FAILED
   - 保存错误信息
   - 创建 PAYMENT_FAILED 事件到 Outbox
   - 触发后续处理（释放库存、发送通知等）
```

**步骤 6：处理 PAYMENT_SUCCESS 事件**
```java
// 位置：OutboxProcessor.processPaymentSuccess() (第235-319行)

6.1. 更新订单状态
   - 更新为 PAID 状态
   
6.2. 创建配送请求
   - 查询订单信息
   - 查询用户信息
   - 构造 DeliveryRequestDTO
   - 调用 DeliveryAdapter.createDelivery()
   
6.3. 保存配送ID
   - 将 deliveryId 保存到订单
   
6.4. 异常处理
   - 如果配送创建失败 → 创建 DELIVERY_FAILED 事件
   - 触发补偿流程（退款）
```

**步骤 7：处理 PAYMENT_FAILED 事件**
```java
// 位置：OutboxProcessor.processPaymentFailed() (第324-376行)

7.1. 释放库存预留
   - 解析订单的 inventoryTransactionIds
   - 调用 WarehouseService.unholdProduct()
   - 恢复仓库库存
   
7.2. 发送失败通知邮件
   - 调用 EmailAdapter.sendOrderFailed()
```

---

## 🔐 并发控制机制

### **1. 库存预留的并发保护**

**乐观锁（Optimistic Locking）**
- `WarehouseProduct` 实体有 `@Version` 字段
- 当两个用户同时预留库存时：
  - 第一个用户：读取库存 → 计算 → 保存（成功）
  - 第二个用户：读取库存（旧值）→ 计算 → 保存（抛出 `OptimisticLockException`）
  - 第二个用户的事务回滚，订单不会创建

**事务保证**
- `OrderService.createOrder()` 是 `@Transactional`
- 订单创建和库存预留在同一事务中
- 如果库存预留失败，整个订单创建事务回滚

### **2. 支付记录的幂等性**

- `PaymentService.createPayment()` 支持幂等调用
- 如果订单已有支付记录，根据状态决定是否补发事件

---

## 📊 状态流转图

### **订单状态流转**
```
PLACED (已下单)
    ↓
PENDING_PAYMENT (待付款)  ← 创建支付记录后
    ↓
PAID (已付款)  ← 支付成功后
    ↓
PROCESSING (处理中)  ← 可选
    ↓
SHIPPED (已发货)  ← 配送开始
    ↓
IN_TRANSIT (配送中)
    ↓
DELIVERED (已送达)
```

### **支付状态流转**
```
PENDING (待处理)
    ↓
    ├─→ SUCCESS (成功) → REFUNDED (已退款)
    └─→ FAILED (失败)
```

---

## ⚠️ 关键点总结

1. **库存检查和预留是同步的**，发生在订单创建时
2. **支付是异步的**，通过 Outbox 模式处理
3. **并发保护**：乐观锁防止库存超卖
4. **事务保证**：订单创建和库存预留在同一事务中，要么全部成功，要么全部失败
5. **幂等性**：支付记录创建支持重复调用
6. **补偿机制**：支付失败会自动释放库存，配送失败会触发退款

---

## 🔄 异常处理流程

### **订单创建阶段失败**
- 库存不足 → 事务回滚，订单不创建
- 库存预留失败 → 事务回滚，订单不创建
- Outbox 创建失败 → 事务回滚，订单不创建

### **支付阶段失败**
- 支付失败 → 创建 PAYMENT_FAILED 事件
- 自动释放库存预留
- 发送失败通知邮件

### **配送阶段失败**
- 配送创建失败 → 创建 DELIVERY_FAILED 事件
- 触发补偿流程（退款 + 取消订单）
- 发送退款成功邮件

