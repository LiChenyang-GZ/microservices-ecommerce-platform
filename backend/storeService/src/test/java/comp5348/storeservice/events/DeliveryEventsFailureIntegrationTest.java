package comp5348.storeservice.events;

import com.comp5348.contracts.events.Topics;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import comp5348.storeservice.support.KafkaProbe;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static com.comp5348.contracts.events.DeliveryEventType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 失败场景表（17-第2波A）D1–D6：消费失败分两种（规则 6 / ADR-08）。
 *   - 消息本身有问题 → 直接进 delivery-events.DLT，后面的消息照常处理；
 *   - 暂时性失败（数据库卡了、被锁了）→ 原地重试，不跳过、不进 DLT，而且只卡住这一个分区。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka。D3 会冻结 Postgres 容器几秒，D6 会删掉再重建 DLT topic。
 * 【红灯为什么红】store 还没有 Kafka 消费者：DLT 里什么都不会出现，订单状态也不会变。
 */
class DeliveryEventsFailureIntegrationTest extends DeliveryEventsTestBase {

    private static final String DLT = Topics.deadLetter(Topics.DELIVERY_EVENTS);

    /**
     * D1 + D2：三种坏消息，每种都进 delivery-events.DLT（名字是 ".DLT"，不是 Spring 的默认写法），
     * 带着失败原因；而且排在它们后面的、同一分区的正常消息照样被处理。
     *   1. 根本不是 JSON
     *   2. 是 JSON，但缺必填字段 eventId（契约 EventEnvelope 的构造函数会拒绝）
     *   3. 外壳没问题，但 eventType 说"已揽收"，payload 却说状态是 LOST —— 自相矛盾
     */
    @Test
    void poisonMessage_goesToDotDlt_andNextMessageStillProcessed() {
        PlacedOrder order = placePaidOrder();
        String key = order.orderId().toString(); // 坏消息和后面的好消息用同一个 key → 同一个分区，后面的被前面的挡着

        RecordMetadata notJson = kafka.send(Topics.DELIVERY_EVENTS, key, "this is not json");
        kafka.send(Topics.DELIVERY_EVENTS, key, """
                {"eventType":"PickedUp","orderId":"%s","occurredAt":"%s","payload":{"deliveryId":1,"status":"PICKED_UP"}}
                """.formatted(key, Instant.now()));
        kafka.send(Topics.DELIVERY_EVENTS, key, """
                {"eventId":"8d3c7f0e-1111-4c8e-9f00-000000000001","eventType":"PickedUp","orderId":"%s",
                 "occurredAt":"%s","payload":{"deliveryId":1,"status":"LOST"}}
                """.formatted(key, Instant.now()));
        // 排在三条坏消息后面的正常事件
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);

        // 断言 1：后面的正常消息没有被挡住
        awaitOrderStatus(order, "PICKED_UP");

