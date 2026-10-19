package comp5348.storeservice.payment;

import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 #17、#18 的 store 这一半（来自独立审查 07 号报告 #1、#2）：幂等键不能被猜到。
 *
 * 【层】集成测试（真 Postgres + 假 bank）。
 * 【问题】paymentId、orderId 用户在页面和接口里都看得到；attempt 从 1 开始数。
 *   键如果就是 PAY-{paymentId}-{attempt} / REFUND-{paymentId}，攻击者能算出别人的下一个键，抢先去 bank 占用它。
 * 【只测性质，不定格式】这里只断言"发给 bank 的键不在能猜的那几个里"。键具体长什么样（比如加一段随机数），
 *   要改契约 README 第 4 节和设计 ADR-04，由 Christy 决定。
 */
class PaymentKeyIntegrationTest extends StoreIntegrationTest {

    @Autowired OrderService orderService;

    /** 只靠"看得到的编号"就能拼出来的键：PAY-{paymentId 或 orderId}-{1..3} */
    private static List<String> guessablePayKeys(PlacedOrder order) {
        return Stream.of(order.paymentId(), order.orderId())
                .flatMap(id -> IntStream.rangeClosed(1, 3).mapToObj(attempt -> "PAY-" + id + "-" + attempt))
                .toList();
    }

    /**
     * #17（store 这一半）：扣款键不能由 paymentId / orderId + attempt 算出来。
     */
    @Test
    void payKey_cannotBeGuessedFromPublicIds() {
        PlacedOrder order = placeOrder();
        bankTransferSucceeds();

        runOutboxRounds(1);

        // 取出 store 真正发给 bank 的键
        assertThat(transferRequests()).isNotEmpty();
        String sentKey = keyOf(transferRequests().get(0));
        // 断言：它不是任何一个"能猜到的键"
        assertThat(sentKey).as("扣款键是 %s，攻击者能从 paymentId=%d 猜出来", sentKey, order.paymentId())
                .isNotIn(guessablePayKeys(order));
    }

    /**
     * #18（store 这一半）：退款键不能由 paymentId / orderId 算出来。
     */
    @Test
    void refundKey_cannotBeGuessedFromPublicIds() {
        PlacedOrder order = placePaidOrder();
        bankRefundSucceeds();

        // 用户取消已支付的订单 → 写 REFUND 任务 → OutboxProcessor 去退款
        orderService.cancelOrder(order.orderId());
        runOutboxRounds(1);

        assertThat(refundRequests()).isNotEmpty();
        String sentKey = keyOf(refundRequests().get(0));
        assertThat(sentKey).as("退款键是 %s，攻击者能从 paymentId=%d 猜出来", sentKey, order.paymentId())
                .isNotIn("REFUND-" + order.paymentId(), "REFUND-" + order.orderId());
    }
}
