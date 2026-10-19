package comp5348.storeservice.payment;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #1、#4、#5：调 bank 超时（结果未知）。
 *
 * 【层】集成测试（真 Postgres + 假 bank 的 HTTP）。
 * 【为什么要假 bank】真 bank 很难让它"恰好超时"；WireMock 可以按剧本：慢 6 秒回答、直接断开连接、先超时再成功。
 * 【怎么数"扣了几次钱"】真 bank 按幂等键去重：同一个键只扣一次，不同的键各扣一次。
 *   所以"bank 收到了几个不同的键"就等于"最坏情况下扣了几次钱"。
 */
@ExtendWith(OutputCaptureExtension.class) // 把控制台输出（日志）抓下来，测试里可以检查有没有打出告警
class PaymentTimeoutIntegrationTest extends StoreIntegrationTest {

    /**
     * #1 调 bank 超时，重试时扣了两次。
     * 设计（ADR-04、ADR-05、规则 7）：重试必须用同一个幂等键 PAY-{paymentId}-{attempt}。
     */
    @Test
    void payTimeout_retriesWithSameKey_neverChargesTwice() {
        // 准备：用户下单（store 写 PAY 任务）
        PlacedOrder order = placeOrder();
        // 剧本：前 3 次 bank 都比 store 的 5 秒超时慢（bank 其实已经扣款了，只是回答丢了），之后正常回答
        bankTransferTimesOutThenSucceeds(3);

        // 动作：OutboxProcessor 跑 3 轮（每轮 store 都会尝试扣款）
        runOutboxRounds(3);

        // 取出 bank 收到的每个扣款请求里的幂等键，去重
        List<String> distinctKeys = transferRequests().stream().map(StoreIntegrationTest::keyOf).distinct().toList();

        // 断言 1（核心）：只出现过一个键 —— 不管重试几次，bank 最多扣一次钱
        assertThat(distinctKeys)
                .as("bank 收到的不同幂等键个数 = 最坏情况下扣了几次钱")
                .hasSize(1);
        // 断言 2：键的格式是规格定的 PAY-{paymentId}-1（第一次支付，attempt = 1）
        assertThat(distinctKeys.get(0)).isEqualTo("PAY-" + order.paymentId() + "-1");
        // 断言 3：最后拿到了 bank 的成功回答，Payment 是 SUCCESS
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    /**
     * #4 快速重试 5 次都超时 → PAY_UNKNOWN + 告警，不标 FAILED，attempt 不变。
     * 设计（规则 7、ADR-05）：结果未知 ≠ 失败。钱可能已经扣了，换新键再扣就会扣两次。
     */
    @Test
    void payStillUnknownAfter5Retries_marksPayUnknown_keepsAttempt(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        // 剧本：bank 一直没有回答（连接被重置，相当于超时）
        bankTransferResultUnknown();

        // 跑 6 轮，每轮拨 30 秒，一共 3 分钟：足够把"快速重试 5 次"用完，又不到"10 分钟后再试"
        runOutboxRounds(6);

        Payment payment = paymentOf(order);
        // 断言 1：状态是 PAY_UNKNOWN（不是 PENDING 假装还在处理，也不是 FAILED）
        assertThat(payment.getStatus()).as("5 次都没有回答 → 结果未知").isEqualTo(PaymentStatus.PAY_UNKNOWN);
        // 断言 2：attempt 还是 1 —— 结果未知时不能换新键
        assertThat(payment.getAttempt()).isEqualTo(1);
        // 断言 3：确实重试过，而且每一次都是同一个键
        assertThat(transferRequests()).hasSizeGreaterThan(1);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf)
                .containsOnly("PAY-" + order.paymentId() + "-1");
        // 断言 4：PAY 任务没有结束（之后还要每 10 分钟再试），订单还是待支付
        assertThat(openTasksOf(order, "PAY")).as("任务不结束，等低频重试").isNotEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        // 断言 5：发出了告警。告警 = 带固定标记的日志（Christy 2026-10-19 定的），运维按 "ALERT payment_unknown" 搜
        assertThat(output.getAll())
                .contains("ALERT payment_unknown orderId=" + order.orderId() + " paymentId=" + order.paymentId());
    }

    /**
     * #5 PAY_UNKNOWN 之后每 10 分钟用同一个键重试，拿到成功 → 正常走成功流程。
     * 设计（规则 7）：低频重试直到有确定结果。
     */
    @Test
    void payUnknown_laterSucceeds_completesNormally() {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();
        runOutboxRounds(6);
        // 前提：已经进入 PAY_UNKNOWN
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.PAY_UNKNOWN);

        // 还没到 10 分钟：再跑一轮，bank 不应该收到新请求（低频，不是一直狂打）
        int callsBefore = transferRequests().size();
        runOutboxRounds(1);
        assertThat(transferRequests()).as("不到 10 分钟不重试").hasSize(callsBefore);

        // bank 恢复了；把表拨过 10 分钟（注入的 Clock，不真等）
        bankTransferSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(3);

        // 断言：用的还是最初那个键，支付成功，订单 PAID（走了正常成功流程）
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf)
                .containsOnly("PAY-" + order.paymentId() + "-1");
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
    }
}
