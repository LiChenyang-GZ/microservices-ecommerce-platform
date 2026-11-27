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
        return "ALERT outbox_task_failed taskId=" + task.getId();
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
            assertThat(output.getOut())
                    .contains(alertFor(task) + " type=FAILED orderId=" + order.orderId() + " retries=3")
                    .contains("stock row " + order.warehouseProductId() + " is broken");

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
        assertThat(output.getOut()).contains(alertFor(task) + " type=REFUNDED orderId=" + order.orderId());
        assertThat(occurrences(output.getOut(), alertFor(task))).isEqualTo(1);
    }
}
