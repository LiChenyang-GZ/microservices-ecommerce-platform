# Delivery 死信队列 (DLQ) 功能测试指南

## 前提条件

1. ✅ 已在 PostgreSQL 中执行：`ALTER TABLE delivery ADD COLUMN retry_count INT NOT NULL DEFAULT 0;`
2. ✅ 后端服务已启动（Delivery Service 运行在 8081 端口）
3. ✅ 前端已启动（React 运行在 3000 端口）
4. ✅ RabbitMQ 已启动（localhost:5672）

---

## 测试场景 1: 验证重试机制（失败 5 次后进入 DLQ）

### 1.1 准备：添加临时失败逻辑

修改 `DeliveryStatusUpdateService.java` 的 `processDelivery()` 方法，在处理特定 ID 的消息时故意抛出异常：

```java
@RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
public void processDelivery(String deliveryId) throws Exception {
    logger.info("[Processing] Delivery ID: {}", deliveryId);
    
    // 临时测试：特定 ID 的消息总是失败
    if (deliveryId.equals("TEST-FAIL-DELIVERY")) {
        throw new RuntimeException("[TEST] Simulated delivery processing failure for testing DLQ retry mechanism");
    }
    
    // ... 正常处理逻辑 ...
}
```

### 1.2 发送测试消息

在前端或使用 RabbitMQ Admin 界面向 `delivery_processing_queue` 发送消息：

```
消息内容: TEST-FAIL-DELIVERY
```

或者使用以下 PowerShell 脚本：

```powershell
# 需要在后端添加一个测试端点来发送消息
# POST http://localhost:8081/api/admin/test/send-delivery?deliveryId=TEST-FAIL-DELIVERY
```

### 1.3 观察日志

在后端控制台查看日志序列：

**预期日志输出：**
```
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Retry] Requeuing delivery ID: TEST-FAIL-DELIVERY for retry 1/5
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Retry] Requeuing delivery ID: TEST-FAIL-DELIVERY for retry 2/5
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Retry] Requeuing delivery ID: TEST-FAIL-DELIVERY for retry 3/5
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Retry] Requeuing delivery ID: TEST-FAIL-DELIVERY for retry 4/5
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Retry] Requeuing delivery ID: TEST-FAIL-DELIVERY for retry 5/5
[Processing] Delivery ID: TEST-FAIL-DELIVERY
[Delivery Processing Error] Failed to process delivery ID: TEST-FAIL-DELIVERY. Error: ...
[Delivery Max Retries Exceeded] Delivery ID: TEST-FAIL-DELIVERY has exceeded maximum retry attempts (5). Entering DLQ.
```

### 1.4 验证消息进入 DLQ

检查 RabbitMQ Admin：
- 访问 http://localhost:15672 (admin/admin)
- 查看 `delivery_dead_letter_queue` 的消息计数是否增加

---

## 测试场景 2: 在前端查看 DLQ 告警

### 2.1 访问 Admin 仪表板

1. 前端访问 http://localhost:3000
2. 登录为管理员账号
3. 导航到 **Admin** → **Delivery DLQ** (🚨 图标)

### 2.2 验证告警显示

应该看到：
- ✅ 告警卡片显示 "Total Alerts: 1"
- ✅ 表格中列出一条记录，包含：
  - **Delivery ID**: TEST-FAIL-DELIVERY
  - **Severity**: CRITICAL (黄色标签)
  - **Reason**: 处理失败的错误信息
  - **Alert Time**: 当前时间
  - **Status**: Unresolved

### 2.3 验证过滤功能

- 点击 **Severity** 下拉框，选择不同的严重级别过滤
- 切换 **Show Resolved** 开关，确认已解决的告警隐藏/显示

---

## 测试场景 3: 测试 Resolve 操作

### 3.1 在前端点击 Resolve 按钮

1. 找到未解决的告警行
2. 点击 **Resolve** 按钮（蓝色）

### 3.2 验证结果

预期结果：
- ✅ 按钮变为灰色（禁用状态）
- ✅ 告警状态变为 "Resolved"
- ✅ "Resolved At" 列显示当前时间戳
- ✅ 如果关闭 "Show Resolved" 开关，该告警消失

**后端日志应显示：**
```
[Delivery DLQ Alert] Alert ID: 1 marked as resolved
```

---

## 测试场景 4: 测试 Requeue 操作

### 4.1 创建新的失败消息

重复 **测试场景 1** 的步骤，创建新的失败消息进入 DLQ。

### 4.2 在前端点击 Requeue 按钮

1. 找到未解决的告警行
2. 点击 **Requeue** 按钮（绿色）

### 4.3 验证消息重新处理

预期结果：
- ✅ 消息被重新放入 `delivery_processing_queue`
- ✅ DeliveryStatusUpdateService 重新处理消息
- ✅ 如果还是失败，`retryCount` 继续增加
- ✅ 前端告警状态变为 "Resolved"

**后端日志应显示：**
```
[Delivery DLQ Alert] Requeuing delivery ID: TEST-FAIL-DELIVERY from DLQ back to processing queue
[Processing] Delivery ID: TEST-FAIL-DELIVERY
```

---

## 测试场景 5: 验证数据库状态

