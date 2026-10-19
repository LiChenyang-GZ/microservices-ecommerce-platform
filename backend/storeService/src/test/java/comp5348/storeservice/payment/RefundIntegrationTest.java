package comp5348.storeservice.payment;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #8、#9：退款。
 *
 * 【层】集成测试（真 Postgres + 假 bank）。
 * 【准备】先走一遍真实的下单 + 扣款成功（订单 PAID），再让用户取消订单，触发退款。
 */
@ExtendWith(OutputCaptureExtension.class) // 把控制台输出（日志）抓下来，测试里可以检查有没有打出告警
class RefundIntegrationTest extends StoreIntegrationTest {

    @Autowired OrderService orderService;

    /**
     * #8 退款超时 → 走 outbox，用 REFUND-{paymentId}-{随机段} 重试；5 次后 REFUND_UNKNOWN。
     * 设计（规则 2、规则 7）：退款也是任务，不在用户的取消请求里直接调 bank。
     */
    @Test
    void refundTimeout_retriesViaOutbox_sameKey() {
        PlacedOrder order = placePaidOrder();
        // 剧本：bank 的退款接口一直没有回答
        bankRefundResultUnknown();

        // 动作：用户取消已支付的订单，然后 OutboxProcessor 跑 6 轮（3 分钟）
        orderService.cancelOrder(order.orderId());
        runOutboxRounds(6);

        // 前提：订单已取消
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        // 断言 1：退款结果未知 → REFUND_UNKNOWN（不是还写着 SUCCESS，好像钱从来没要退过）
        assertThat(paymentOf(order).getStatus()).as("退款 5 次没有回答 → 结果未知")
                .isEqualTo(PaymentStatus.REFUND_UNKNOWN);
        // 断言 2：确实重试了，而且每次都是同一个退款键
        assertThat(refundRequests()).hasSizeGreaterThan(1);
        assertThat(refundRequests()).extracting(StoreIntegrationTest::keyOf)
                .containsOnly(refundKeyOf(order));
        assertThat(refundKeyOf(order)).startsWith("REFUND-" + order.paymentId() + "-");

        // bank 恢复，过了 10 分钟，低频重试拿到成功
        bankRefundSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(2);
        // 断言 3：钱退回去了
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    /**
     * #9 bank 明确拒绝退款 → 任务停下 + 告警，等人工。
     * 设计（支付状态机）：拒绝退款是异常情况，不自动重试。
     */
    @Test
    void refundRejected_stopsTask_raisesAlert(CapturedOutput output) {
        PlacedOrder order = placePaidOrder();
        // 剧本：bank 明确拒绝退款（HTTP 400 + success=false）
        bankRejectsRefund("Insufficient balance for refund");

        orderService.cancelOrder(order.orderId());
        runOutboxRounds(6);

        // 断言 1：拒绝是确定的回答，问一次就够了，不重试
        assertThat(refundRequests()).as("bank 明确拒绝，不该重试").hasSize(1);
        // 断言 2：有且只有一条 REFUND 任务，并且已经停下（FAILED = 放弃自动处理，等人工；沿用 outbox 现有的状态词）
        assertThat(tasksOf(order)).filteredOn(t -> "REFUND".equals(t.getEventType()))
                .singleElement()
                .extracting(PaymentOutbox::getStatus)
                .isEqualTo("FAILED");
        // 断言 3：钱没退成，Payment 不能写成 REFUNDED
        assertThat(paymentOf(order).getStatus()).isNotEqualTo(PaymentStatus.REFUNDED);
        // 断言 4：发出了告警，等人工处理（告警 = 带固定标记的日志）
        assertThat(output.getAll())
                .contains("ALERT refund_rejected orderId=" + order.orderId() + " paymentId=" + order.paymentId());
    }
}
