package comp5348.storeservice.events;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 失败场景表 B10：delivery 挂了一段时间。
 *
 * 旧做法：扣款成功后 store 用 HTTP 调 /api/deliveries/create（重试 3 次），失败就写 DELIVERY_FAILED，
 *   30 秒后"补偿"——给用户退款、取消订单。delivery 只是重启一下，用户的单就没了。
 * 新做法（推进顺序第 5 步"去掉 store 用 HTTP 建配送单"）：store 只写 OrderPaid，事件留在 Kafka 里等着；
 *   delivery 起来后接着读、建单。store 这边**不再**有"建不了单就退款"的补偿。
 *
 * 【层】集成测试。假 delivery 的建单接口一直回 503，扮演"delivery 挂了"。
 * 【红灯为什么红】store 现在还会用 HTTP 建单（假 delivery 收到请求），失败后写 DELIVERY_FAILED，30 秒后退款。
 */
class DeliveryDownIntegrationTest extends DeliveryEventsTestBase {

    @Test
    void deliveryDown_orderPaidWaitsInKafka_noCompensation() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/deliveries/create")).willReturn(aResponse().withStatus(503)));
        PlacedOrder order = placeOrder();
        bankTransferSucceeds();
        bankRefundSucceeds(); // 如果真有人去退款，让它退得成，这样才看得见
        runOutboxRounds(3);
        clock.advance(Duration.ofMinutes(1)); // 远超旧补偿的 30 秒
        runOutboxRounds(3);

        // 断言 1：store 不再用 HTTP 建配送单
        assertThat(deliveryCreateRequests()).as("store 不该再调 /api/deliveries/create").isEmpty();
        // 断言 2：没有"建单失败"的补偿任务，没有退款
        assertThat(tasksOf(order)).extracting(t -> t.getEventType()).doesNotContain("DELIVERY_FAILED");
        assertThat(refundTasksOf(order)).isEmpty();
        assertThat(refundRequests()).isEmpty();
        // 断言 3：订单还是 PAID，OrderPaid 在 Kafka 里等 delivery
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderPaid");
    }
}
