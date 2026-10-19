# 04 · Holding stock with a pessimistic lock: enough stock means the order succeeds

**English** | [中文](04-inventory-pessimistic-lock.zh-CN.md)

> Commits: `2db8299 test(red)` → `3170242 feat` (PR #13)
> Replay time: **30 minutes**
> Prerequisite: sections 2.4–2.6 of [01 · Idempotent payments](01-idempotent-payments.md) (pessimistic lock, fixed lock order, READ COMMITTED). This step uses the same tools on stock.

---

## 1. Problem

**A hot product has 10 in stock. 8 people buy 1 each at almost the same time. Only 1 order succeeds and the other 7 fail.**

- Reproducing test: [StockHoldConcurrencyIntegrationTest.java:111](../../backend/storeService/src/test/java/comp5348/storeservice/inventory/StockHoldConcurrencyIntegrationTest.java#L111)
- Red: `expected: 8, but was: 1`. All 7 failures are `ObjectOptimisticLockingFailureException`.
- No overselling (stock never goes negative), but customers are turned away while there is stock. The hotter the product, the more people fail.

---

## 2. What it is (framework layer)

### 2.1 Old code: optimistic lock with `@Version`
**Plain words**: each row has a version number. A save says "update only if the version is still the one I read". If not, that's a conflict.
- Field: [WarehouseProduct.java:35](../../backend/storeService/src/main/java/comp5348/storeservice/model/WarehouseProduct.java#L35)
- Hibernate saves with `UPDATE warehouse_products SET quantity=?, version=version+1 WHERE id=? AND version=?`. Zero rows updated → `OptimisticLockException`, which Spring wraps as `ObjectOptimisticLockingFailureException`.
- Old stock hold: plain `SELECT` → subtract in memory → write back with the version. All 8 read the same version. The first commit bumps it, and the other 7 update zero rows and fail.

### 2.2 New code: `@Lock(PESSIMISTIC_WRITE)` → `SELECT … FOR UPDATE`
- New query: [WarehouseProductRepository.java:25](../../backend/storeService/src/main/java/comp5348/storeservice/repository/WarehouseProductRepository.java#L25)
  ```java
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select wp from WarehouseProduct wp where wp.product.id = :productId and wp.quantity > 0 order by wp.id")
  List<WarehouseProduct> findByProductIdForUpdate(...);
  ```
- Holding stock calls it first: [WarehouseService.java:194](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java#L194)
- The lock is held until the order transaction commits (`@Transactional` on `OrderService.createOrder`; the stock method joins that transaction).

### 2.3 After waiting, you read the new quantity (READ COMMITTED re-check)
- Buyer 2 waits on `FOR UPDATE`. When buyer 1 commits, Postgres does **not** hand buyer 2 the old data. It re-checks the `WHERE` (`quantity > 0`) against the **newly committed version** of the row, and if it still matches, locks and returns that version.
- So buyer 2 reads 9 with the new version number, and `@Version` matches on commit. No conflict.
- That's why we **don't need** SERIALIZABLE.

### 2.4 Why `order by wp.id`, not `order by quantity`
- **Lock in id order to avoid deadlock**: a product can have stock rows in several warehouses. If every order locks them from the smallest id up, one can't hold 1 and wait for 2 while another holds 2 and waits for 1. Same idea as `lockAccounts` in the bank.
- **Don't sort by a column others are changing**: the Postgres docs warn that `ORDER BY` + `FOR UPDATE` sorts first, then locks row by row. If the sort column changes while you wait, rows can come back out of order. `id` never changes. `quantity` is exactly the column others are changing.
- So: lock by id in SQL, then sort by the fresh quantity in memory ([WarehouseService.java:195](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java#L195)). The "take from the smallest stock first" rule is unchanged.

### 2.5 Why we can trust the value is fresh (first-level cache)
- Within one transaction, if Hibernate already loaded an entity, querying it again returns the cached object. The freshly queried data does not overwrite it (the ➕ item in doc 01).
- Safe here: before holding stock, the order transaction only runs a `SUM` query ([OrderService.java:114](../../backend/storeService/src/main/java/comp5348/storeservice/service/OrderService.java#L114)), so no `WarehouseProduct` entity is in the cache. **If someone later adds a `findById` before this, the lock could read a stale quantity.**

---

## 3. Request path

```
POST /api/store/orders/create-with-payment → StoreController.createOrderWithPayment (controller/StoreController.java:38)
  → OrderProductService.createOrderWithPayment                        (service/OrderProductService.java:67)
    → OrderService.createOrder  @Transactional  ── transaction starts (service/OrderService.java:99)
        SUM(quantity): rough check that there is enough
        save temporary order PENDING_STOCK_HOLD
        → WarehouseService.getAndUpdateAvailableWarehouse            (service/WarehouseService.java:187)
            SELECT … FOR UPDATE (by id)  ← other orders queue here      (:194)
            sort by quantity in memory, take from each warehouse, write HOLD rows
        order → PLACED
    ← commit: stock update + order + HOLD rows together; lock released
```

---

## 4. Why this way

| Option | Result | Why not chosen |
|---|---|---|
| Keep `@Version`, retry on conflict | Works, but 8 buyers retry many rounds; hotter = slower | When conflicts are normal, optimistic locking is wasted work |
| 🤖 `SELECT … FOR UPDATE` (chosen) | Orders queue and go one by one | The lock lives only in one order transaction, with no external calls inside, so it's short |
| One atomic SQL: `UPDATE … SET quantity = quantity - ? WHERE id = ? AND quantity >= ?` | Fastest, no read first | Holding spans several warehouses and writes a HOLD row and audit log per warehouse; a big change. Worth it for one warehouse under heavy load |
| `synchronized` | Works for one instance | Can't see other JVMs when store runs more than one instance |
| Redis distributed lock / Redis pre-deduct | For flash-sale traffic | Another component to run; the database row lock is enough now |

🤖 Choices the AI made:
1. Pessimistic lock instead of "optimistic + retry".
2. Lock by id in SQL, sort by quantity in memory.
3. Keep `@Version`: the admin path (`updateProductQuantity`) still relies on it. The pessimistic path also bumps the version, so they don't interfere.
4. `unholdProduct` (returning stock on cancel) is **not** changed here. It's in the backlog and needs its own red test first (later fixed in #15, see below).

Known, not fixed (in the Obsidian backlog):
- `unholdProduct` still uses a plain read + `@Version`, so it can conflict with an order for the same product. The conflict is thrown at flush/commit, and unhold joins the caller's transaction (`cancelOrder`, `CompensationService`) → the whole cancel may roll back, or a `catch` may swallow it and the stock is not returned. 
  **Confirmed by the red test in #15: the whole cancel rolls back.** The order is not cancelled and stock is not returned, while the logs already said success. It is not swallowed by a `catch`, because the UPDATE runs at commit, after the method has returned and left the try. Fixed in #15: unhold also locks with `FOR UPDATE` before reading, in the same order as holding.
- `updateProductQuantity` uses `@Async` + `CompletableFuture.supplyAsync`. The code inside runs on another thread, so the method's `@Transactional` doesn't cover it (transactions are bound to a thread).

---

## 5. Interview topics here

**Verdict: this step needed a real change — the hot stock row moved from optimistic to pessimistic locking. Nothing heavier: no SERIALIZABLE, no Redis, no synchronized.**

| Topic | Where | The call made here |
|---|---|---|
| 📒 E3 optimistic vs pessimistic | `WarehouseProductRepository.java:25` | Pessimistic: conflicts are normal on a hot product; optimistic failed 7 of 8 (the test shows it). **Not changed**: admin edits, conflicts are rare, keep `@Version` |
| 📒 E3 follow-up synchronized | same | Two orders may hit two instances → the lock lives in the shared database |
| 📒 B6 deadlock | `order by wp.id` | Lock warehouse rows in a fixed order, which breaks circular wait. **No need** for tryLock + timeout |
| 📒 E4 isolation | re-check of `WHERE` after waiting | Default READ COMMITTED reads the new quantity; **no need** for SERIALIZABLE |
| 📒 D4 @Transactional | lock scope = `createOrder` transaction | The lock is released when the transaction ends, so no external calls inside. Counter-example: `supplyAsync` in `updateProductQuantity` switches thread, so the transaction doesn't apply |
| 📒 G4 Testcontainers | test uses real Postgres | Row locks and the re-check after waiting only show up on a real database |
| ➕ `ORDER BY` + `FOR UPDATE` | `order by wp.id` | Sort first, lock after; sorting by a changing column can come back out of order → sort by the fixed id |
| ➕ Atomic UPDATE decrement | not used, section 4 | Fastest way to decrement one row; a big change when spreading across warehouses |

Scenario anchors:
- "A hot product had ten in stock and eight buyers at once. With optimistic locking, seven of them failed even though there was enough stock."
- "I switched to SELECT FOR UPDATE, locked in id order. The orders queue up, and each one reads the latest quantity. Optimistic locking stays for admin edits."

---

## 6. Follow-up chain

1. How do you stop overselling? → `@Version` already did. So what was wrong? → Orders failed with stock left.
2. Why pessimistic? → Conflicts are normal. Cost? → Orders wait; nothing slow may run while the lock is held.
3. After waiting, does buyer 2 see the old or new quantity? → READ COMMITTED re-checks and returns the new version.
4. A product in several warehouses? → Lock in id order. Why not sort by quantity? → A changing sort column can come back out of order.
5. Ten times the traffic? → Atomic UPDATE, or Redis pre-deduct with async write-back. Not needed now.

---

## 7. How to say it

- "Placing an order holds stock. It used to rely on a version column, so concurrent orders for a hot product mostly failed."
- "Now it locks the stock rows with SELECT FOR UPDATE, in id order. Orders wait in line, and nobody fails while there's stock left."
- "A second test checks we never oversell: three in stock, eight buyers, exactly three succeed."

---

## 8. References

- `FOR UPDATE`, `ORDER BY` and lock order: [PostgreSQL · SELECT · The Locking Clause](https://www.postgresql.org/docs/current/sql-select.html#SQL-FOR-UPDATE-SHARE)
- Re-checking `WHERE` after waiting, reading the new version: [PostgreSQL · Read Committed Isolation Level](https://www.postgresql.org/docs/current/transaction-iso.html#XACT-READ-COMMITTED)
- Consistent lock order to avoid deadlock: [PostgreSQL · 13.3 Explicit Locking](https://www.postgresql.org/docs/current/explicit-locking.html)
- `@Lock` on repository methods: [Spring Data JPA · Locking](https://docs.spring.io/spring-data/jpa/reference/jpa/locking.html)

---

## 9. Self-check

1. Did the old code oversell? Why isn't the red "stock went negative"?
<details><summary>Answer</summary>No. <code>@Version</code> makes the later commit update zero rows and roll back, so stock never goes negative. The problem is the other direction: buyers failed while there was stock. The safety-net test <code>notEnoughStock_…</code> was already green before the change.</details>

2. If the query used `order by wp.quantity`, would the single-warehouse test still pass? What goes wrong with several warehouses?
<details><summary>Answer</summary>With one warehouse there is one row, so order doesn't matter and it stays green. With several, quantity is the column others are changing: sorted first, then locked, the rows can come back out of order after waiting, and two orders may lock in different orders, which risks deadlock. So SQL sorts by the fixed id and memory sorts by fresh quantity.</details>

3. Someone adds `warehouseProductRepository.findById(...)` in `createOrder` before holding stock. What happens?
<details><summary>Answer</summary>That entity is now in Hibernate's first-level cache. The later <code>FOR UPDATE</code> locks the row and the database returns the new quantity, but Hibernate hands back the cached old object. You compute with the old quantity and save with the old version → back to optimistic-lock conflicts. Rule: lock the first time a transaction reads the row.</details>

---

## 10. Replay time

**30 minutes**: checkout `2db8299`, run red (5) → read the diff and section 2 (10) → checkout `3170242`, run green (5) → cover the "call made" column in section 5 and say it yourself (5) → say it in English, do the self-check (5).
