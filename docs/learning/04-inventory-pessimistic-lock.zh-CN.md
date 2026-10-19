# 04 · 扣库存改悲观锁：库存够，就不该下单失败

[English](04-inventory-pessimistic-lock.md) | **中文**

> 对应 commit：`2db8299 test(red)` → `3170242 feat`（PR #13）
> 预计回放时间：**30 分钟**
> 前置：[01 · 幂等扣款与退款](01-idempotent-payments.zh-CN.md) 的 2.4–2.6（悲观锁、固定加锁顺序、READ COMMITTED）。这一步是同一套工具用在库存上。

---

## 1. 问题

**一个热门商品库存 10，8 个人几乎同时各买 1 件，结果只有 1 单成功，其他 7 单报错。**

- 复现测试：[StockHoldConcurrencyIntegrationTest.java:111](../../backend/storeService/src/test/java/comp5348/storeservice/inventory/StockHoldConcurrencyIntegrationTest.java#L111)
- 红灯：`expected: 8, but was: 1`，7 个失败全是 `ObjectOptimisticLockingFailureException`。
- 不会超卖（库存没变负数），但库存明明够，顾客却被拒绝。商品越热门，失败的人越多。

---

## 2. 是什么（框架层）

### 2.1 旧代码：乐观锁 `@Version`
**大白话**：每行带一个版本号。保存时说"只有版本号还是我读到的那个才更新"，不是就算冲突。
- 字段：[WarehouseProduct.java:35](../../backend/storeService/src/main/java/comp5348/storeservice/model/WarehouseProduct.java#L35)
- Hibernate 保存时生成 `UPDATE warehouse_products SET quantity=?, version=version+1 WHERE id=? AND version=?`。更新到 0 行 → 抛 `OptimisticLockException`，Spring 再包成 `ObjectOptimisticLockingFailureException`。
- 旧的扣库存：普通 `SELECT` 读出数量 → 内存里减 → 提交时带着版本号写回。8 个人读到同一个版本，第一个提交后版本 +1，其余 7 个全部 0 行 → 全部失败。

### 2.2 新代码：`@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`
- 新查询：[WarehouseProductRepository.java:25](../../backend/storeService/src/main/java/comp5348/storeservice/repository/WarehouseProductRepository.java#L25)
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0 order by wp.id")
  List<WarehouseProduct> findByProductIdForUpdate(...);
  ```
- 扣库存先调它：[WarehouseService.java:194](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java#L194)
- 锁一直持有到下单事务提交（`OrderService.createOrder` 上的 `@Transactional`，扣库存的方法加入同一个事务）。

### 2.3 等锁之后读到的是新数量（READ COMMITTED 的重新检查）
- 第 2 个人在 `FOR UPDATE` 上等。第 1 个人提交后，Postgres **不会**把旧的那份数据给他，而是拿这一行**刚提交的新版本**重新检查 `WHERE`（`quantity > 0`），满足就锁住、返回新版本。
- 所以第 2 个人读到 9、版本号也是新的，提交时 `@Version` 对得上，不冲突。
- 这就是为什么**不需要**把隔离级别调成 SERIALIZABLE。

### 2.4 为什么 `order by wp.id`，而不是 `order by quantity`
- **按 id 加锁防死锁**：一个商品可能在好几个仓库有库存行。两单都按 id 从小到大锁，就不会一个先锁 1 再等 2、另一个先锁 2 再等 1。和 bank 的 `lockAccounts` 同一个道理。
- **排序列不能是会被改的列**：Postgres 文档提醒，`ORDER BY` + `FOR UPDATE` 是先排序、再逐行加锁；等锁期间如果排序列被别人改了，返回的顺序就不对了。`id` 永远不变，`quantity` 正是别人在改的列。
- 所以：SQL 里按 id 锁，拿到最新数量后再在内存里按数量排序（[WarehouseService.java:195](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java#L195)），"先从库存少的仓库拿"的规则不变。

### 2.5 为什么能信这次读到的是数据库里的新值（一级缓存）
- Hibernate 同一个事务里，已经加载过的实体再查一次，返回的是缓存里的那个对象，不会用新查回来的数据覆盖它（01 文档的 ➕ 那条）。
- 这里安全：下单事务在扣库存之前只用 `SUM` 查过总数（[OrderService.java:114](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L114)），没有把 `WarehouseProduct` 实体加载进缓存。**以后谁在这之前加了一句 `findById`，这把锁就可能读到旧数量。**

---

## 3. 请求路径导读

```
POST /api/store/orders/create-with-payment → StoreController.createOrderWithPayment (controller/StoreController.java:38)
  → OrderProductService.createOrderWithPayment                        (service/OrderProductService.java:67)
    → OrderService.createOrder  @Transactional  ── 事务从这里开始     (service/OrderService.java:99)
        SUM(quantity) 粗查库存够不够                                  (:114)
        保存临时订单 PENDING_STOCK_HOLD
        → WarehouseService.getAndUpdateAvailableWarehouse            (service/WarehouseService.java:187)
            SELECT … FOR UPDATE（按 id）  ← 别的下单在这里排队          (:194)
            内存里按数量排序、逐个仓库扣、写 HOLD 流水
        订单改成 PLACED
    ← 提交：库存更新 + 订单 + HOLD 流水一起落库，锁释放
```

---

## 4. 为什么这样用

| 方案 | 结果 | 为什么没选 |
|---|---|---|
| 保留 `@Version`，冲突后自动重试 | 能成功，但 8 个人要重试很多轮，越热门越慢 | 冲突是常态时，乐观锁就是在白做功 |
| 🤖 `SELECT … FOR UPDATE`（选了） | 排队，一个接一个扣 | 锁只在一次下单事务里，事务里没有调外部服务，锁很短 |
| 一条原子 SQL：`UPDATE … SET quantity = quantity - ? WHERE id = ? AND quantity >= ?` | 最快，不用先读 | 扣库存要跨多个仓库分配，还要写每个仓库的 HOLD 流水和审计日志，拆成单条 UPDATE 改动大。单仓库、高并发时值得换 |
| `synchronized` | 单实例能用 | 多个 store 实例时管不到别的 JVM |
| Redis 分布式锁 / Redis 预扣库存 | 秒杀级流量才需要 | 多一个要维护的组件；现在数据库行锁足够 |

🤖 AI 替你选的：
1. 悲观锁而不是"乐观锁 + 重试"。
2. SQL 按 id 锁、内存按数量排。
3. `@Version` 留着：管理员改库存那条路径（`updateProductQuantity`）还靠它，悲观锁的路径也会让版本号 +1，两边互不干扰。
4. `unholdProduct`（取消时还库存）这次**没改**，单独记进待办，要先有红灯。

已知、没修（记在 Obsidian 待办）：
- `unholdProduct` 仍是普通读 + `@Version`，和同商品的下单撞上会冲突。冲突在 flush / 提交时才抛，而 unhold 加入的是调用方（`cancelOrder`、`CompensationService`）的事务 → 可能整个取消回滚，也可能被某层 `catch` 吞掉、库存没还。哪一种要先写红灯测试确认。
- `updateProductQuantity` 用 `@Async` + `CompletableFuture.supplyAsync`：里面的代码跑在另一个线程上，方法上的 `@Transactional` 管不到它（事务绑定在线程上）。

---

## 5. 八股在这里

**结论：这一步真需要优化——热点库存行从乐观锁换成悲观锁。不需要更重的工具：不用 SERIALIZABLE、不用 Redis、不用 synchronized。**

| 八股 | 代码在哪 | 这里的判断 |
|---|---|---|
| 📒 E3 乐观 vs 悲观 | `WarehouseProductRepository.java:25` | 选了悲观：热门商品冲突是常态，乐观锁让 8 个人里 7 个失败（测试证明）。**不换**的地方：管理员改库存，冲突少，继续 `@Version` |
| 📒 E3 追问 synchronized | 同上 | 两个下单可能在两个实例上 → 锁要放在共享的数据库里 |
| 📒 B6 死锁 | `order by wp.id` | 多个仓库行按固定顺序锁，破坏循环等待。**不需要** tryLock + 超时 |
| 📒 E4 隔离级别 | 等锁后重新检查 `WHERE` | 默认 READ COMMITTED 就能读到新数量；**不需要** SERIALIZABLE |
| 📒 D4 @Transactional | 锁的范围 = `createOrder` 的事务 | 锁随事务结束释放，所以事务里不能调外部服务。反例：`updateProductQuantity` 里的 `supplyAsync` 换了线程，事务失效 |
| 📒 G4 Testcontainers | 测试用真 Postgres | 行锁、等锁后重新检查，只有真数据库测得出来 |
| ➕ `ORDER BY` + `FOR UPDATE` | `order by wp.id` | 先排序后加锁，按会被改的列排会乱序 → 按不变的 id |
| ➕ 原子 UPDATE 扣减 | 没用，第 4 节 | 单行扣减最快的写法；跨仓库分配时改动大 |

英文场景锚点：
- "A hot product had ten in stock and eight buyers at once. With optimistic locking, seven of them failed even though there was enough stock."
- "I switched to SELECT FOR UPDATE, locked in id order. The orders queue up, and each one reads the latest quantity. Optimistic locking stays for admin edits."

---

## 6. 面试追问链

1. 怎么防超卖？→ 原来就有 `@Version`，不会超卖。那问题是什么？→ 库存够也失败。
2. 为什么改悲观？→ 冲突是常态。代价？→ 排队，慢一些；锁着的事务里不能做慢事。
3. 第 2 个人等完锁，读到的是旧数量还是新数量？→ READ COMMITTED 下重新检查，读新版本。
4. 一个商品在多个仓库？→ 按 id 顺序锁，防死锁。为什么不按数量排？→ 排序列被改会乱序。
5. 流量再大 10 倍？→ 原子 UPDATE、或 Redis 预扣 + 异步落库。现在不需要。

---

## 7. 英文说法

- "Placing an order holds stock. It used to rely on a version column, so concurrent orders for a hot product mostly failed."
- "Now it locks the stock rows with SELECT FOR UPDATE, in id order. Orders wait in line, and nobody fails while there's stock left."
- "A second test checks we never oversell: three in stock, eight buyers, exactly three succeed."

---

## 8. 资料

- `FOR UPDATE`、`ORDER BY` 和加锁的顺序问题：[PostgreSQL · SELECT · The Locking Clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- 等锁后重新检查 `WHERE`、读到新版本：[PostgreSQL · Read Committed Isolation Level](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED)
- 固定顺序加锁防死锁：[PostgreSQL · 13.3 Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- `@Lock` 写在 repository 方法上：[Spring Data JPA · Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)

---

## 9. 自测题

1. 旧代码会超卖吗？为什么红灯不是"库存变负数"？
<details><summary>答案</summary>不会。<code>@Version</code> 让后提交的人更新到 0 行、整单回滚，库存不会变负数。问题是反方向的：库存够的人也失败了。安全网测试 <code>notEnoughStock_…</code> 在改之前就是绿的。</details>

2. 如果把查询改成 `order by wp.quantity`，单仓库的测试还会绿吗？多仓库会出什么问题？
<details><summary>答案</summary>单仓库只有一行，顺序无所谓，还是绿的。多仓库时，数量是正在被别人改的列：先排序再等锁，等完后返回的顺序可能已经不是按数量排的；而且两单的加锁顺序可能不一致，有死锁风险。所以 SQL 按不变的 id，内存里再按新数量排。</details>

3. 有人在 `createOrder` 里扣库存之前加了一句 `warehouseProductRepository.findById(...)`，会发生什么？
<details><summary>答案</summary>这个实体进了 Hibernate 一级缓存。之后 <code>FOR UPDATE</code> 虽然锁住了行、数据库返回了新数量，但 Hibernate 给你的是缓存里的旧对象。用旧数量算、带旧版本号保存 → 又回到乐观锁冲突。规则：每个事务第一次读这一行时就加锁。</details>

---

## 10. 预计回放时间

**30 分钟**：checkout `2db8299` 跑红（5 分钟）→ 读 diff 和第 2 节（10 分钟）→ checkout `3170242` 跑绿（5 分钟）→ 第 5 节遮住"判断"自己说（5 分钟）→ 英文说一遍、做自测题（5 分钟）。
