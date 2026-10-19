package comp5348.storeservice.payment;

import comp5348.storeservice.model.Payment;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #11、#12、#13：用户点 Pay Now。
 *
 * 【层】集成测试（真 Postgres + 假 bank）。表里 #11、#12 写的是单元层，这里改成了集成层，原因见红灯报告：
 *   现在的 Pay Now 根本不经过 PaymentService，自己直接调 bank；修好以后逻辑会搬家。
 *   用 Mockito 把周围都换成假的，逻辑一搬家，测试就会"假绿"（假对象什么都不做，当然什么都没发生）。
 *   从真实入口 OrderProductService.retryOrderPayment 测，不管逻辑搬到哪，测的都是用户真正点到的那个按钮。
 * 【规则 8】先锁住这一笔 Payment，再按状态处理：
 *   FAILED → 重置为 PENDING、attempt + 1、写一条新的 PAY 任务；PENDING / PAY_UNKNOWN → 什么都不改；SUCCESS → 返回已支付。
 *   无论哪种，Pay Now 自己都不调 bank（只有 OutboxProcessor 调 bank）。
 */
class PayNowIntegrationTest extends StoreIntegrationTest {

    /** 把这一单的 Payment 设成某个状态，并清掉它的待办任务，模拟"之前发生过的事" */
    private void givenPayment(PlacedOrder order, PaymentStatus status) {
        Payment payment = paymentOf(order);
        payment.setStatus(status);
        payment.setAttempt(1);
        paymentRepository.save(payment);
        jdbc.update("DELETE FROM payment_outbox WHERE order_id = ?", order.orderId());
        FAKE_BANK.resetRequests();
    }

    /**
     * #11 Pay Now：FAILED → 重置为 PENDING、attempt + 1、写新 PAY 任务。
     * 只有确定失败（钱肯定没扣）才换新键 PAY-{paymentId}-2。
     */
    @Test
    void payNow_afterFailed_newAttemptNewKey() {
        PlacedOrder order = placeOrder();
        // 之前 bank 明确拒绝过（比如余额不足），用户充了钱回来点 Pay Now
        givenPayment(order, PaymentStatus.FAILED);
        bankTransferSucceeds();

        // 动作：用户点 Pay Now
        orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());

        // 断言 1：Pay Now 自己不调 bank，只写任务（页面显示"支付处理中"）
        assertThat(transferRequests()).as("Pay Now 不许直接调 bank").isEmpty();
        // 断言 2：状态重置、attempt 加 1、有一条新的 PAY 任务
        Payment payment = paymentOf(order);
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.PENDING);
        assertThat(payment.getAttempt()).isEqualTo(2);
        assertThat(openTasksOf(order, "PAY")).hasSize(1);

        // OutboxProcessor 执行这条任务：用的是新键
        runOutboxRounds(1);
        assertThat(transferRequests()).extracting(StoreIntegrationTest::keyOf)
                .containsOnly("PAY-" + order.paymentId() + "-2");
    }

    /**
     * #12 Pay Now：PENDING 或 PAY_UNKNOWN → 什么都不改；SUCCESS → 返回已支付。
     * 结果未知时不能换新键，否则钱可能扣两次。
     * SUCCESS 这一格用的是真实存在的时间窗：Payment 已经成功，但订单还没被下一轮任务改成 PAID，页面上还显示 Pay Now。
     * @ParameterizedTest + @EnumSource：同一个测试用三个状态各跑一遍，报告里是三行。
     */
    @ParameterizedTest(name = "Payment = {0}")
    @EnumSource(value = PaymentStatus.class, names = {"PENDING", "PAY_UNKNOWN", "SUCCESS"})
    void payNow_whilePendingOrUnknown_doesNothing(PaymentStatus current) {
        PlacedOrder order = placeOrder();
        givenPayment(order, current);
        bankTransferSucceeds();

        orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());

        // 断言 1：没有调 bank
        assertThat(transferRequests()).as("Payment 是 %s 时点 Pay Now，bank 不该收到扣款请求", current).isEmpty();
        // 断言 2：没有写新任务
        assertThat(tasksOf(order)).as("不写新任务").isEmpty();
        // 断言 3：Payment 原样不动
        Payment payment = paymentOf(order);
        assertThat(payment.getStatus()).isEqualTo(current);
        assertThat(payment.getAttempt()).isEqualTo(1);
    }

    /**
     * #13 Pay Now 两个标签页同时点 → 只写一条任务，bank 只扣一次。
     * 设计（规则 8）：SELECT … FOR UPDATE 锁住 Payment 行，第二个请求要等第一个提交，然后看到的已经是 PENDING。
     */
    @Test
    void payNow_twoTabsAtOnce_onlyOneTask() throws Exception {
        PlacedOrder order = placeOrder();
        givenPayment(order, PaymentStatus.FAILED);
        // bank 回答慢一点，让两个请求确实重叠
        bankTransferSucceedsSlowly(500);

        // CountDownLatch 当"发令枪"：两个线程都准备好之后同时开跑
        CountDownLatch startGun = new CountDownLatch(1);
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());
        ExecutorService twoTabs = Executors.newFixedThreadPool(2);
        for (int tab = 0; tab < 2; tab++) {
            twoTabs.submit(() -> {
                try {
                    startGun.await();
                    orderProductService.retryOrderPayment(order.orderId(), order.buyerEmail());
                } catch (Throwable e) {
                    errors.add(e); // 记下来就行；第二个标签页被拒绝也是可以接受的
                }
            });
        }
        startGun.countDown();
        twoTabs.shutdown();
        assertThat(twoTabs.awaitTermination(30, TimeUnit.SECONDS)).isTrue();

        // 之后 OutboxProcessor 执行任务
        bankTransferSucceeds();
        runOutboxRounds(2);

        // 断言 1（核心）：bank 一共只收到一次扣款请求 —— 钱只扣一次
        assertThat(transferRequests()).as("两个标签页同时点，bank 只扣一次（失败的请求：%s）", errors).hasSize(1);
        // 断言 2：只写过一条 PAY 任务，attempt 只加了一次
        assertThat(tasksOf(order)).filteredOn(t -> "PAY".equals(t.getEventType())).hasSize(1);
        assertThat(paymentOf(order).getAttempt()).isEqualTo(2);
    }
}