### 5.1 检查 delivery 表

在 pgAdmin 或 SQL 客户端执行：

```sql
-- 查看特定 delivery 的 retry_count
SELECT id, delivery_status, retry_count, update_time 
FROM delivery 
WHERE id = 'TEST-FAIL-DELIVERY';
```

**预期结果：**
- `retry_count` = 5（失败 5 次后）
- `delivery_status` = FAILED 或 PENDING（取决于是否被 requeue）

### 5.2 检查 delivery_dead_letter_alert 表

```sql
-- 查看所有 DLQ 告警
SELECT id, delivery_id, severity, reason, alert_timestamp, resolved, resolved_at 
FROM delivery_dead_letter_alert 
ORDER BY alert_timestamp DESC;
```

**预期结果：**
- ✅ 存在一条记录，`delivery_id` = TEST-FAIL-DELIVERY
- ✅ `severity` = CRITICAL
- ✅ `resolved` = true（如果点击了 Resolve）
- ✅ `resolved_at` 有值（如果点击了 Resolve）

---

## 测试场景 6: 验证 CORS 和 API 连接

### 6.1 打开浏览器开发者工具

1. F12 打开开发者工具
2. 切换到 **Network** 标签

### 6.2 导航到 DLQ 页面

刷新 Admin → Delivery DLQ 页面，观察网络请求：

**预期请求：**
```
GET /api/admin/dlq/delivery        ✅ Status 200
```

**预期响应格式：**
```json
[
  {
    "id": 1,
    "deliveryId": "TEST-FAIL-DELIVERY",
    "severity": "CRITICAL",
    "reason": "...",
    "alertTimestamp": "2025-12-13T23:51:06",
    "resolved": false,
    "resolvedAt": null
  }
]
```

### 6.3 验证 Resolve 请求

点击 Resolve 按钮，验证请求：

```
POST /api/admin/dlq/delivery/1/resolve
```

**预期响应：** Status 200

---

## 测试场景 7: 性能和边界测试

### 7.1 批量失败消息

发送 10 条相同的失败消息，验证：
- ✅ 所有消息都重试 5 次
- ✅ 前端显示 10 条告警
- ✅ 性能良好，界面响应快速

### 7.2 网络中断测试

在处理过程中中断网络连接：
- ✅ 消息不会丢失，会在网络恢复后重新处理
- ✅ RabbitMQ 队列保证了消息不会丢失

---

## 清理测试数据

### 完成所有测试后，清理测试消息：

1. **删除测试告警记录：**
```sql
DELETE FROM delivery_dead_letter_alert 
WHERE delivery_id = 'TEST-FAIL-DELIVERY';

DELETE FROM delivery 
WHERE id = 'TEST-FAIL-DELIVERY';
```

2. **清空 RabbitMQ 队列：**
   - 访问 RabbitMQ Admin (http://localhost:15672)
   - 进入 Queues 页签
   - 清除 `delivery_dead_letter_queue` 和 `delivery_processing_queue` 的所有消息

3. **重新启动后端服务以清除缓存**

---

## 快速测试清单

| 功能 | 测试方法 | 预期结果 | 状态 |
|------|--------|--------|------|
| **重试机制** | 发送失败消息，查看日志 | 失败 5 次后进入 DLQ | ⬜ |
| **前端告警显示** | 打开 Admin → Delivery DLQ | 显示告警列表 | ⬜ |
| **告警过滤** | 选择 Severity 过滤 | 按严重级别过滤 | ⬜ |
| **Resolve 操作** | 点击 Resolve 按钮 | 告警标记为已解决 | ⬜ |
| **Requeue 操作** | 点击 Requeue 按钮 | 消息重新处理 | ⬜ |
| **数据库同步** | 查询 PostgreSQL | 数据与前端一致 | ⬜ |
| **API 连接** | 查看浏览器网络标签 | GET/POST 返回 200 | ⬜ |

---

## 故障排除

### 问题 1: 前端显示 "No alerts found"

**原因：**
- 消息没有进入 DLQ
- DeliveryDeadLetterService 没有监听到消息

**解决方案：**
1. 检查 RabbitMQ 队列是否收到消息（Admin UI）
2. 检查后端日志是否有错误
3. 验证 `delivery_dead_letter_queue` 名称是否正确

### 问题 2: 404 错误访问 `/api/admin/dlq/delivery`

**原因：**
- DeliveryDeadLetterController 没有被加载
- 路由路径不匹配

**解决方案：**
1. 检查 @RequestMapping 路径是否正确
2. 检查 @RestController 注解是否存在
3. 重启后端服务

### 问题 3: 消息无限重试

**原因：**
- processDelivery() 没有抛出异常
- 重试逻辑没有被触发

**解决方案：**
1. 验证测试代码中 `if (deliveryId.equals("TEST-FAIL-DELIVERY"))` 是否被执行
2. 添加日志确认异常被抛出
3. 检查异常处理块是否被执行

---

## 注意事项

⚠️ **测试完成后，务必：**
1. 移除 `if (deliveryId.equals("TEST-FAIL-DELIVERY"))` 这行测试代码
2. 清理数据库和 RabbitMQ 中的测试数据
3. 重新启动服务确保生产环境清洁
