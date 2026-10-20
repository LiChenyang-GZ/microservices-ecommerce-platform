package comp5348.storeservice.adapter;

import comp5348.storeservice.dto.BankRefundRequest;
import comp5348.storeservice.dto.BankTransferRequest;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 第 2 波 B（store 调 bank 从 HTTP 换成 gRPC）的安全网。失败场景表：Obsidian 18 号文件，第 9、12、13、15、16、20 行。
 *
 * 【为什么先写这些】换协议属于"换实现，行为不变"：没有红灯，测试是安全网。
 *   这几条是第 1 波没测到、但换成 gRPC 时很容易被改坏的行为。先在**现在的 HTTP 实现**上跑绿，
 *   换成 gRPC 之后测试体一个字不改，还得是绿的。
 * 【层】集成测试：真 Spring + 真 Postgres + 假 bank。假 bank 怎么冒充（HTTP 还是 gRPC）藏在
 *   StoreIntegrationTest 的剧本方法里（bankTransferSucceeds() 这类），所以测试体和协议无关。
 */
@ExtendWith(OutputCaptureExtension.class) // 抓下日志：第 16 行要数 store 白费了几次调用
class BankCallIntegrationTest extends StoreIntegrationTest {

    // 直接拿到 store 里真的 BankAdapter，用来测"一次调用"本身（第 20 行）
    @Autowired BankAdapter bankAdapter;

    /**
     * 第 9 行：bank 自己出错（数据库挂了、同键并发撞唯一约束）。
     * 要的行为：当成"不知道"，绝不当成"bank 拒绝"。否则 Payment 变 FAILED → 用户 Pay Now → 换新键再扣 → 可能扣两次。
     */
    @Test
    void bankInternalError_isUnknown_neverRejected() {
        PlacedOrder order = placeOrder();
        // 剧本：bank 每次都内部出错
        bankTransferFailsInternally();

        // 跑一轮：OutboxProcessor 用同一个键快速试 5 次，都没有确定的回答
        runOutboxRounds(1);

        Payment payment = paymentOf(order);
        // 断言 1：结果未知（PAY_UNKNOWN），不是 FAILED
        assertThat(payment.getStatus()).as("bank 出错 ≠ bank 拒绝").isEqualTo(PaymentStatus.PAY_UNKNOWN);
        // 断言 2：attempt 没加 1 —— 没有换新键的机会
        assertThat(payment.getAttempt()).isEqualTo(1);
        // 断言 3：每一次都是同一个键
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
    }

