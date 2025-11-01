# 并发下单库存冲突分析

## 问题场景

**场景：** 两个用户（用户A和用户B）同时下单，库存只有1个，两者都到达了库存预留的位置。

**问题：** 谁会成功？还是说这种情况不可能发生？

---

## ✅ 答案：这种情况**可能发生**，但乐观锁会确保**只有一个人成功**

---

## 🔍 详细分析

### 当前代码的保护机制

#### 1. **乐观锁（Optimistic Locking）**

`WarehouseProduct` 实体有 `@Version` 字段：

```java
@Entity
public class WarehouseProduct {
    @Version
    private Long version;  // 乐观锁版本号
    
    private Integer quantity;
    // ...
}
```

#### 2. **并发执行时间线**

假设库存只有1个，两个用户同时下单：

```
时间轴：
T1: 用户A 读取库存 → quantity=1, version=0
T2: 用户B 读取库存 → quantity=1, version=0  (此时A还未提交)
T3: 用户A 在内存中计算：quantity=1-1=0
T4: 用户B 在内存中计算：quantity=1-1=0
T5: 用户A 调用 saveAll → 数据库检查 version=0，更新成功
    → 库存变为 0，version 变为 1
T6: 用户B 调用 saveAll → 数据库检查 version=0
    → 但实际数据库 version=1（A已更新）
    → 抛出 OptimisticLockException
T7: 用户B 的事务回滚（因为 @Transactional）
    → 订单创建失败
```

---

## 📊 结果分析

### **用户A：成功** ✅
- 库存预留成功
- 订单创建成功
- 可以继续支付流程

### **用户B：失败** ❌
- 抛出 `OptimisticLockException`
- 异常向上传播到 `OrderService.createOrder()`
- 整个事务回滚（因为 `@Transactional`）
- **订单不会创建**
- **库存也不会被预留**（因为回滚了）
- 用户B会收到错误：`"Failed to hold stock, maybe it was taken by another order"`

---

## 🔄 异常处理流程

### **当前代码的异常处理**

#### 1. **`WarehouseService.getAndUpdateAvailableWarehouse()`**
```java
@Transactional
public WarehouseDTO getAndUpdateAvailableWarehouse(...) {
    // ... 读取库存 ...
    // ... 在内存中计算 ...
    
    // 这一步如果发生 OptimisticLockException，会被向上抛出
    warehouseProductRepository.saveAll(productsToUpdate);
    // ↑ 如果这里抛出 OptimisticLockException，没有被捕获
}
```

#### 2. **`OrderService.createOrder()`**
```java
@Transactional
public OrderDTO createOrder(...) {
    // ... 创建临时订单 ...
    
    // 调用库存预留
    var whDTO = warehouseService.getAndUpdateAvailableWarehouse(...);
    // ↑ 如果抛出 OptimisticLockException，会向上传播
    
    if (whDTO == null || ...) {
        throw new RuntimeException("Failed to hold stock...");
    }
    // ↑ 正常情况下，如果返回 null，会抛异常
    // ↑ 但实际上，OptimisticLockException 会先被抛出
}
```

#### 3. **异常传播和事务回滚**

当 `OptimisticLockException` 被抛出：
1. **异常向上传播**：从 `WarehouseService` → `OrderService`
2. **事务回滚**：因为方法标记了 `@Transactional`，异常会导致整个事务回滚
3. **数据库状态恢复**：
   - 临时订单被撤销（回滚）
   - 库存不会被扣减（因为保存失败）
   - 用户B的下单请求失败

---

## ⚠️ 潜在问题

### **当前代码没有显式捕获 `OptimisticLockException`**

**问题：**
- `getAndUpdateAvailableWarehouse()` 没有捕获 `OptimisticLockException`
- 异常会作为 `RuntimeException` 向上传播
- 用户B会收到通用的错误信息

**影响：**
- ✅ 功能上没问题（不会超卖）
- ⚠️ 用户体验：错误信息可能不够清晰

---

## 💡 建议的改进

### **选项1：显式捕获并处理乐观锁异常**

```java
@Transactional
public WarehouseDTO getAndUpdateAvailableWarehouse(...) {
    try {
        // ... 库存预留逻辑 ...
        warehouseProductRepository.saveAll(productsToUpdate);
        // ...
    } catch (OptimisticLockException e) {
        logger.warn("Concurrent stock reservation conflict for productId={}, orderId={}. " +
                   "Another order may have reserved the stock.", productId, orderId);
        return null;  // 返回 null，让上层处理
    }
}
```

### **选项2：使用悲观锁（Pessimistic Lock）**

如果需要更强的一致性保证，可以使用悲观锁：

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0")
List<WarehouseProduct> findByProductIdWithLock(@Param("productId") Long productId);
```

**优缺点：**
- ✅ 不会出现并发冲突（排队等待）
- ❌ 性能较差（需要数据库锁）
- ❌ 可能产生死锁

---

## 📝 总结

### **回答你的问题：**

1. **这种情况会发生吗？** 
   - ✅ **会发生**。两个用户可能同时到达库存预留步骤。

2. **谁会成功？**
   - ✅ **先提交到数据库的用户会成功**
   - ❌ **后提交的用户会因为乐观锁冲突而失败**

3. **结果：**
   - ✅ **不会超卖**（乐观锁保证）
   - ✅ **只有一个人会成功**
   - ✅ **另一个人的订单不会创建**（事务回滚）

### **机制保证：**

- ✅ **乐观锁（@Version）** 防止并发冲突
- ✅ **事务（@Transactional）** 保证原子性
- ✅ **异常回滚** 保证数据库一致性

### **用户体验：**

- ⚠️ 失败的用户会收到错误信息
- ⚠️ 可能需要前端提示用户"库存已被占用，请重试"

