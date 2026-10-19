package comp5348.storeservice.payment;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.scheduler.UnpaidOrderCanceller;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 失败场景表 #6（集成版）、#7、#14：bank 给出了确定的回答之后，store 怎么处理。
 *
 * 【层】集成测试（真 Postgres + 假 bank）。
 * 【为什么要真数据库】#7、#14 是"两件事同时发生"（扣款结果回来的同时，订单被取消了），
 *   要靠数据库事务和行锁来保证谁先谁后，假的 repository 测不出来。
 */
class PaymentResultIntegrationTest extends StoreIntegrationTest {

    @Autowired OrderService orderService;
    @Autowired UnpaidOrderCanceller unpaidOrderCanceller;

    /**
     * #6（集成版，表里只写了单元层；这一版额外检查 HTTP 那一段）：余额不足 → FAILED，订单还是待支付。
     * 真 bank 拒绝时回的是 HTTP 400 + success=false。这一版检查 store 能不能把 400 认成"明确拒绝"，
     * 而不是当成"bank 挂了"去重试。
     */
    @Test
    void insufficientFunds_marksFailed_orderStaysPending() {
        PlacedOrder order = placeOrder();
        bankRejectsTransfer("Insufficient balance. Available: 10.00");

        runOutboxRounds(3);

        // 断言 1：确定失败
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.FAILED);
        // 断言 2：bank 明确拒绝就不用再问了 —— 只问了一次
        assertThat(transferRequests()).as("拒绝是确定结果，不重试").hasSize(1);
        // 断言 3：订单还是待支付，库存还锁着（等用户 Pay Now）
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /**
     * #7 扣款成功时订单已被取消 → 写 REFUND 任务，订单不改回 PAID。
     * 设计（规则 1）：扣款结果的事务里锁订单行再判断；订单已取消就写 REFUND 任务、不建配送单。
     */
    @Test
    void paySucceeds_afterOrderCancelled_writesRefund_orderStaysCancelled() throws Exception {
        PlacedOrder order = placeOrder();
        // 剧本：bank 会成功，但要 2 秒才回答 —— 制造"银行处理中"的时间窗
        bankTransferSucceedsSlowly(2_000);

        // 在另一个线程里跑一轮 OutboxProcessor（它会卡在等 bank 回答）
        ExecutorService pool = Executors.newSingleThreadExecutor();
        Future<?> round = pool.submit(() -> outboxProcessor.processOutbox());
        // Awaitility：反复检查，直到条件成立（最多等 10 秒）。这里等"bank 已经收到扣款请求"
        await().atMost(Duration.ofSeconds(10)).until(() -> !transferRequests().isEmpty());

        // 就在这个时候，用户点了取消
        orderService.cancelOrder(order.orderId());

        // 等那一轮跑完（bank 回答"成功"），再跑几轮处理后续任务
        round.get(30, TimeUnit.SECONDS);
        pool.shutdown();
        runOutboxRounds(3);

        // 断言 1：订单还是已取消，没有被改回 PAID
        assertThat(orderOf(order).getStatus()).as("取消在先，扣款成功不能把订单改回 PAID")
                .isEqualTo(OrderStatus.CANCELLED);
        // 断言 2：钱扣了就要退：写了一条 REFUND 任务
        assertThat(tasksOf(order)).as("扣款成功 + 订单已取消 → 写 REFUND 任务")
                .anyMatch(t -> "REFUND".equals(t.getEventType()));
        // 断言 3：没有给已取消的订单建配送单
        assertThat(deliveryCreateRequests()).isEmpty();
    }

    /**
     * #14 超时自动取消遇到 PAY_UNKNOWN 的订单 → 照样取消、释放库存；之后扣款成功 → 退款。
     * 设计（规则 7 的"超时自动取消"）：和 #7 一样，扣款结果的事务看到订单已取消，就写 REFUND 任务。
     */
    @Test
    void autoCancel_payUnknown_thenLateSuccess_refunds() {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();
        runOutboxRounds(6);
        // 前提：扣款结果未知
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.PAY_UNKNOWN);

        // 下单已经超过 15 分钟（拨表，不真等），自动取消任务跑一次
        clock.advance(Duration.ofMinutes(16));
        unpaidOrderCanceller.cancelExpiredUnpaidOrders();

        // 断言 1：照样取消，库存放回去（100 件里锁的那 1 件回来了）
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity())
                .as("取消后库存释放").isEqualTo(100);

        // 之后 bank 恢复，低频重试拿到"扣款成功"；退款也会成功
        bankTransferSucceeds();
        bankRefundSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(4);

        // 断言 2：订单还是已取消，没建配送单
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(deliveryCreateRequests()).isEmpty();
        // 断言 3：钱退了：bank 收到了退款请求，键是 REFUND-{paymentId}-{随机段}
        assertThat(refundRequests()).isNotEmpty();
        assertThat(refundRequests()).extracting(StoreIntegrationTest::keyOf)
                .containsOnly(refundKeyOf(order));
        assertThat(refundKeyOf(order)).startsWith("REFUND-" + order.paymentId() + "-");
    }
}
