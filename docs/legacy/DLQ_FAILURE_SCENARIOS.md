# 📋 Delivery 消息进入 DLQ 的场景分析

## 核心原理

RabbitMQ 中，当一条消息处理失败时，它会进入 **Dead Letter Queue (DLQ)**。这通常发生在以下两种情况：

1. **消息处理抛出未捕获的异常** → RabbitMQ 认为处理失败，自动 NACK（否定应答）消息
2. **消息重试达到上限** → 配置了重试策略，重试次数用尽后进入 DLQ
3. **手动调用 NACK** → 处理逻辑中显式拒绝消息

---

## 当前系统中会导致进入 DLQ 的场景

### **场景 1️⃣：数据库中不存在该 Delivery 任务**

**触发条件：**
```java
Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
if (delivery == null) {
    logger.warn("[Warning] Delivery task with ID {} not found in database...");
    return; // ✅ 正常返回，消息被 ACK（确认），不会进入 DLQ
}
```

**当前行为：** ✅ 消息被安全处理（记录日志后丢弃），**不会进入 DLQ**

**改进建议：** 如果希望记录这类失败，可以改为：
```java
if (delivery == null) {
    logger.error("[ERROR] Delivery not found: {}", deliveryId);
    throw new DeliveryNotFoundException("Delivery ID " + deliveryId + " not found");
    // 现在会进入 DLQ，并在 DLQ listener 中记录告警
}
```

---

### **场景 2️⃣：数据库操作异常（乐观锁冲突等）**

**触发条件：**
```java
catch (ObjectOptimisticLockingFailureException e) {
    logger.warn("[Optimistic Lock Conflict] Conflict occurred...");
    transactionManager.rollback(status);
    // ✅ 异常被捕获，不会向上抛出，消息被 ACK，不会进入 DLQ
}
```

**当前行为：** ✅ 异常被捕获处理，**不会进入 DLQ**

**改进建议：** 这种情况下，你可能想让消息重试，可以改为：
```java
catch (ObjectOptimisticLockingFailureException e) {
    logger.warn("[Optimistic Lock Conflict] Retrying...");
    transactionManager.rollback(status);
    throw e; // 重新抛出，触发重试机制
}
```

---

### **场景 3️⃣：发送通知消息（Webhook）失败**

**触发条件：**
```java
rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
// 如果 notification_queue 配置了 DLX，且 NotificationProcessingService 处理失败...
```

**当前行为：** 取决于 NotificationProcessingService 的实现

**检查 NotificationProcessingService：**

---

### **场景 4️⃣：线程中断异常**

**触发条件：**
```java
try {
    Thread.sleep(BASE_WAIT_TIME_MS);
} catch (InterruptedException e) {
    Thread.currentThread().interrupt();
    logger.error("Thread interrupted while waiting", e);
    // ✅ 异常被捕获，消息被 ACK，不会进入 DLQ
}
```

**当前行为：** ✅ 异常被捕获，**不会进入 DLQ**

---

### **场景 5️⃣：任何未被捕获的异常** ⚠️ 这是最常见的原因

**示例：**
```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(Long deliveryId) {
    // 假设这里发生异常：
    Delivery delivery = deliveryRepository.findById(deliveryId)
        .orElseThrow(() -> new Exception("Not found"));
    
    // 或者：
    someUnsafeMethod(); // 抛出 RuntimeException 或其他异常
    
    // 如果异常未被 catch，它会：
    // 1. 导致消息处理失败
    // 2. RabbitMQ 自动 NACK 消息
    // 3. 消息进入 DLX（如果配置了）
    // 4. 最终进入 DLQ
}
```

**触发条件：** 任何未被 catch 的异常

**当前行为：** ❌ 消息会进入 DLQ

---

## 如何主动测试 DLQ 功能

### **方法 1：修改代码抛出异常**

临时修改 `DeliveryStatusUpdateService.java`：

