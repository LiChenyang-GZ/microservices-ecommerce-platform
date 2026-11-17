# ADR-20 · 扣库存用悲观行锁

[English](20-stock-pessimistic-lock.md) | **中文**

**状态：** 已采纳

## 背景
库存行原来用乐观锁（`@Version`）。测试：库存 10 件，8 个人同时各买 1 件，只有 1 单成功，另外 7 单抛 `ObjectOptimisticLockingFailureException`。没有超卖，但明明有货却把顾客拒了，商品越热门越严重。

## 决定
占用和释放库存时，用 `SELECT … FOR UPDATE` 锁住仓库商品行，并且总是按 id 顺序加锁，两个订单不会死锁。并发的订单排队等锁，而不是直接失败。

## 代价与影响
- 库存够的时候每一单都能成功，库存也永远不会变成负数。
- 同一商品的订单在锁住的那一小段时间里排队执行。
- 实体上的 `@Version` 保留，防其他写入方。

## 代码在哪
- [WarehouseService.java](../../backend/storeService/src/main/java/comp5348/storeservice/service/WarehouseService.java)、[WarehouseProductRepository.java](../../backend/storeService/src/main/java/comp5348/storeservice/repository/WarehouseProductRepository.java)
- 测试：`StockHoldConcurrencyIntegrationTest`、`UnholdConcurrencyIntegrationTest`
- [docs/learning/04-inventory-pessimistic-lock.zh-CN.md](../learning/04-inventory-pessimistic-lock.zh-CN.md)
