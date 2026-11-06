# ADR-20 · Stock is reserved with pessimistic row locks

**English** | [中文](20-stock-pessimistic-lock.zh-CN.md)

**Status:** Accepted

## Context
Stock rows used optimistic locking (`@Version`). A test with 10 in stock and 8 customers buying one each at the same moment showed only 1 order succeeding; the other 7 failed with `ObjectOptimisticLockingFailureException`. Nothing was oversold, but customers were turned away while there was stock, and the hotter the product, the worse it got.

## Decision
Reserving and releasing stock locks the warehouse-product rows with `SELECT … FOR UPDATE`, always in id order so two orders cannot deadlock. Concurrent orders queue for the lock instead of failing.

## Consequences
- With enough stock, every order succeeds; stock still never goes negative.
- Orders for the same product are serialized for the short time the rows are locked.
- `@Version` stays on the entities as a guard for other writers.

## In the code
- [WarehouseService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java), [WarehouseProductRepository.java](../../backend/storeService/src/main/java/comp5348/storeservice/repository/WarehouseProductRepository.java)
- Tests: `StockHoldConcurrencyIntegrationTest`, `UnholdConcurrencyIntegrationTest`
- [docs/learning/04-inventory-pessimistic-lock.md](../learning/04-inventory-pessimistic-lock.md)