        // 断言 2：三条坏消息都在 delivery-events.DLT 里，同一个分区号，带着原 topic 和失败原因
        await().atMost(CONSUME_TIMEOUT).until(() -> kafka.recordsOfOrder(DLT, key).size() >= 3);
        List<ConsumerRecord<String, String>> dead = kafka.recordsOfOrder(DLT, key);
        assertThat(dead).hasSize(3);
        assertThat(dead.get(0).value()).isEqualTo("this is not json");
        assertThat(dead).extracting(ConsumerRecord::partition).containsOnly(notJson.partition());
        // 这两个头是 Spring Kafka 写 DLT 时自动加的：原来的 topic、异常信息
        assertThat(dead).extracting(r -> KafkaProbe.header(r, "kafka_dlt-original-topic"))
                .containsOnly(Topics.DELIVERY_EVENTS);
        assertThat(dead).extracting(r -> KafkaProbe.header(r, "kafka_dlt-exception-message"))
                .doesNotContainNull();
    }

    /**
     * D3：数据库暂时不响应（docker pause 冻结 Postgres 容器 8 秒）。
     * 事件不能被跳过、不能进 DLT；数据库回来后照常处理。
     */
    @Test
    void dbDown_messageRetriedInPlace_thenProcessed() throws Exception {
        PlacedOrder order = placePaidOrder();
        var docker = POSTGRES.getDockerClient();
        docker.pauseContainerCmd(POSTGRES.getContainerId()).exec();
        try {
            publish(order, DELIVERY_CREATED);
            publish(order, PICKED_UP);
            Thread.sleep(8_000);
        } finally {
            docker.unpauseContainerCmd(POSTGRES.getContainerId()).exec();
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> orderOf(order).getStatus().name().equals("PICKED_UP"));
        assertThat(kafka.recordsOfOrder(DLT, order.orderId().toString())).as("暂时性失败不进 DLT").isEmpty();
    }

    /**
     * D4（09 · C 中 1）：一个订单卡住（它的订单行被别人锁着），不能拖住其他分区的订单。
     * 规则 6 说的是"卡住这个分区"，不是"卡住所有分区"。
     */
    @Test
    void stuckPartition_doesNotBlockOtherPartitions() throws Exception {
        PlacedOrder stuck = placePaidOrder();
        PlacedOrder other = placePaidOrderOnAnotherPartitionThan(stuck);

        try (Connection locker = lockOrderRow(stuck)) {
            publish(stuck, DELIVERY_CREATED);
            publish(other, DELIVERY_CREATED);

            // 断言 1：另一个分区的订单不受影响，照常处理
            await().atMost(CONSUME_TIMEOUT).until(() -> deliveryIdOf(other) == nullToZero(orderOf(other).getDeliveryId()));
            // 前提：被锁的那一单确实还卡着
            assertThat(orderOf(stuck).getDeliveryId()).isNull();
            locker.rollback(); // 放开锁
        }
        // 断言 2：锁放开后，卡住的那一单也处理了（没有被跳过）
        await().atMost(Duration.ofSeconds(30)).until(() -> deliveryIdOf(stuck) == nullToZero(orderOf(stuck).getDeliveryId()));
    }

    /**
     * D5（09 · C 中 2）：订单行被锁了 15 秒。消费者不能一直干等（占着连接、看起来活着其实卡死）：
     * 每次等锁不超过 10 秒，超时就放弃这一次、退避后重试。锁放开后照常处理。
     * 怎么看"在等锁"：Postgres 的 pg_stat_activity 视图，wait_event_type = 'Lock' 的就是在等锁的查询，
     * now() - query_start 是它已经等了多久。
     */
    @Test
    void lockedRow_consumerTimesOut_andRetries() throws Exception {
        PlacedOrder order = placePaidOrder();
        try (Connection locker = lockOrderRow(order)) {
            publish(order, DELIVERY_CREATED);
            Instant until = Instant.now().plusSeconds(15);
            while (Instant.now().isBefore(until)) {
                Double longestWait = jdbc.queryForObject("""
                        SELECT COALESCE(MAX(EXTRACT(EPOCH FROM now() - query_start)), 0)
                        FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock'
                        """, Double.class);
                assertThat(longestWait).as("有查询等锁等了太久（没有超时）").isLessThan(10.0);
                Thread.sleep(500);
            }
            locker.rollback();
        }
        await().atMost(Duration.ofSeconds(30)).until(() -> deliveryIdOf(order) == nullToZero(orderOf(order).getDeliveryId()));
    }

    /**
     * D10（4a 审查 #2，钱）：Cancelled 补完取消时，要还的那行库存被别的事务锁着（比如同一件商品正好有人在下单）。
     * 等锁 5 秒超时 —— 这是暂时性故障，应该原地退避重试，锁放开后取消照常补完：订单 CANCELLED、退款 1 条、库存还回去。
     *
     * 旧代码错在哪：等锁超时的异常在 WarehouseService.unholdProduct 里（还有 OrderService.finishCancel 里）被 catch 吞掉，
     * 只打一行日志。可异常经过仓库（repository）的事务代理时，Spring 已经把整个共享事务标成"只能回滚"，
     * Postgres 那边这个事务也已经作废了 → 后面的语句或提交报一个"看不出是暂时性"的异常 → 被当成代码 bug，
     * 约 1 秒重试 5 次（每次再等 5 秒锁）→ 进 DLT。结果：订单停在 PAID、没有退款，要人工去 DLT 重放。
     *
     * 锁 35 秒：比"bug 预算"长（1 次 + 重试 5 次，每次等锁 5 秒，约 31 秒），旧代码一定会在锁放开前把消息送进 DLT。
     */
    @Test
    void cancelledEvent_stockRowLocked_isRetriedNotDeadLettered() throws Exception {
        PlacedOrder order = placePaidOrder();
        int stockBefore = stockOf(order);
        try (Connection locker = lockStockRow(order)) {
            publish(order, DELIVERY_CREATED);
            publish(order, CANCELLED);
            Thread.sleep(35_000);
            locker.rollback();
        }

        awaitOrderStatus(order, "CANCELLED", Duration.ofSeconds(30));
        assertThat(kafka.recordsOfOrder(DLT, order.orderId().toString())).as("暂时性故障不能进 DLT").isEmpty();
        assertThat(openTasksOf(order, "REFUND")).as("要退款").hasSize(1);
        assertThat(stockOf(order)).as("锁住的 1 件要还回去").isEqualTo(stockBefore + 1);
    }

    /**
     * D6：写 DLT 这一步本身失败了（这里把 DLT topic 删掉来制造；自动建 topic 是关的，所以写不进去）。
     * 坏消息不能因此被悄悄跳过：DLT 回来后，它要出现在 DLT 里。
     */
    @Test
    void dltPublishFails_recordIsNotSkipped() throws Exception {
        String key = "555000" + System.nanoTime() % 1_000_000;
        kafka.deleteTopic(DLT);
        try {
            kafka.send(Topics.DELIVERY_EVENTS, key, "poison while the DLT is missing");
            Thread.sleep(5_000); // 消费者这段时间写 DLT 一直失败
        } finally {
            kafka.createTopics(List.of(DLT));
        }

        ConsumerRecord<String, String> dead = awaitDeadLetter(key, Duration.ofSeconds(90));
        assertThat(dead.value()).isEqualTo("poison while the DLT is missing");
    }

    // ---------- 工具 ----------

    /** 再下一单付好款，直到它和 first 落在不同分区（3 个分区，一般一两次就够） */
    private PlacedOrder placePaidOrderOnAnotherPartitionThan(PlacedOrder first) {
        int p = KafkaProbe.partitionFor(first.orderId().toString());
        for (int i = 0; i < 20; i++) {
            PlacedOrder candidate = placePaidOrder();
            if (KafkaProbe.partitionFor(candidate.orderId().toString()) != p) {
                return candidate;
            }
        }
        throw new IllegalStateException("could not find an order on another partition");
    }

    /**
     * 测试自己开一个数据库连接，在一个不提交的事务里 SELECT ... FOR UPDATE 锁住这一单的订单行，
     * 就像别的事务正锁着它。返回的连接关闭（或 rollback）时锁才放开。
     */
    private Connection lockOrderRow(PlacedOrder order) throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM orders WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, order.orderId());
            ps.executeQuery();
        }
        return c;
    }

    /** 同上，锁的是这一单那件商品的库存行（warehouse_products），取消时 unhold 要锁它才能把库存加回去 */
    private Connection lockStockRow(PlacedOrder order) throws Exception {
        Connection c = DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        c.setAutoCommit(false);
        try (PreparedStatement ps = c.prepareStatement("SELECT id FROM warehouse_products WHERE id = ? FOR UPDATE")) {
            ps.setLong(1, order.warehouseProductId());
            ps.executeQuery();
        }
        return c;
    }

    private static long nullToZero(Long v) {
        return v == null ? 0L : v;
    }
}
