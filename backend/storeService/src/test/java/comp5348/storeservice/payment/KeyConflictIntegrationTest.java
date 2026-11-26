package comp5348.storeservice.payment;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 66 号（09 待办"A#4 后续"，审查 63 🟡2）：bank 回 KEY_CONFLICT（这个键用过、请求不同）。
 *
 * 【层】集成测试（真 Postgres + 假 bank gRPC）。
 * 【为什么不是拒绝】键有 64 位随机段、bank 要服务凭据，"别人先用了你的键"基本不会发生。现在撞上冲突，几乎只会是
 *   我们自己同一个键发了不同的请求（62 号的老任务换了号、以后多副本）。那这个键第一次的请求可能已经扣了 / 退了钱：
 *   当成拒绝 → FAILED → Pay Now 换新键 → 扣两次。
 * 【关口 0（Christy 2026-11-24）】KEY_CONFLICT = 结果未知：PAY_UNKNOWN / REFUND_UNKNOWN，各自的 ALERT 只打一次，
 *   不发 PaymentFailed；这一轮不再快速重试（再发也是冲突），之后每 10 分钟同一个键再试（人工把账号改对后能自动收尾）。
 */
@ExtendWith(OutputCaptureExtension.class)
class KeyConflictIntegrationTest extends StoreIntegrationTest {

    @Autowired OrderService orderService;

    private int paymentFailedEvents(PlacedOrder order) {
        return jdbc.queryForObject("SELECT count(*) FROM event_outbox WHERE order_id = ? AND event_type = 'PaymentFailed'",
                Integer.class, order.orderId());
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }

    /**
     * K4–K7：扣款收到 KEY_CONFLICT。
     * 现在：REJECTED → FAILED、PaymentFailed、任务关掉；Pay Now 换新键。
     * 应该：PAY_UNKNOWN + 告警一次；这一轮只发 1 次；10 分钟后同一个键再试；改对以后拿到成功。
     */
    @Test
    void payKeyConflict_isUnknownNotFailed_alertsOnce_retriesSameKeyEvery10Minutes(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        bankTransferKeyConflict();

        runOutboxRounds(1);

        // K4：不是失败
        assertThat(paymentOf(order).getStatus()).as("冲突 ≠ 拒绝：第一次可能已经扣了钱").isEqualTo(PaymentStatus.PAY_UNKNOWN);
        assertThat(paymentFailedEvents(order)).as("不能告诉别的服务'付款失败'").isZero();
        assertThat(openTasksOf(order, "PAY")).as("任务留着，10 分钟后再看").hasSize(1);
        assertThat(output.getAll()).contains("ALERT payment_key_conflict orderId=" + order.orderId() + " ");
        // K5：再发也是冲突，这一轮不快速重试
        assertThat(transferRequests()).as("这一轮只发 1 次").hasSize(1);

        // Pay Now 不能换新键
        orderProductService.retryOrderPayment(order.orderId(), buyerIdOf(order));
        assertThat(tasksOf(order)).filteredOn(t -> "PAY".equals(t.getEventType())).hasSize(1);
        assertThat(paymentOf(order).getAttempt()).isEqualTo(1);

        // 不到 10 分钟不再发
        runOutboxRounds(1);
        assertThat(transferRequests()).hasSize(1);

        // K6：10 分钟后还是冲突：同一个键再发一次，告警不再打
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        assertThat(transferRequests()).hasSize(2);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
        assertThat(countOf(output.getAll(), "ALERT payment_key_conflict orderId=" + order.orderId() + " "))
                .as("同一单只告警一次").isEqualTo(1);

        // K7：人工把任务上的账号改对了，bank 按键返回第一次的成功
        bankTransferSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
    }

    /**
     * K8：退款收到 KEY_CONFLICT。
     * 现在：ALERT refund_rejected、任务关成 FAILED，这笔退款放弃。
     * 应该：REFUND_UNKNOWN + ALERT refund_key_conflict（一次）；任务留着，10 分钟后同一个键再试。
     */
    @Test
    void refundKeyConflict_isUnknownNotRejected_retriesSameKey(CapturedOutput output) {
        PlacedOrder order = placePaidOrder();
        bankRefundKeyConflict();

        orderService.cancelOrder(order.orderId());
        runOutboxRounds(1);

        assertThat(paymentOf(order).getStatus()).as("这个退款键以前可能已经退过钱").isEqualTo(PaymentStatus.REFUND_UNKNOWN);
        assertThat(tasksOf(order)).filteredOn(t -> "REFUND".equals(t.getEventType()))
                .singleElement().extracting(PaymentOutbox::getStatus).as("退款不能放弃").isEqualTo("PENDING");
        assertThat(output.getAll()).contains("ALERT refund_key_conflict orderId=" + order.orderId() + " ");
        assertThat(output.getAll()).doesNotContain("ALERT refund_rejected orderId=" + order.orderId() + " ");
        assertThat(refundRequests()).as("这一轮只发 1 次").hasSize(1);

        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        assertThat(refundRequests()).hasSize(2);
        assertThat(refundRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(refundKeyOf(order));
        assertThat(countOf(output.getAll(), "ALERT refund_key_conflict orderId=" + order.orderId() + " ")).isEqualTo(1);

        bankRefundSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
    }

    /**
     * K10：62 号留下的那条路，端到端。上线时正在路上的老任务（没记账号），买家账号这期间换了号，
     * 重试带着新号、同一个键发出去，bank 回 KEY_CONFLICT。
     * 现在：FAILED → Pay Now 换新键 → 扣两次。应该：PAY_UNKNOWN + 告警，Pay Now 什么都不做。
     */
    @Test
    void oldTaskWithChangedAccount_keyConflict_neverASecondKey(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();
        runOutboxRounds(6);
        assertThat(paymentOf(order).getStatus()).as("前提：结果未知").isEqualTo(PaymentStatus.PAY_UNKNOWN);
        jdbc.update("UPDATE payment_outbox SET from_account = NULL, to_account = NULL WHERE order_id = ?",
                order.orderId());
        jdbc.update("UPDATE account SET bank_account_number = ? WHERE email = ?",
                "ACC-CHANGED-" + order.orderId(), order.buyerEmail());
        bankTransferKeyConflict();

        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        orderProductService.retryOrderPayment(order.orderId(), buyerIdOf(order));
        bankTransferSucceeds();
        runOutboxRounds(3);

        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf)
                .as("bank 只见过一个键 = 最多扣一次").containsOnly(payKeyOf(order));
        assertThat(paymentOf(order).getStatus()).isNotEqualTo(PaymentStatus.FAILED);
        assertThat(paymentOf(order).getAttempt()).isEqualTo(1);
        assertThat(output.getAll()).contains("ALERT payment_key_conflict orderId=" + order.orderId() + " ");
    }
}
