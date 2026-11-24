package comp5348.storeservice.payment;

import com.comp5348.contracts.bank.v1.TransferRequest;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.service.PaymentService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 62 号（09 待办 A#4）：扣款已经发出去过一次，重试时读不到 / 读到别的买家银行账号。
 *
 * 【层】集成测试（真 Postgres + 假 bank gRPC）。
 * 【为什么危险】发过一次的扣款，bank 可能已经扣了钱（只是回答丢了）。这时标 FAILED，用户就能点 Pay Now
 *   换新键再扣一次。真 bank 遇到"同一个键、不同的付款账号"会回 KEY_CONFLICT（success = false），
 *   store 当成拒绝 → 同样标 FAILED。
 * 【关口 0（Christy 2026-11-24）方案 C】第一次发送时把付款账号、收款账号记在 PAY 任务上，之后的重试只用记下的：
 *   同一个键永远发一模一样的请求（规则 7 的前提）。升级前写的老任务没有记录，读不到账号时不发、不标 FAILED，
 *   改成 PAY_UNKNOWN + 告警，10 分钟后再看。从没发过（retryCount = 0）读不到账号，照旧 FAILED（钱不可能扣过）。
 * 【怎么数"扣了几次钱"】和 PaymentTimeoutIntegrationTest 一样：bank 收到几个不同的键 = 最坏情况下扣了几次。
 */
@ExtendWith(OutputCaptureExtension.class)
class PayRetryAccountIntegrationTest extends StoreIntegrationTest {

    @Autowired
    private PaymentService paymentService;

    /** 买家的银行账号被清空（以后的"解绑银行卡"，或者数据被改了） */
    private void clearBankAccount(PlacedOrder order) {
        jdbc.update("UPDATE account SET bank_account_number = NULL WHERE email = ?", order.buyerEmail());
    }

    private void setBankAccount(PlacedOrder order, String bankAccount) {
        jdbc.update("UPDATE account SET bank_account_number = ? WHERE email = ?", bankAccount, order.buyerEmail());
    }

    /** 第一次发送超时，5 次快速重试都没有回答 → PAY_UNKNOWN，任务等 10 分钟后的低频重试 */
    private void givenPayUnknown(PlacedOrder order) {
        bankTransferResultUnknown();
        runOutboxRounds(6);
        assertThat(paymentOf(order).getStatus()).as("前提：结果未知").isEqualTo(PaymentStatus.PAY_UNKNOWN);
    }

    private Long payTaskId(PlacedOrder order) {
        List<PaymentOutbox> open = openTasksOf(order, "PAY");
        assertThat(open).as("前提：这一单有一条没做完的 PAY 任务").hasSize(1);
        return open.get(0).getId();
    }

    private List<String> distinctKeys() {
        return transferRequests().stream().map(StoreIntegrationTest::keyOf).distinct().toList();
    }

    /**
     * F1 + F8：PAY_UNKNOWN 之后买家账号被清空，10 分钟后低频重试；之后买家重新绑好账号、点了 Pay Now。
     * 现在：低频重试读不到账号 → FAILED → Pay Now 换新键 → bank 收到两个键（扣两次）。
     * 应该：低频重试用记下的账号、同一个键重发，拿到成功；Pay Now 看到已付款，什么都不做。
     */
    @Test
    void payUnknown_accountCleared_slowRetryUsesRecordedAccount_neverASecondKey() {
        PlacedOrder order = placeOrder();
        givenPayUnknown(order);
        clearBankAccount(order);

        bankTransferSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);

