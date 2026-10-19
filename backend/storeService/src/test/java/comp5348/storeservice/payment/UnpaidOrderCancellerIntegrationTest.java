package comp5348.storeservice.payment;

import comp5348.storeservice.model.Order;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.scheduler.UnpaidOrderCanceller;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;

/**
 * 失败场景表 #19（来自独立审查 07 号报告 #3）：自动取消拿着旧列表，取消了已经付款的订单。
 *
 * 【层】集成测试（真 Postgres + 假 bank）。
 * 【问题】UnpaidOrderCanceller 先读一次"待支付"订单列表，再一个个调 cancelOrder。
 *   读完列表到真正取消之间，低频重试可能刚好扣款成功、订单变成 PAID。
 *   cancelOrder 锁了订单行，但只拒绝"已揽收 / 运输中 / 已送达 / 已取消"，不拒绝 PAID → 按时付款的订单被取消、退款。
 * 【怎么造出这个时间窗】@MockitoSpyBean：把真的 OrderRepository 包一层"间谍"，其他方法照常工作，
 *   只让 findByStatus(PENDING_PAYMENT) 返回付款之前读到的那份旧列表。不靠运气撞时间，每次都能复现。
 * 【应该】自动取消在锁住订单行之后重新看一眼：已经不是待支付了就跳过。
 *   （用户自己点"取消"已支付的订单仍然允许，那会退款，见 #8。）
 */
class UnpaidOrderCancellerIntegrationTest extends StoreIntegrationTest {

    // 包住容器里唯一的 OrderRepository；父类里注入的 orderRepository 拿到的也是这个间谍
    @MockitoSpyBean OrderRepository spiedOrders;

    @Autowired UnpaidOrderCanceller unpaidOrderCanceller;

    @Test
    void autoCancel_withStaleList_skipsOrderThatGotPaid() {
        PlacedOrder order = placeOrder();

        // 取消器"刚读完列表"的那一刻：这一单还是待支付
        List<Order> staleList = orderRepository.findByStatus(OrderStatus.PENDING_PAYMENT).stream()
                .filter(o -> o.getId().equals(order.orderId()))
                .toList();
        assertThat(staleList).as("前提：读列表时订单还是待支付").hasSize(1);

        // 就在这之后，扣款成功了（比如 PAY_UNKNOWN 的低频重试刚好拿到成功）
        bankTransferSucceeds();
        runOutboxRounds(2);
        assertThat(orderOf(order).getStatus()).as("前提：订单已经付款").isEqualTo(OrderStatus.PAID);

        // 取消器手里拿的还是旧列表；已经过了 15 分钟
        doReturn(staleList).when(spiedOrders).findByStatus(OrderStatus.PENDING_PAYMENT);
        clock.advance(Duration.ofMinutes(16));
        unpaidOrderCanceller.cancelExpiredUnpaidOrders();

        // 断言 1（核心）：已付款的订单不能被"超时未付款"取消
        assertThat(orderOf(order).getStatus()).as("订单已付款，自动取消应该跳过它").isEqualTo(OrderStatus.PAID);
        // 断言 2：钱也不动：Payment 还是 SUCCESS，没有写退款任务
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(tasksOf(order)).noneMatch(t -> "REFUND".equals(t.getEventType()));
    }
}
