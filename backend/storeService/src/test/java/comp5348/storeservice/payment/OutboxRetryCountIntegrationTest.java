package comp5348.storeservice.payment;

import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.service.OutboxService;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * 失败场景表 87 号 R1–R5：OutboxProcessor 的"非钱"任务（老的付款失败还库存 FAILED、退款成功邮件 REFUNDED）
 * 失败时，重试次数要真的记进库，到上限（3 次）标 FAILED、打一次 ALERT。
 *
 * 【层】集成测试：真 Postgres + 真的 OutboxProcessor。还库存的失败用 Postgres 触发器造（breakStockRow）：
 *   和"数据坏了"一样，每次都失败、而且在数据库那一层失败，会把整个事务弄成只能回滚。
 * 【FAILED 任务从哪来】现在的代码已经不写它了（7b654a5 删了调用），只有很老的库里会剩下。
 *   测试直接调还留着的 OutboxService#createPaymentFailedEvent 造一条。
 * 【红灯为什么红】计数和任务状态写在做事的同一个事务里，事务一回滚计数也跟着回滚：
 *   R1 retryCount 一直是 0、任务一直 PENDING；R2、R5 没有 ALERT。
 */
@ExtendWith(OutputCaptureExtension.class)
class OutboxRetryCountIntegrationTest extends DeliveryEventsTestBase {

    @Autowired OutboxService outboxService;

    /** 下一单（锁了 1 件库存），去掉它的 PAY 任务，换成一条老的"付款失败、去还库存"任务 */
    private PlacedOrder orderWithLegacyFailedTask() {
        PlacedOrder order = placeOrder();
        jdbc.update("DELETE FROM payment_outbox WHERE order_id = ?", order.orderId());
        outboxService.createPaymentFailedEvent(order.orderId(), "Insufficient balance");
        assertThat(stockOf(order)).as("前提：下单锁了 1 件").isEqualTo(99);
        return order;
    }

    private PaymentOutbox onlyTaskOf(PlacedOrder order) {
        assertThat(tasksOf(order)).hasSize(1);
        return tasksOf(order).get(0);
    }

    private static String alertFor(PaymentOutbox task) {
        return "ALERT outbox_task_failed taskId=" + task.getId() + " "; // 结尾空格：taskId=12 不能把 taskId=123 也算进去
    }

    /** R1：还库存每次都失败 → 每轮计数 +1 真的写进库，第 3 轮后任务 FAILED，不再重试 */
    @Test
    void unholdAlwaysFails_retryCountIsSaved_andTaskFailsAfterMaxRetries() {
        PlacedOrder order = orderWithLegacyFailedTask();
        breakStockRow(order.warehouseProductId());
        try {
            for (int round = 1; round <= 3; round++) {
                runOutboxRounds(1);
                assertThat(onlyTaskOf(order).getRetryCount()).as("第 %d 轮之后的 retryCount", round).isEqualTo(round);
            }
            assertThat(onlyTaskOf(order).getStatus()).isEqualTo("FAILED");
            assertThat(stockOf(order)).as("库存没还上（数据坏了），要人来看").isEqualTo(99);

            runOutboxRounds(2);
            assertThat(onlyTaskOf(order).getRetryCount()).as("FAILED 之后不再重试").isEqualTo(3);
        } finally {
            repairStockRow(order.warehouseProductId());
        }
    }

    /** R2：到上限那一轮打一次 ALERT（带 taskId、类型、orderId、最后的错误），之后不再打 */
    @Test
    void unholdAlwaysFails_alertsOnceAtMaxRetries(CapturedOutput output) {
        PlacedOrder order = orderWithLegacyFailedTask();
        PaymentOutbox task = onlyTaskOf(order);
        breakStockRow(order.warehouseProductId());
        try {
            runOutboxRounds(2);
            assertThat(output.getOut()).as("还没到上限，不该告警").doesNotContain(alertFor(task));

            runOutboxRounds(1);
            // lastError 要锁在 ALERT 这一行上：每轮的 ERROR 行本来就带这句，只查整个输出证明不了 ALERT 填对了（审查 88 🟡7）
            assertThat(output.getOut()).containsPattern(java.util.regex.Pattern.quote(
                    alertFor(task) + "type=FAILED orderId=" + order.orderId() + " retries=3 lastError=")
                    + "[^\n]*stock row " + order.warehouseProductId() + " is broken");

            runOutboxRounds(3);
            assertThat(occurrences(output.getOut(), alertFor(task))).as("只打一次").isEqualTo(1);
        } finally {
            repairStockRow(order.warehouseProductId());
        }
    }

    /** R3：第 1 轮失败、第 2 轮好了（暂时性错误）→ 第 2 轮还上库存、PROCESSED，retryCount = 1，没有 ALERT */
    @Test
    void unholdFailsOnceThenWorks_stockComesBack_noAlert(CapturedOutput output) {
        PlacedOrder order = orderWithLegacyFailedTask();
        PaymentOutbox task = onlyTaskOf(order);
        breakStockRow(order.warehouseProductId());
        try {
            runOutboxRounds(1);
            assertThat(onlyTaskOf(order).getRetryCount()).isEqualTo(1);
            assertThat(onlyTaskOf(order).getStatus()).isEqualTo("PENDING");
        } finally {
            repairStockRow(order.warehouseProductId());
        }

        runOutboxRounds(1);
        assertThat(onlyTaskOf(order).getStatus()).isEqualTo("PROCESSED");
        assertThat(onlyTaskOf(order).getRetryCount()).isEqualTo(1);
        assertThat(stockOf(order)).isEqualTo(100);
        assertThat(output.getOut()).doesNotContain(alertFor(task));
    }