        // 买家重新绑好银行账号，看到页面上的 Pay Now（如果有）就点
        setBankAccount(order, order.buyerBankAccount());
        orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());
        runOutboxRounds(3);

        // 断言 1（核心）：bank 只见过一个键 —— 最多扣一次钱
        assertThat(distinctKeys()).as("bank 收到的不同幂等键个数 = 最坏情况下扣了几次钱")
                .containsExactly(payKeyOf(order));
        // 断言 2：每一次请求都是同一个付款账号（第一次发送时的那个）
        assertThat(transferRequests()).extracting(TransferRequest::getFromAccount)
                .containsOnly(order.buyerBankAccount());
        // 断言 3：低频重试拿到了成功，正常走完
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(paymentOf(order).getAttempt()).as("没有换过新的一次扣款").isEqualTo(1);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    /** F2：同 F1，但买家的账号行整行被删了（以后的"注销账号"） */
    @Test
    void payUnknown_accountDeleted_slowRetryUsesRecordedAccount() {
        PlacedOrder order = placeOrder();
        givenPayUnknown(order);
        jdbc.update("DELETE FROM account WHERE email = ?", order.buyerEmail());
        forgetBankRequests();

        bankTransferSucceeds();
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);

        assertThat(paymentOf(order).getStatus()).as("不能因为账号没了就说没扣钱")
                .isNotEqualTo(PaymentStatus.FAILED);
        assertThat(transferRequests()).as("低频重试照常发出").isNotEmpty();
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
        assertThat(transferRequests()).extracting(TransferRequest::getFromAccount)
                .containsOnly(order.buyerBankAccount());
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    /**
     * F3：快速重试的中途（第 1 次已经发出、状态还是 PENDING）账号被清空。
     * 这一段 payment 还没变成 PAY_UNKNOWN，所以只看 PAY_UNKNOWN 不够，要看"这个键发过没有"。
     */
    @Test
    void sentOnceStillPending_accountCleared_nextFastRetrySendsTheSameRequest() {
        PlacedOrder order = placeOrder();
        Long taskId = payTaskId(order);

        // 第 1 次：OutboxProcessor 拿到要发的请求（之后调 bank 超时，不重要）
        PaymentService.PayCall first = paymentService.startPayAttempt(taskId).orElseThrow();
        assertThat(first.fromAccount()).isEqualTo(order.buyerBankAccount());

        clearBankAccount(order);

        // 第 2 次（同一轮的快速重试）
        Optional<PaymentService.PayCall> second = paymentService.startPayAttempt(taskId);

        assertThat(paymentOf(order).getStatus()).as("发过的扣款不能标 FAILED").isNotEqualTo(PaymentStatus.FAILED);
        assertThat(second).as("快速重试照常发").isPresent();
        assertThat(second.get().idempotencyKey()).isEqualTo(first.idempotencyKey());
        assertThat(second.get().fromAccount()).isEqualTo(first.fromAccount());
        assertThat(second.get().toAccount()).isEqualTo(first.toAccount());

        // F8：这时买家点 Pay Now，不能多出一条 PAY 任务（新键）
        setBankAccount(order, order.buyerBankAccount());
        orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());
        assertThat(tasksOf(order)).filteredOn(t -> "PAY".equals(t.getEventType())).hasSize(1);
        assertThat(paymentOf(order).getAttempt()).isEqualTo(1);
    }

    /**
     * F4：两次发送之间，买家的银行账号换成了另一个号；store 的收款账号配置也改了。
     * 真 bank 对"同一个键、不同的账号"回 KEY_CONFLICT → store 当成拒绝 → FAILED → Pay Now 再扣一次。
     * 应该：重试还是发第一次的付款账号和收款账号，bank 按键返回第一次的结果。
     */
    @Test
    void sentOnce_accountsChanged_retrySendsTheRecordedAccounts() {
        PlacedOrder order = placeOrder();
        Long taskId = payTaskId(order);
        PaymentService.PayCall first = paymentService.startPayAttempt(taskId).orElseThrow();

        setBankAccount(order, "ACC-CHANGED-" + order.orderId());
        Object target = AopTestUtils.getTargetObject(paymentService);
        Object storeAccountBefore = ReflectionTestUtils.getField(target, "storeAccount");
        ReflectionTestUtils.setField(target, "storeAccount", "STORE_ACCOUNT_CHANGED");
        try {
            PaymentService.PayCall second = paymentService.startPayAttempt(taskId).orElseThrow();

            assertThat(second.idempotencyKey()).isEqualTo(first.idempotencyKey());
            assertThat(second.fromAccount()).as("同一个键必须发同一个付款账号").isEqualTo(first.fromAccount());
            assertThat(second.toAccount()).as("同一个键必须发同一个收款账号").isEqualTo(first.toAccount());
        } finally {
            ReflectionTestUtils.setField(target, "storeAccount", storeAccountBefore);
        }
    }

    /**
     * F5（回归，现在就该绿）：从没发过就读不到账号 → FAILED。钱不可能扣过，Pay Now 换新键是安全的。
     */
    @Test
    void neverSent_noAccount_failsAndPayNowStartsANewAttempt() {
        PlacedOrder order = placeOrder();
        clearBankAccount(order);
        bankTransferSucceeds();

        runOutboxRounds(1);

        assertThat(transferRequests()).as("没有账号就什么都没发").isEmpty();
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.FAILED);

        setBankAccount(order, order.buyerBankAccount());
        orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());
        runOutboxRounds(3);

        assertThat(distinctKeys()).hasSize(1);
        assertThat(distinctKeys().get(0)).startsWith("PAY-" + order.paymentId() + "-2-");
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
    }

    /**
     * F6 + F7：升级前写的老任务（发过、但没有记下账号），账号又读不到。
     * 不知道第一次发的是哪个账号，不能乱发，也不能说没扣：PAY_UNKNOWN + 告警，10 分钟后再看。
     * 账号回来以后，低频重试照常用同一个键发。
     */
    @Test
    void oldTaskWithoutRecordedAccount_noAccount_staysUnknownWithAlert_thenRetriesWhenAccountReturns(
            CapturedOutput output) {
        PlacedOrder order = placeOrder();
        givenPayUnknown(order);
        // 模拟升级前写的任务：记账号的列是空的
        jdbc.update("UPDATE payment_outbox SET from_account = NULL, to_account = NULL WHERE order_id = ?",
                order.orderId());
        clearBankAccount(order);
        forgetBankRequests();
        bankTransferSucceeds();

        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);

        assertThat(transferRequests()).as("不知道该从哪个账号扣，就不发").isEmpty();
        assertThat(paymentOf(order).getStatus()).as("发过的扣款不能标 FAILED").isEqualTo(PaymentStatus.PAY_UNKNOWN);
        assertThat(openTasksOf(order, "PAY")).as("任务留着，等人工处理或账号回来").hasSize(1);
        assertThat(output.getAll()).contains("ALERT payment_no_account orderId=" + order.orderId());

        // 不到 10 分钟不再看（不是每轮都刷告警）
        runOutboxRounds(1);
        assertThat(transferRequests()).isEmpty();

        // 审查 63 🟡4：10 分钟后再看、账号还是读不到 → 还是不发，但 ALERT 只打第一次（之后 WARN），不每 10 分钟刷一条
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(1);
        assertThat(transferRequests()).isEmpty();
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.PAY_UNKNOWN);
        assertThat(countOf(output.getAll(), "ALERT payment_no_account orderId=" + order.orderId() + " "))
                .as("同一单的告警只打一次").isEqualTo(1);

        // F7：账号回来了，10 分钟后低频重试照常发同一个键
        setBankAccount(order, order.buyerBankAccount());
        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(3);

        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    /**
     * 审查 63 🟡3：升级时停机中断留下的老任务——发过（retryCount = 1）、没记账号、payment 还是 PENDING，账号又读不到。
     * 兜底要把 PENDING 改成 PAY_UNKNOWN（否则前端一直"处理中"、Pay Now 也不动），F6 只覆盖了已经是 PAY_UNKNOWN 的一半。
     */
    @Test
    void oldTaskInterruptedWhilePending_noAccount_becomesPayUnknownWithAlert(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        jdbc.update("UPDATE payment_outbox SET retry_count = 1, from_account = NULL, to_account = NULL "
                + "WHERE order_id = ? AND event_type = 'PAY'", order.orderId());
        clearBankAccount(order);
        bankTransferSucceeds();

        runOutboxRounds(1);

        assertThat(transferRequests()).as("不知道该从哪个账号扣，就不发").isEmpty();
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.PAY_UNKNOWN);
        assertThat(paymentOf(order).getErrorMessage()).contains("already sent");
        assertThat(openTasksOf(order, "PAY")).hasSize(1);
        assertThat(output.getAll()).contains("ALERT payment_no_account orderId=" + order.orderId() + " ");
    }

    /**
     * F9（审查 63 🟠1）：发过、账号已经记下，之后订单行没了（手工改库 / 以后的"清理老订单"）。
     * 现在：照常发 → bank 说成功 → onPaySucceeded 找不到订单抛异常 → 回滚，任务一直 PENDING → 每轮都再打一次 bank。
     * 应该：bank 说扣了就是扣了——payment 记 SUCCESS、任务关掉、ALERT payment_no_order 交给人处理（没有订单，不发货）。
     */
    @Test
    void sentOnce_orderDeleted_bankSaysCharged_recordsSuccessClosesTaskWithAlert(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        givenPayUnknown(order);
        jdbc.update("DELETE FROM orders WHERE id = ?", order.orderId());
        forgetBankRequests();
        bankTransferSucceeds();

        clock.advance(Duration.ofMinutes(10));
        runOutboxRounds(3);

        assertThat(transferRequests()).as("同一个键发一次就拿到结果，不是每轮都打").hasSize(1);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf).containsOnly(payKeyOf(order));
        assertThat(openTasksOf(order, "PAY")).as("任务关掉了").isEmpty();
        assertThat(paymentOf(order).getStatus()).as("bank 说扣了").isEqualTo(PaymentStatus.SUCCESS);
        assertThat(output.getAll()).contains("ALERT payment_no_order orderId=" + order.orderId() + " ");
    }

    private static int countOf(String text, String needle) {
        int count = 0;
        for (int i = text.indexOf(needle); i >= 0; i = text.indexOf(needle, i + 1)) {
            count++;
        }
        return count;
    }
}
