# ADR-21 · 表结构改动在启动时跑幂等 SQL，不用 Flyway

[English](21-schema-changes-at-startup.md) | **中文**

**状态：** 已采纳

## 背景
各服务用 Hibernate 的 `ddl-auto=update` 建表。它会加新表、新列，但对已经存在的表，从不去掉 `NOT NULL`，加唯一约束失败也只打一行日志。第 2 波恰好要在已有数据的库上做这几种改动：配送单的 `order_id` 唯一（ADR-07）、取消标记需要客户字段可以为空（ADR-09）、store 的状态检查约束要加新状态（LOST、REFUNDED）。全新的测试库看不出问题，长期用的 compose 卷就会出问题（发现 B11）。

可选：(a) 每个服务启动时跑一段幂等 SQL；(b) 引入 Flyway，要给现有的库做 baseline，还多一个工具；(c) 重建数据卷，数据清空，也测不到"升级"这件事。

## 决定
选 (a)（Christy 选的）。Hibernate 建完或更新完表之后、Kafka 监听器启动之前，服务跑几条可以重复执行的语句：`CREATE UNIQUE INDEX IF NOT EXISTS`、`DROP NOT NULL`、替换检查约束。必需的索引建不起来时（比如一个订单已经有两条配送单），故意让启动失败：没有这个索引还继续跑，去重就悄悄失效了。测试先造出旧的表结构，再验证升级。

## 代价与影响
- 已有的库原地升级，谁都不用 `down -v`。
- 没有迁移历史表。改动少的时候够用；多起来以后，Flyway（或 Liquibase）是更合适的工具。
- delivery 的 `db/migration` 下那个旧的 `V1__…sql` 没有任何东西会执行（项目里没有 Flyway）。

## 代码在哪
- delivery [DeliverySchemaUpgrade.java](../../backend/deliveryService/src/main/java/com/comp5348/delivery/config/DeliverySchemaUpgrade.java)、store [OrderStatusCheckUpgrade.java](../../backend/storeService/src/main/java/comp5348/storeservice/config/OrderStatusCheckUpgrade.java)