    /** R4：一切正常 → 还库存、发付款失败邮件、PROCESSED（回归） */
    @Test
    void legacyFailedTask_releasesStock_andSendsEmail() {
        PlacedOrder order = orderWithLegacyFailedTask();

        runOutboxRounds(1);

        assertThat(onlyTaskOf(order).getStatus()).isEqualTo("PROCESSED");
        assertThat(onlyTaskOf(order).getRetryCount()).isZero();
        assertThat(stockOf(order)).isEqualTo(100);
        verify(emailAdapter).sendOrderFailed(eq(order.buyerEmail()), eq(String.valueOf(order.orderId())), anyString());
    }

    /** R5：退款成功邮件任务处理不了（payload 缺 refundTxnId）→ 到上限 FAILED，也打 ALERT */
    @Test
    void refundEmailTaskKeepsFailing_failsAfterMaxRetries_withAlert(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        jdbc.update("DELETE FROM payment_outbox WHERE order_id = ?", order.orderId());
        outboxService.createRefundSuccessEvent(order.orderId(), "8001");
        jdbc.update("UPDATE payment_outbox SET payload = ? WHERE order_id = ?",
                "{\"orderId\":" + order.orderId() + "}", order.orderId());
        PaymentOutbox task = onlyTaskOf(order);

        runOutboxRounds(4);

        assertThat(onlyTaskOf(order).getStatus()).isEqualTo("FAILED");
        assertThat(onlyTaskOf(order).getRetryCount()).isEqualTo(3);
        assertThat(output.getOut()).contains(alertFor(task) + "type=REFUNDED orderId=" + order.orderId());
        assertThat(occurrences(output.getOut(), alertFor(task))).isEqualTo(1);
    }

    /**
     * R5b（审查 88 🟡3）：原来 #7 的那条路——里面某一步把事务标成只能回滚，异常在里面被 catch 掉，
     * 提交时才 UnexpectedRollbackException。这里用退款邮件：发邮件时事务被标成只能回滚、再抛，
     * processRefundSuccess 吞掉这个异常（"邮件失败不影响主流程"）。计数照样要 +1、到上限 FAILED + 一次 ALERT。
     * （main 上这条是红的：retry_count 一直 0。）
     */
    @Test
    void failureSwallowedInsideButTransactionRollbackOnly_isStillCounted(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        jdbc.update("DELETE FROM payment_outbox WHERE order_id = ?", order.orderId());
        outboxService.createRefundSuccessEvent(order.orderId(), "8002");
        PaymentOutbox task = onlyTaskOf(order);
        // 在事务里调一次会失败的仓库方法：仓库代理的事务拦截器把外层事务标成只能回滚（和查账号时库出错一样），再抛
        org.mockito.Mockito.doAnswer(inv -> {
            accountRepository.findById(null);
            throw new RuntimeException("not reached");
        }).when(emailAdapter).sendRefundSuccess(anyString(), anyString(), anyString());

        runOutboxRounds(4);

        assertThat(onlyTaskOf(order).getStatus()).isEqualTo("FAILED");
        assertThat(onlyTaskOf(order).getRetryCount()).isEqualTo(3);
        assertThat(occurrences(output.getOut(), alertFor(task))).isEqualTo(1);
        // 审查 88 🟡6：这时最里层的异常只是 "Transaction silently rolled back…"，看了不知道查什么。ALERT 要说清楚
        assertThat(output.getOut()).contains(alertFor(task) + "type=REFUNDED orderId=" + order.orderId()
                + " retries=3 lastError=rolled back by an error caught inside the task (see the log lines before this)");
    }

    /**
     * 审查 88 🟡2：recordFailure 只动还是 PENDING 的任务。做事的事务其实提交了（提交时连接断了、客户端以为失败），
     * 或者滚动更新时另一个 pod 已经处理完了：任务已是 PROCESSED，不能再 +1、更不能被改成 FAILED、打假 ALERT。
     */
    @Test
    void recordFailure_leavesATaskThatIsNoLongerPendingAlone(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        jdbc.update("DELETE FROM payment_outbox WHERE order_id = ?", order.orderId());
        outboxService.createRefundSuccessEvent(order.orderId(), "8003");
        PaymentOutbox task = onlyTaskOf(order);
        jdbc.update("UPDATE payment_outbox SET status = 'PROCESSED', retry_count = 2 WHERE id = ?", task.getId());

        org.springframework.test.util.ReflectionTestUtils.invokeMethod(
                (Object) org.springframework.test.util.AopTestUtils.getUltimateTargetObject(outboxProcessor),
                "recordFailure", task.getId(), "commit result unknown");

        assertThat(onlyTaskOf(order).getStatus()).isEqualTo("PROCESSED");
        assertThat(onlyTaskOf(order).getRetryCount()).isEqualTo(2);
        assertThat(output.getOut()).doesNotContain(alertFor(task));
    }
}