    /**
     * 第 12 行：bank 说成功，但没给流水号。
     * 要的行为（Christy 2026-10-20 定：保持旧行为）：照样算成功，流水号为空。以后退款时 bank 会拒绝并告警。
     */
    @Test
    void succeededWithoutTransactionId_keepsOldBehaviour() {
        PlacedOrder order = placeOrder();
        bankTransferSucceedsWithoutTransactionId();

        runOutboxRounds(1);

        // 断言：支付成功、订单已付款，流水号是 null（gRPC 版收到的是空串 ""，要转成 null，和 HTTP 版一样）
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentOf(order).getBankTxnId()).as("没有流水号就存 null，不存空串").isNull();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    /**
     * 第 13 行：超时是"每一次调用 5 秒"，不是"程序启动后 5 秒"。
     * 会出什么错：gRPC 的 withDeadlineAfter(5 秒) 如果在启动时设在 stub 上，算的是"从那一刻起 5 秒"，
     *   5 秒以后的**每一次**调用都会立刻超时 → 全站付款都变 PAY_UNKNOWN。
     * 怎么测：第一单成功后，真的等 6 秒（比超时长），第二单还得成功。这里不能拨假时钟，deadline 用的是真时间。
     */
    @Test
    void deadlineIsPerCall_callAfterIdleStillSucceeds() throws InterruptedException {
        bankTransferSucceeds();
        PlacedOrder first = placeOrder();
        runOutboxRounds(1);
        assertThat(paymentOf(first).getStatus()).as("前提：第一单成功").isEqualTo(PaymentStatus.SUCCESS);

        // 真的等过一个超时的长度
        Thread.sleep(SLOWER_THAN_STORE_TIMEOUT_MS);

        PlacedOrder second = placeOrder();
        runOutboxRounds(1);
        // 断言：第二单照样成功（如果 deadline 是"启动后 5 秒"，这里会是 PAY_UNKNOWN）
        assertThat(paymentOf(second).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    /**
     * 第 15 行：没有回答时，一轮里 bank 正好收到 5 次请求。
     * 会出什么错：gRPC 自己也能配重试。如果它在 OutboxProcessor 的 5 次外面再重试几次，bank 会收到 10 次、15 次，
     *   每一轮也会慢好几倍。第 1 波的测试只断言了"多于 1 次"，拦不住这个。
     */
    @Test
    void noAnswer_bankSeesExactly5CallsPerRound() {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();

        runOutboxRounds(1);

        // 断言：正好 5 次（payment.bank.fast-retries 的默认值），都是同一个键
        assertThat(transferRequests()).as("重试只由 OutboxProcessor 做，一轮 5 次").hasSize(5);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
    }

    /**
     * 第 16 行：bank 重启了一下（停 1.5 秒），这一轮的付款还能完成。
     * 会出什么错：gRPC 用一条长连接。断了以后它按"等 1 秒、1.6 秒、2.6 秒……"慢慢重连，
     *   等待期间新的调用**立刻**失败。5 次快速重试（间隔 0.5 秒）可能在 bank 回来之前全部用完 → PAY_UNKNOWN，要等 10 分钟。
     *   HTTP 每次都新建连接，没有这个问题。（Christy 2026-10-20 定：gRPC 版加 waitForReady，连不上时等到超时为止）
     */
    @Test
    void bankBriefRestart_paymentStillCompletes(CapturedOutput output) {
        bankTransferSucceeds();
        // 先成功付一单：让 store 和 bank 之间已经有连接（gRPC 的长连接要先建起来，才测得到"断了以后"）
        PlacedOrder warmUp = placeOrder();
        runOutboxRounds(1);
        assertThat(paymentOf(warmUp).getStatus()).as("前提：连接已经建好").isEqualTo(PaymentStatus.SUCCESS);

        // 下第二单，然后 bank 停 1.5 秒（剧本不变：回来以后照样成功）
        PlacedOrder order = placeOrder();
        bankUnreachableFor(Duration.ofMillis(1_500));

        runOutboxRounds(1);

        // 断言 1：这一轮之内就付成功了，没有掉进 PAY_UNKNOWN
        assertThat(paymentOf(order).getStatus()).as("bank 只停了 1.5 秒").isEqualTo(PaymentStatus.SUCCESS);
        // 断言 2（审查 #5）：第一次调用就成功，一次都没白费。
        //   只数"假 bank 收到几次"不够：不加 waitForReady 时，失败的调用在 store 这边立刻失败，根本到不了 bank，
        //   假 bank 照样只收到 1 次。所以数 store 自己的日志：这个键有没有出现过"结果未知"。
        String key = payKeyOf(order);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnlyOnce(key);
        assertThat(output.getAll())
                .as("waitForReady：等连接恢复再发，不会先白费几次 UNKNOWN")
                .doesNotContain("Bank transfer result: key=" + key + ", outcome=UNKNOWN");
    }

    /**
     * 第 20 行：请求里某个字段是 null，适配器也不能抛异常，要让 bank 去判断。
     * 会出什么错：protobuf 生成的 setter 遇到 null 直接抛 NullPointerException。异常飞出适配器后，
     *   OutboxProcessor 只记一条日志，任务每 5 秒重试一次，**永远不进 PAY_UNKNOWN、也不告警**。
     *   HTTP 版会把 null 发过去，bank 回"拒绝"。
     */
    @Test
    void nullField_doesNotThrow_bankDecides() {
        bankRejectsRefund("Invalid originalTransactionId: null");
        bankRejectsTransfer("From account not found: null");
        String refundKey = "REFUND-test-" + UUID.randomUUID();
        String payKey = "PAY-test-1-" + UUID.randomUUID();

        // 动作：直接调适配器，退款的原流水号和原因都是 null；扣款的付款账户是 null
        BankCallResult refund = bankAdapter.refund(new BankRefundRequest(refundKey, null, null));
        BankCallResult transfer = bankAdapter.transfer(
                new BankTransferRequest(payKey, null, "STORE_ACCOUNT_001", new BigDecimal("100.00")));

        // 断言 1：没抛异常（走到这里就说明没抛），拿到的是 bank 的拒绝
        assertThat(refund.outcome()).isEqualTo(BankCallResult.Outcome.REJECTED);
        assertThat(transfer.outcome()).isEqualTo(BankCallResult.Outcome.REJECTED);
        // 断言 2：请求真的发到了 bank，键原样带过去
        assertThat(refundRequests()).extracting(StoreIntegrationTest::keyOf).containsExactly(refundKey);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsExactly(payKey);
    }
}
