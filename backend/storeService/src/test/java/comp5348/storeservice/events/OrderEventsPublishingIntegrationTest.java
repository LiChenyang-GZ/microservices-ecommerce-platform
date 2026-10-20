package comp5348.storeservice.events;

import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.OrderCancelledPayload;
import com.comp5348.contracts.events.payload.OrderPaidPayload;
import com.comp5348.contracts.events.payload.OrderPlacedPayload;
import com.comp5348.contracts.events.payload.OrderRefundedPayload;
import com.comp5348.contracts.events.payload.PaymentFailedPayload;
import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.scheduler.UnpaidOrderCanceller;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import comp5348.storeservice.support.KafkaProbe;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.comp5348.contracts.events.DeliveryEventType.LOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 失败场景表（17-第2波A）B1–B3、P1–P6（store 那份）：store 改了订单 / 付款状态，就一定往 order-events 写对应的事件。
 *
 * 五种事件（契约 OrderEventType）：下单 OrderPlaced、扣款成功 OrderPaid、确定失败 PaymentFailed、
 * 取消 OrderCancelled、退款成功 OrderRefunded。规则 2：每一种都和状态变化写在**同一个事务**里（event_outbox），
 * 再由投递程序发到 Kafka（和 delivery 那份一样：pg_try_advisory_xact_lock、按 id 顺序、key = orderId）。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka + 假 bank / 假 delivery（WireMock）。
 * 【测试怎么看结果】直接读 order-events 这个 topic（KafkaProbe），不经过任何消费者。
 * 【红灯为什么红】store 现在不往 Kafka 发任何事件（没有 event_outbox，也没有投递程序），
 *   所以每个测试都会在"等事件出现"那一步等满 10 秒后失败。
 */
class OrderEventsPublishingIntegrationTest extends DeliveryEventsTestBase {

    /** store 投递程序的咨询锁键。store 和 delivery 是两个库，用同一个数字也不会互相挡 */
    private static final long PUBLISHER_LOCK_KEY = 5348L;

    @Autowired PlatformTransactionManager transactionManager;
    @Autowired UnpaidOrderCanceller unpaidOrderCanceller;

    // ---------- B1：五种状态变化，各有各的事件 ----------

    /** B1 · OrderPlaced：下单就写，payload 带用户、商品、数量、总价（契约 OrderPlacedPayload） */
    @Test
    void placeOrder_writesOrderPlaced() {
        PlacedOrder order = placeOrder();

        assertThat(awaitOrderEventTypes(order, 1)).containsExactly("OrderPlaced");
        OrderPlacedPayload placed = payloadOf(order, "OrderPlaced", OrderPlacedPayload.class);
        Order saved = orderWithProduct(order);
        assertThat(placed.userId()).isEqualTo(saved.getUserId());
        assertThat(placed.productId()).isEqualTo(saved.getProduct().getId());
        assertThat(placed.quantity()).isEqualTo(1);
        assertThat(placed.totalAmount()).isEqualByComparingTo("100.00");
    }

    /**
     * B1 · OrderPaid：扣款成功就写。payload 要带 delivery 建单的全部字段（推进顺序第 5 步：delivery 不回调 store）。
     * 这里也顺带看 payload 和订单、买家对得上：delivery 会照着它把货发给谁、发什么。
     */
    @Test
    void paySucceeds_writesOrderPaid_withEverythingDeliveryNeeds() {
        PlacedOrder order = placePaidOrder();

        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderPaid");
        OrderPaidPayload paid = payloadOf(order, "OrderPaid", OrderPaidPayload.class);
        assertThat(paid.paymentId()).isEqualTo(order.paymentId());
        assertThat(paid.amount()).isEqualByComparingTo("100.00");
        assertThat(paid.bankTxnId()).isEqualTo("9001"); // 假 bank 回的交易号
        assertThat(paid.customerEmail()).isEqualTo(order.buyerEmail());
        assertThat(paid.customerName()).startsWith("buyer-");
        assertThat(paid.toAddress()).isNotBlank();
        assertThat(paid.fromAddresses()).isNotEmpty();
        assertThat(paid.productName()).isEqualTo(orderWithProduct(order).getProduct().getName());
        assertThat(paid.quantity()).isEqualTo(1);
    }

