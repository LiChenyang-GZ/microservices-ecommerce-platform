package comp5348.storeservice.payment;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.scheduler.UnpaidOrderCanceller;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 87 号 R7–R9：付款失败（或一直没付）的订单，15 分钟后由 UnpaidOrderCanceller 取消、还库存。
 * 现在新订单付款失败以后，库存就是从这里还回去的（不再经过 OutboxProcessor 的 FAILED 任务）。
 *
 * 取消每次都失败（数据坏了）时：订单和库存都不能动（不能"半取消"），每分钟照样再试；
 * 但同一张单连续失败 5 次（≈ 5 分钟）要打一次 ALERT unpaid_cancel_stuck，不能只有 ERROR。
 *
 * 【层】集成测试：真 Postgres + 真的 UnpaidOrderCanceller（测试里手动调它，相当于每分钟那一轮）。
 * 【红灯为什么红】现在失败只打 ERROR，R7 等不到 ALERT。
 */
@ExtendWith(OutputCaptureExtension.class)
class UnpaidCancelStuckIntegrationTest extends DeliveryEventsTestBase {

    @Autowired UnpaidOrderCanceller unpaidOrderCanceller;

    private static String alertFor(PlacedOrder order) {
        return "ALERT unpaid_cancel_stuck orderId=" + order.orderId() + " ";
    }

    /** R7：取消每次都失败 → 订单、库存不动；第 5 次失败打一次 ALERT，之后不再打 */
    @Test
    void cancelKeepsFailing_alertsOnceAfterFiveFailures(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        clock.advance(Duration.ofMinutes(16));
        breakStockRow(order.warehouseProductId());
        try {
            for (int i = 1; i <= 4; i++) {
                unpaidOrderCanceller.cancelExpiredUnpaidOrders();
            }
            assertThat(output.getOut()).as("才失败 4 次").doesNotContain(alertFor(order));

            unpaidOrderCanceller.cancelExpiredUnpaidOrders();
            // lastError 锁在 ALERT 这一行上（每轮的 ERROR 行本来就带这句，审查 88 🟡7）
            assertThat(output.getOut()).containsPattern(java.util.regex.Pattern.quote(
                    alertFor(order) + "failures=5 lastError=")
                    + "[^\n]*stock row " + order.warehouseProductId() + " is broken");

            unpaidOrderCanceller.cancelExpiredUnpaidOrders();
            unpaidOrderCanceller.cancelExpiredUnpaidOrders();
            assertThat(occurrences(output.getOut(), alertFor(order))).as("只打一次").isEqualTo(1);

            assertThat(orderOf(order).getStatus()).as("取消没成，订单不动").isEqualTo(OrderStatus.PENDING_PAYMENT);
            assertThat(stockOf(order)).as("库存也不动").isEqualTo(99);
        } finally {
            repairStockRow(order.warehouseProductId());
        }
    }

    /** R8：失败 2 次后好了 → 取消成功、还库存，没有 ALERT */
    @Test
    void cancelFailsTwiceThenWorks_noAlert(CapturedOutput output) {
        PlacedOrder order = placeOrder();
        clock.advance(Duration.ofMinutes(16));
        breakStockRow(order.warehouseProductId());
        try {
            unpaidOrderCanceller.cancelExpiredUnpaidOrders();
            unpaidOrderCanceller.cancelExpiredUnpaidOrders();
        } finally {
            repairStockRow(order.warehouseProductId());
        }

        unpaidOrderCanceller.cancelExpiredUnpaidOrders();

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(stockOf(order)).isEqualTo(100);
        assertThat(output.getOut()).doesNotContain(alertFor(order));
    }

    /** R9：同一轮里一张单卡住，另一张照样取消（不被挡住） */
    @Test
    void oneStuckOrder_doesNotHoldBackTheOthers() {
        PlacedOrder stuck = placeOrder();
        PlacedOrder fine = placeOrder();
        clock.advance(Duration.ofMinutes(16));
        breakStockRow(stuck.warehouseProductId());
        try {
            unpaidOrderCanceller.cancelExpiredUnpaidOrders();

            assertThat(orderOf(fine).getStatus()).isEqualTo(OrderStatus.CANCELLED);
            assertThat(stockOf(fine)).isEqualTo(100);
            assertThat(orderOf(stuck).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        } finally {
            repairStockRow(stuck.warehouseProductId());
        }
    }
}
