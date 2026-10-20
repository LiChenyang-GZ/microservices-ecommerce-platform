package com.comp5348.delivery.events;

import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.DeliveryEventPayload;
import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.support.KafkaProbe;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表（17-第2波A）P1–P6、A1、A2：delivery 改了状态，就一定把事件发到 delivery-events，而且只发一次、顺序对。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka（见 DeliveryIntegrationTest）。
 * 【红灯为什么红】delivery 现在根本不往 Kafka 发任何东西（还在用 webhook + notification_queue），
 *   所以每个测试都会在"等事件出现"那一步等满 10 秒后失败。
 */
class DeliveryEventPublishingIntegrationTest extends DeliveryIntegrationTest {

    @Autowired PlatformTransactionManager transactionManager;

    /**
     * A1：每一次状态变化都有对应的事件。建单 → 揽收 → 运输 → 签收，四步四条事件。
     * 设计：时序图 A 的注释"每一步都是 改状态 + 写事件 一个事务"；推进顺序第 4 步"delivery 的 event_outbox"。
     */
    @Test
    void everyStatusChange_publishesItsEvent() {
        String orderId = newOrderId();
        Long deliveryId = createDelivery(orderId);
        // worker 推进三步（每一步：sleep 一会儿 → CREATED→PICKED_UP→DELIVERING→RECEIVED）
        worker.processDelivery(deliveryId);
        worker.processDelivery(deliveryId);
        worker.processDelivery(deliveryId);
        // 前提：数据库里确实走到了签收
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.RECEIVED);

        // 断言 1：四条事件，名字和顺序都对（名字来自契约 DeliveryEventType）
        assertThat(awaitEventTypes(orderId, 4))
                .containsExactly("DeliveryCreated", "PickedUp", "Delivering", "Received");