```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(Long deliveryId) {
    try {
        logger.info("[Message Consumer] Received new task, processing delivery ID: {}", deliveryId);
        
        // 模拟异常
        if (deliveryId == 999) {
            throw new RuntimeException("Simulated processing failure for testing");
        }
        
        // 正常处理逻辑...
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            transactionManager.commit(status);
            return;
        }
        // ... 其余代码
    } catch (Exception e) {
        logger.error("[ERROR] Failed to process delivery: {}", deliveryId, e);
        transactionManager.rollback(status);
        throw e; // 让异常向上抛出，进入 DLQ
    }
}
```

**测试步骤：**
1. 发送一条消息到 `delivery_processing_queue`，payload = `999`
2. 监听器会抛出异常
3. RabbitMQ 将消息进入 DLX
4. 最终进入 `delivery_dead_letter_queue`
5. DLQ listener 创建告警记录

---

### **方法 2：通过 RabbitMQ 管理界面直接发送坏消息**

1. 打开 `http://localhost:15672`（admin/admin）
2. 进入 **Queues** → `delivery_processing_queue`
3. 点击 **Publish Message**
4. 在 **Payload** 中输入无效数据：
   ```
   "invalid-data-not-a-valid-delivery-id"
   ```
5. 发布消息
6. 监听器会因无法解析而抛出异常
7. 消息进入 DLQ

---

### **方法 3：数据库操作失败**

```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(Long deliveryId) {
    try {
        // 故意让 save 操作失败
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery != null) {
            // 模拟数据库约束违反
            delivery.setDeliveryStatus(null); // 违反 @NotNull 约束
            deliveryRepository.saveAndFlush(delivery); // 会抛出异常
        }
    } catch (Exception e) {
        logger.error("Save failed", e);
        throw e; // 进入 DLQ
    }
}
```

---

## 建议的改进方案

为了更好地记录失败场景，建议在 `DeliveryStatusUpdateService` 中添加统一的异常处理：

```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(Long deliveryId) {
    TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
    try {
        // 正常处理逻辑
        // ...
    } catch (DeliveryNotFoundException e) {
        // 特定异常：Delivery 不存在
        logger.error("[Delivery Not Found] ID: {}", deliveryId);
        transactionManager.rollback(status);
        throw e; // 进入 DLQ，记录告警
    } catch (ObjectOptimisticLockingFailureException e) {
        // 乐观锁冲突（可能由其他操作更新了 Delivery）
        logger.warn("[Optimistic Lock] ID: {} is being updated elsewhere", deliveryId);
        transactionManager.rollback(status);
        // 重试或丢弃，取决于业务需求
    } catch (Exception e) {
        // 捕获所有其他未预期的异常
        logger.error("[Unexpected Error] Processing delivery ID: {} failed", deliveryId, e);
        transactionManager.rollback(status);
        throw e; // 进入 DLQ
    }
}
```

---

## 总结

| 场景 | 当前行为 | 是否进入 DLQ | 备注 |
|------|--------|-----------|------|
| Delivery 不存在 | 记录日志后返回 | ❌ 否 | 正常处理，不算错误 |
| 乐优化锁冲突 | 捕获并回滚 | ❌ 否 | 正常处理，不算错误 |
| 线程中断 | 捕获并记录 | ❌ 否 | 正常处理 |
| 数据库保存失败 | 未捕获 | ✅ 是 | 会进入 DLQ |
| 通知队列发送失败 | 取决于实现 | ⚠️ 可能 | 需要检查 NotificationProcessingService |
| **任何未捕获异常** | **抛出** | **✅ 是** | **最常见的原因** |

---

## 下一步

1. **为了测试 DLQ 功能**，建议临时修改代码抛出异常（参考方法 1）
2. **观察消息流向**：异常 → NACK → DLX → DLQ → DLQ Listener → 数据库记录
3. **在前端验证**：访问 `/admin/delivery-dlq` 查看记录的告警
4. **修复问题**：点击 Resolve 或 Requeue 处理告警

需要我帮你修改代码以更好地测试 DLQ 吗？
