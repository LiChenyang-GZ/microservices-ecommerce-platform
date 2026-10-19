# 📊 失败重试机制与 DLQ 的真实流程

## 你的记忆是对的！系统中确实有"失败5次就丢弃"的逻辑

但这个逻辑**只适用于 NotificationMessage（通知消息）**，不适用于 DeliveryMessage。

---

## 两个不同的消息队列，两个不同的重试策略

### **1️⃣ Delivery 消息处理流程**（`delivery_processing_queue`）

```
DeliveryStatusUpdateService.processDelivery()
    ↓
处理成功 → ACK → 消息删除
    ↓
处理失败（异常） → NACK → 进入 DLX（死信交换机）
    ↓
delivery_dead_letter_queue
    ↓
DeliveryDeadLetterService 处理
    ↓
创建告警记录到数据库
    ↓
前端 DLQ 管理页面显示告警
```

**关键点：**
- ❌ **Delivery 消息没有内置重试机制**
- ❌ **失败 1 次就直接进入 DLQ**（没有重试）
- ✅ **但你可以在前端手动点击 "Requeue" 重新发送**

---

### **2️⃣ Notification 消息处理流程**（`notification_queue`）✅ **这个有 5 次重试**

```
NotificationProcessingService.processNotification()
    ↓
发送 Webhook 成功 → ACK → 消息删除
    ↓
发送 Webhook 失败（异常） → NACK → 进入 DLX
    ↓
dead_letter_queue（通用 DLQ）
    ↓
DeadLetterQueueService.processDeadLetterMessage()
    ↓
检查 retryCount：
    - 如果 retryCount < 5 → 重试次数+1，重新放回 notification_queue
    - 如果 retryCount >= 5 → 放弃，只记录日志
```

**关键点：**
- ✅ **Notification 消息有 5 次重试**
- ✅ **失败 5 次后才彻底放弃**
- ❌ **Notification 的最终失败不创建告警（只有日志）**

---

## 当前系统的实际行为

### **场景 A：Delivery 消息处理异常**

```
流程：
1. Store Service 发送 deliveryId=123
2. Delivery Service 监听并处理
3. ❌ 异常发生（数据库错误等）
4. NACK 消息
5. → 进入 delivery_dead_letter_queue
6. → DeliveryDeadLetterService 创建告警
7. → 前端显示"Delivery DLQ Alerts"
8. → 管理员可以 Resolve 或 Requeue

重试机制：❌ 无（失败立即进入 DLQ）
```

---

### **场景 B：Notification 消息发送失败**

```
流程：
1. DeliveryStatusUpdateService 发送通知消息
2. NotificationProcessingService 处理
3. ❌ Webhook 调用失败
4. NACK 消息
5. → 进入 dead_letter_queue
6. → DeadLetterQueueService 检查重试次数
   - 第 1 次失败 → retryCount=1，重新放回 notification_queue
   - 第 2 次失败 → retryCount=2，重新放回 notification_queue
   - ...
   - 第 5 次失败 → retryCount=5，放弃，只记录日志
7. → ❌ 不创建告警记录

重试机制：✅ 有（失败最多重试 5 次）
```

---

## 为什么设计成两套不同的机制？

| 队列 | 消息类型 | 失败影响 | 重试策略 |
|------|--------|--------|--------|
| **delivery_processing_queue** | Delivery 运单处理 | 🔴 高优先级（影响订单） | ❌ 无自动重试，管理员手动处理 |
| **notification_queue** | Webhook 通知 | 🟡 低优先级（只是通知） | ✅ 自动重试 5 次 |

**设计理念：**
- Delivery 消息是**核心业务**，失败需要**人工审核**
- Notification 消息是**辅助功能**，可以**自动重试**

---

## 建议：为 Delivery 消息也添加重试机制

如果你想让 Delivery 消息也有重试机制（而不是失败立即进入 DLQ），可以这样改：

### **方案 1：在 Delivery 实体中添加重试计数**

```java
@Entity
@Table(name = "delivery")
public class Delivery {
    // ... 现有字段 ...
    
    @Column(nullable = false)
    private int retryCount = 0; // 添加重试计数
}
```

### **方案 2：修改 DeliveryStatusUpdateService**

```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(Long deliveryId) {
    try {
        // 正常处理逻辑
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery == null) {
            throw new DeliveryNotFoundException("Not found");
        }
        // ... 处理 ...
    } catch (Exception e) {
        logger.error("Failed to process delivery: {}", deliveryId, e);
        
        // 获取当前重试次数
        Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
        if (delivery != null) {
            int retries = delivery.getRetryCount();
            
            if (retries < 5) {
                // 重试
                delivery.setRetryCount(retries + 1);
                deliveryRepository.save(delivery);
                rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, deliveryId);
                logger.warn("Retrying delivery {}, attempt {}/5", deliveryId, retries + 1);
            } else {
                // 进入 DLQ
                throw e;
            }
        }
    }
}
```

---

## 当前系统的总结

### ✅ **已有的机制：**

| 功能 | 状态 | 说明 |
|------|------|------|
| **Notification 消息重试** | ✅ 实现 | 最多 5 次，自动重试 |
| **Delivery DLQ 告警** | ✅ 实现 | 失败消息记录到数据库 |
| **前端 DLQ 管理界面** | ✅ 实现 | Resolve/Requeue 功能 |

### ❌ **缺失的机制：**

| 功能 | 状态 | 建议 |
|------|------|------|
| **Delivery 消息重试** | ❌ 无 | 可添加（参考上面的方案） |
| **Delivery 重试计数** | ❌ 无 | 可添加 |

---

## 推荐的改进步骤

### **选项 A：保持现状（推荐）**
- Delivery 失败立即进入 DLQ → 等待管理员审核和处理
- 好处：关键业务不自动重试，确保人工审核
- 缺点：用户体验可能不好（一次失败就要人工处理）

### **选项 B：为 Delivery 也添加重试机制**
- Delivery 失败自动重试 5 次 → 再进入 DLQ
- 好处：容错性更强，减少人工干预
- 缺点：实现稍复杂，需要在 Delivery 实体添加字段

### **选项 C：混合方案（最推荐）**
- 某些异常自动重试（如超时、数据库临时锁定）
- 某些异常立即 DLQ（如数据不存在）

---

## 测试验证

### **验证 Notification 有 5 次重试：**

```bash
# 1. 修改 NotificationProcessingService 使其总是失败
# 2. 发送 delivery 消息
# 3. 观察日志，应该看到：
#    [Dead Letter Queue] Received failed message, performing retry 1/5
#    [Dead Letter Queue] Received failed message, performing retry 2/5
#    ...
#    [Final Failure] Message has reached maximum retry count (5)
```

### **验证 Delivery 没有重试（失败立即 DLQ）：**

```bash
# 1. 修改 DeliveryStatusUpdateService 使其抛出异常
# 2. 发送 delivery 消息
# 3. 观察前端 DLQ 告警
# 4. 应该立即看到告警（无重试）
```

需要我为你实现 Delivery 消息的重试机制吗？