        // 断言 2：payload 里的 deliveryId 和 status 对得上（契约 DeliveryEventPayload）
        List<DeliveryEventPayload> payloads = kafka.recordsOfOrder(Topics.DELIVERY_EVENTS, orderId).stream()
                .map(r -> payloadOf(parse(r)))
                .toList();
        assertThat(payloads).extracting(DeliveryEventPayload::deliveryId).containsOnly(deliveryId);
        assertThat(payloads).extracting(DeliveryEventPayload::status)
                .containsExactly("CREATED", "PICKED_UP", "DELIVERING", "RECEIVED");
    }

    /**
     * P6：同一订单的事件落在同一个分区，key = orderId，顺序 = 发生的顺序。
     * 设计：规则 5 / ADR-07"所有事件用 orderId 做分区 key"；规则 3"按 id 顺序逐条发送"。
     */
    @Test
    void sameOrderEvents_samePartition_inIdOrder() {
        String orderId = newOrderId();
        Long deliveryId = createDelivery(orderId);
        worker.processDelivery(deliveryId);   // PickedUp
        worker.processDelivery(deliveryId);   // Delivering

        awaitEventTypes(orderId, 3);
        List<ConsumerRecord<String, String>> records = kafka.recordsOfOrder(Topics.DELIVERY_EVENTS, orderId);

        // 断言 1：key 就是 orderId，外壳里的 orderId 也一样（契约 EventEnvelope：orderId 同时是 Kafka key）
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(orderId);
        assertThat(records).extracting(r -> parse(r).orderId()).containsOnly(orderId);
        // 断言 2：三条都在 Kafka 默认分区器为这个 key 选的那个分区里
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(KafkaProbe.partitionFor(orderId));
        // 断言 3：offset 先后 = 事件发生先后
        assertThat(records).extracting(r -> parse(r).eventType())
                .containsExactly("DeliveryCreated", "PickedUp", "Delivering");
    }

    /**
     * P1：改状态和写事件同生共死。业务事务回滚了，就不能有事件；提交了，就一定有事件。
     * 设计：规则 2"改状态和写 event_outbox 必须在同一个事务里，不允许改完数据库再直接发 Kafka"。
     * 反例：如果实现是"改完状态直接 kafkaTemplate.send"，回滚的那次取消也会发出一条 Cancelled，这里就会看到两条。
     */
    @Test
    void stateChange_andEvent_commitTogetherOrNotAtAll() throws Exception {
        String orderId = newOrderId();
        createDelivery(orderId);

        // 第一次取消：在一个事务里做，然后故意回滚（模拟"改完状态，事务里后面某一步出错了"）
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            deliveryService.cancelByOrderId(orderId);
            tx.setRollbackOnly();
        });
        // 前提：回滚生效，配送单还是 CREATED
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CREATED);

        // 第二次取消：正常提交
        deliveryService.cancelByOrderId(orderId);

        // 断言：只有建单和"提交了的那次"取消，各一条
        awaitEventTypes(orderId, 2);
        Thread.sleep(1_000); // 多等一会儿，确认没有迟到的第三条
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated", "Cancelled");
    }

    /**
     * P2：Kafka 停了一段时间。状态照样能改（不依赖 Kafka），事件先留在 event_outbox；Kafka 回来后按原来的顺序补发。
     * 设计：规则 3 + 交互表 #4"停在发送失败的那一行，下一轮从它开始"。
     * 怎么"停 Kafka"：docker pause 冻结 Kafka 容器（进程还在，但不响应），比真的删掉容器更快恢复。
     */
    @Test
    void kafkaDown_publisherStopsAtFailedRow_thenResumesInOrder() throws Exception {
        String orderId = newOrderId();
        var docker = KAFKA.getDockerClient();
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            // Kafka 不可用期间：建单、取消，两次状态变化都应该成功提交到数据库
            createDelivery(orderId);
            deliveryService.cancelByOrderId(orderId);
            assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
            Thread.sleep(3_000); // 让投递程序在 Kafka 不可用时试几轮、失败几轮
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        // 断言：Kafka 回来后两条都补发出去了，先建单后取消，没有丢、没有乱序
        org.awaitility.Awaitility.await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> eventTypesOf(orderId).size() >= 2);
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated", "Cancelled");
    }

    /**
     * P3：事件发出去了，但"标记已发送"没提交成功（这里用 SQL 把标记清掉来模拟），下一轮会再发一次。
     * 设计：规则 4"Kafka 只保证至少投递一次，所以每个消费者都要能处理重复的消息"。
     * 这个测试证明"会重复"是真的，所以消费者必须去重（store 那边的 duplicateEvent_* 测试证明去重有效）。
     * 两次是同一个 eventId：消费者正是靠它（或状态不倒退）认出重复。
     * 表名、列名在这里定下来：event_outbox.order_id、event_outbox.sent_at（null = 还没发）。
     */
    @Test
    void sentButNotMarked_isSentAgain() {
        String orderId = newOrderId();
        createDelivery(orderId);
        awaitEventTypes(orderId, 1);

        // 模拟"发出去了，但标记没提交"：把这条事件重新标成"未发送"
        int reset = jdbc.update("UPDATE event_outbox SET sent_at = NULL WHERE order_id = ?", orderId);
        assertThat(reset).as("event_outbox 里应该正好有这一条").isEqualTo(1);

        // 断言：又发了一次，两次的 eventId 相同
        assertThat(awaitEventTypes(orderId, 2)).containsExactly("DeliveryCreated", "DeliveryCreated");
        List<ConsumerRecord<String, String>> records = kafka.recordsOfOrder(Topics.DELIVERY_EVENTS, orderId);
        assertThat(parse(records.get(0)).eventId()).isEqualTo(parse(records.get(1)).eventId());
    }

    /**
     * P4：另一个实例拿着锁的时候，这个实例一条都不发；锁放开后才发，而且只发一次。
     * 设计：规则 3 / ADR-06"每一轮开一个事务，先 pg_try_advisory_xact_lock(固定键)，拿不到就结束这一轮"。
     * 怎么模拟"另一个实例"：测试自己开一个数据库连接，在事务里拿住同一把锁，就像另一个 pod 正在投递。
     */
    @Test
    void whileAnotherInstanceHoldsTheLock_nothingIsPublished_thenPublishedOnce() throws Exception {
        String orderId = newOrderId();
        try (Connection otherInstance = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            otherInstance.setAutoCommit(false);
            try (Statement st = otherInstance.createStatement()) {
                // 事务级咨询锁：这个事务结束（提交或回滚）时自动释放
                st.execute("SELECT pg_advisory_xact_lock(" + PUBLISHER_LOCK_KEY + ")");
            }

            createDelivery(orderId);
            Thread.sleep(3_000); // 投递程序跑了十几轮，每轮都拿不到锁
            // 断言 1：锁在别人手里，一条都没发
            assertThat(eventTypesOf(orderId)).as("别的实例持锁期间不应该发送").isEmpty();

            otherInstance.commit(); // "另一个实例"这一轮发完了，锁随事务释放
        }

        // 断言 2：锁放开后发出去了，只有一条
        awaitEventTypes(orderId, 1);
        Thread.sleep(1_000);
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated");
    }

    /**
     * P5：拿着锁的实例中途死了（连接断开，事务没提交），锁自动释放，这个实例接着发。
     * 设计：ADR-06"事务级锁会自动释放，不会因为连接被放回连接池而一直占着"。
     */
    @Test
    void lockHolderDies_otherPublisherTakesOver() throws Exception {
        String orderId = newOrderId();
        Connection dyingInstance = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dyingInstance.setAutoCommit(false);
        try (Statement st = dyingInstance.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(" + PUBLISHER_LOCK_KEY + ")");
        }
        createDelivery(orderId);
        Thread.sleep(1_000);
        assertThat(eventTypesOf(orderId)).isEmpty();

        // "进程崩溃"：不提交，直接断开连接。Postgres 回滚它的事务，锁跟着释放
        dyingInstance.close();

        assertThat(awaitEventTypes(orderId, 1)).containsExactly("DeliveryCreated");
    }

    /**
     * A2：worker 正在推进这一步（sleep 中），用户取消了。只能有 Cancelled，不能再出现一条 PickedUp。
     * 现有代码：worker 睡醒后重新读状态，看到 CANCELLED 就停（或者 @Version 冲突丢弃这一步）。
     * 新要求：被丢弃的那一步，它的事件也必须跟着回滚，不能发出"已揽收"而实际上是"已取消"。
     */
    @Test
    void cancelDuringWorkerStep_onlyCancelledIsPublished() throws Exception {
        String orderId = newOrderId();
        Long deliveryId = createDelivery(orderId);

        Thread step = new Thread(() -> worker.processDelivery(deliveryId));
        step.start();
        Thread.sleep(300);                         // worker 已经读到 CREATED，正在 sleep
        deliveryService.cancelByOrderId(orderId);  // 用户这时候取消
        step.join();

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        awaitEventTypes(orderId, 2);
        Thread.sleep(1_000);
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated", "Cancelled");
    }

    private static DeliveryEventPayload payloadOf(EventEnvelope envelope) {
        try {
            return EventCodec.payload(envelope, DeliveryEventPayload.class);
        } catch (Exception e) {
            throw new AssertionError("bad payload: " + envelope.payload(), e);
        }
    }
}