    /** B1 · PaymentFailed：bank 明确拒绝（余额不足）才写，带第几次尝试和原因 */
    @Test
    void payRejected_writesPaymentFailed() {
        PlacedOrder order = placeOrder();
        bankRejectsTransfer("Insufficient balance. Available: 10.00");
        runOutboxRounds(3);
        assertThat(paymentOf(order).getStatus()).as("前提：确定失败").isEqualTo(PaymentStatus.FAILED);

        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "PaymentFailed");
        PaymentFailedPayload failed = payloadOf(order, "PaymentFailed", PaymentFailedPayload.class);
        assertThat(failed.paymentId()).isEqualTo(order.paymentId());
        assertThat(failed.attempt()).isEqualTo(1);
        assertThat(failed.reason()).contains("Insufficient");
    }

    /** B1 · OrderCancelled：15 分钟没付款，自动取消 → reason = PAYMENT_TIMEOUT（用户自己取消的 USER_REQUEST 在 StoreCancelIntegrationTest） */
    @Test
    void autoCancel_writesOrderCancelled_paymentTimeout() {
        PlacedOrder order = placeOrder();
        clock.advance(Duration.ofMinutes(16)); // 拨表，不真等
        unpaidOrderCanceller.cancelExpiredUnpaidOrders();

        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderCancelled");
        assertThat(payloadOf(order, "OrderCancelled", OrderCancelledPayload.class).reason()).isEqualTo("PAYMENT_TIMEOUT");
    }

    /** B1 · OrderRefunded：取消已付款的订单，退款成功后写，reason = ORDER_CANCELLED */
    @Test
    void refundAfterCancel_writesOrderRefunded() {
        PlacedOrder order = placePaidOrder();
        orderService.cancelOrder(order.orderId()); // 假 delivery 默认回答 CANCELLED
        bankRefundSucceeds();
        runOutboxRounds(3);
        assertThat(paymentOf(order).getStatus()).as("前提：退款成功").isEqualTo(PaymentStatus.REFUNDED);

        assertThat(awaitOrderEventTypes(order, 4))
                .containsExactly("OrderPlaced", "OrderPaid", "OrderCancelled", "OrderRefunded");
        OrderRefundedPayload refunded = payloadOf(order, "OrderRefunded", OrderRefundedPayload.class);
        assertThat(refunded.paymentId()).isEqualTo(order.paymentId());
        assertThat(refunded.amount()).isEqualByComparingTo("100.00");
        assertThat(refunded.refundTxnId()).isEqualTo("9101"); // 假 bank 回的退款交易号
        assertThat(refunded.reason()).isEqualTo("ORDER_CANCELLED");
    }

    /** B1 · OrderRefunded：丢件退款成功后写，reason = DELIVERY_LOST（丢件不是 store 的状态变化来源，所以没有 OrderCancelled） */
    @Test
    void refundAfterLost_writesOrderRefunded_deliveryLost() {
        PlacedOrder order = placePaidOrder();
        publish(order, LOST);
        awaitOrderStatus(order, "LOST");
        bankRefundSucceeds();
        runOutboxRounds(3);
        awaitOrderStatus(order, "REFUNDED");

        assertThat(awaitOrderEventTypes(order, 3)).containsExactly("OrderPlaced", "OrderPaid", "OrderRefunded");
        assertThat(payloadOf(order, "OrderRefunded", OrderRefundedPayload.class).reason()).isEqualTo("DELIVERY_LOST");
    }

    // ---------- B2、B3：不该写的事件 ----------

    /**
     * B2：扣款成功时订单已经被取消 → 写 REFUND 任务，**不写 OrderPaid**（否则 delivery 会给已取消的订单发货）。
     * 退款成功后的 OrderRefunded reason = PAID_AFTER_CANCEL。
     * 设计：规则 1 / 时序图 A 的 alt 分支。时间窗的做法和 PaymentResultIntegrationTest #7 一样：bank 慢 2 秒才回答。
     */
    @Test
    void paidAfterCancel_writesRefund_butNoOrderPaid() throws Exception {
        PlacedOrder order = placeOrder();
        bankTransferSucceedsSlowly(2_000);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> round = pool.submit(() -> outboxProcessor.processOutbox());
        await().atMost(Duration.ofSeconds(10)).until(() -> !transferRequests().isEmpty()); // bank 正在处理
        orderService.cancelOrder(order.orderId());                                           // 用户这时取消
        round.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        assertThat(refundTasksOf(order)).as("前提：钱扣了就要退").isNotEmpty();

        bankRefundSucceeds();
        runOutboxRounds(3);

        assertThat(awaitOrderEventTypes(order, 3)).containsExactly("OrderPlaced", "OrderCancelled", "OrderRefunded");
        assertThat(payloadOf(order, "OrderRefunded", OrderRefundedPayload.class).reason()).isEqualTo("PAID_AFTER_CANCEL");
    }

    /**
     * B3：扣款超时，结果未知（PAY_UNKNOWN）→ **不写 PaymentFailed**。超时不是失败，钱可能已经扣了（规则 7）。
     * 契约 PaymentFailedPayload 的注释："A timeout is never a PaymentFailed"。
     */
    @Test
    void payUnknown_writesNoPaymentFailed() throws Exception {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();
        runOutboxRounds(6);
        assertThat(paymentOf(order).getStatus()).as("前提：结果未知").isEqualTo(PaymentStatus.PAY_UNKNOWN);

        awaitOrderEventTypes(order, 1); // 先确认投递程序在工作（OrderPlaced 已经发出来了）
        Thread.sleep(1_000);            // 如果有 PaymentFailed，这时也该发出来了
        assertThat(orderEventTypesOf(order)).containsExactly("OrderPlaced");
    }

    // ---------- P 组：投递程序（和 delivery 那份同一套要求） ----------

    /**
     * P1：改状态和写事件同生共死。取消在事务里做完再回滚 → 不能有 OrderCancelled；再取消一次、正常提交 → 正好一条。
     * 反例：实现成"改完状态直接 kafkaTemplate.send"，回滚的那次也会发出去，这里就会看到两条。
     */
    @Test
    void stateChange_andEvent_commitTogetherOrNotAtAll() throws Exception {
        PlacedOrder order = placeOrder();

        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            orderService.cancelOrder(order.orderId());
            tx.setRollbackOnly(); // 模拟"改完状态，事务后面某一步出错了"
        });
        assertThat(orderOf(order).getStatus().name()).as("前提：回滚生效").isEqualTo("PENDING_PAYMENT");

        orderService.cancelOrder(order.orderId());

        awaitOrderEventTypes(order, 2);
        Thread.sleep(1_000);
        assertThat(orderEventTypesOf(order)).containsExactly("OrderPlaced", "OrderCancelled");
    }

    /**
     * P2：Kafka 停了。下单、取消照样能提交（不依赖 Kafka），事件留在 event_outbox；Kafka 回来后按顺序补发。
     * docker pause 冻结 Kafka 容器，和 delivery 那份一样。
     */
    @Test
    void kafkaDown_publisherStopsAtFailedRow_thenResumesInOrder() throws Exception {
        var docker = KAFKA.getDockerClient();
        PlacedOrder order;
        docker.pauseContainerCmd(KAFKA.getContainerId()).exec();
        try {
            order = placeOrder();
            orderService.cancelOrder(order.orderId());
            assertThat(orderOf(order).getStatus().name()).isEqualTo("CANCELLED");
            Thread.sleep(3_000); // 让投递程序在 Kafka 不可用时失败几轮
        } finally {
            docker.unpauseContainerCmd(KAFKA.getContainerId()).exec();
        }

        PlacedOrder placed = order;
        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(500))
                .until(() -> orderEventTypesOf(placed).size() >= 2);
        assertThat(orderEventTypesOf(placed)).containsExactly("OrderPlaced", "OrderCancelled");
    }

    /**
     * P3：发出去了，但"标记已发送"没提交成功（用 SQL 把 sent_at 清掉来模拟）→ 下一轮再发一次，eventId 相同。
     * 证明"会重复"是真的，所以 delivery 的消费者必须去重（B5 证明它去重）。
     * 表名、列名和 delivery 那份一样：event_outbox.order_id、event_outbox.sent_at（null = 还没发）。
     */
    @Test
    void sentButNotMarked_isSentAgain() {
        PlacedOrder order = placeOrder();
        awaitOrderEventTypes(order, 1);

        int reset = jdbc.update("UPDATE event_outbox SET sent_at = NULL WHERE order_id = ?", order.orderId());
        assertThat(reset).as("event_outbox 里应该正好有这一条").isEqualTo(1);

        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderPlaced");
        List<EventEnvelope> events = orderEventsOf(order);
        assertThat(events.get(0).eventId()).isEqualTo(events.get(1).eventId());
    }

    /**
     * P4：另一个实例拿着锁的时候，这个实例一条都不发；锁放开后才发，而且只发一次。
     * 测试自己开一个连接、在事务里拿住同一把咨询锁，扮演"另一个 pod 正在投递"。
     */
    @Test
    void whileAnotherInstanceHoldsTheLock_nothingIsPublished_thenPublishedOnce() throws Exception {
        PlacedOrder order;
        try (Connection otherInstance = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            otherInstance.setAutoCommit(false);
            try (Statement st = otherInstance.createStatement()) {
                st.execute("SELECT pg_advisory_xact_lock(" + PUBLISHER_LOCK_KEY + ")");
            }

            order = placeOrder();
            Thread.sleep(3_000); // 投递程序跑了十几轮，每轮都拿不到锁
            assertThat(orderEventTypesOf(order)).as("别的实例持锁期间不应该发送").isEmpty();

            otherInstance.commit(); // 锁随事务释放
        }

        awaitOrderEventTypes(order, 1);
        Thread.sleep(1_000);
        assertThat(orderEventTypesOf(order)).containsExactly("OrderPlaced");
    }

    /** P5：拿着锁的实例死了（连接断开、事务没提交），锁自动释放，这个实例接着发 */
    @Test
    void lockHolderDies_otherPublisherTakesOver() throws Exception {
        Connection dyingInstance = DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        dyingInstance.setAutoCommit(false);
        try (Statement st = dyingInstance.createStatement()) {
            st.execute("SELECT pg_advisory_xact_lock(" + PUBLISHER_LOCK_KEY + ")");
        }
        PlacedOrder order = placeOrder();
        Thread.sleep(1_000);
        assertThat(orderEventTypesOf(order)).isEmpty();

        dyingInstance.close(); // "进程崩溃"：不提交，直接断开。Postgres 回滚它的事务，锁跟着释放

        assertThat(awaitOrderEventTypes(order, 1)).containsExactly("OrderPlaced");
    }

    /** P6：同一订单的事件落在同一个分区，key = orderId，顺序 = 发生的顺序 */
    @Test
    void sameOrderEvents_samePartition_inIdOrder() {
        PlacedOrder order = placePaidOrder();
        orderService.cancelOrder(order.orderId());

        awaitOrderEventTypes(order, 3);
        String key = order.orderId().toString();
        List<ConsumerRecord<String, String>> records = kafka.recordsOfOrder(Topics.ORDER_EVENTS, key);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(key);
        assertThat(orderEventsOf(order)).extracting(EventEnvelope::orderId).containsOnly(key);
        assertThat(records).extracting(ConsumerRecord::partition).containsOnly(KafkaProbe.partitionFor(key));
        assertThat(orderEventTypesOf(order)).containsExactly("OrderPlaced", "OrderPaid", "OrderCancelled");
    }

    /** 订单连同商品一起读出来（商品是懒加载，事务外直接 getProduct() 会报错） */
    private Order orderWithProduct(PlacedOrder order) {
        return orderRepository.findByIdWithProduct(order.orderId()).orElseThrow();
    }
}
