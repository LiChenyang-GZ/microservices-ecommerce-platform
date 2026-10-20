package comp5348.storeservice.events;

import com.comp5348.contracts.events.payload.OrderCancelledPayload;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static com.comp5348.contracts.events.DeliveryEventType.DELIVERY_CREATED;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 失败场景表 C5–C7：用户取消订单时，store 怎么和 delivery 商量。
 *
 * 设计"取消接口"一节：
 *   订单已付款（PAID）→ **一定**同步调 delivery 的 POST /internal/deliveries/cancel-by-order/{orderId}：
 *     200 CANCELLED_BEFORE_CREATED / CANCELLED / ALREADY_CANCELLED → 同一个事务里取消订单、还库存、写 REFUND 任务、写 OrderCancelled；
 *     409 TOO_LATE → 拒绝取消（货已经揽收了）；
 *     超时或 5xx → 拒绝取消，让用户稍后再试（不知道 delivery 那边到底取消没有，不能先退钱）。
 *   订单未付款（PENDING_PAYMENT，含 PAY_UNKNOWN）→ 不调 delivery，直接取消，写 OrderCancelled。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka；delivery 是 WireMock 假的（和假 bank 同一个 WireMock）。
 * 【红灯为什么红】现在的 OrderService.cancelOrder 只在 order.deliveryId != null 时才调 delivery，
 *   而且调的是旧接口 /api/deliveries/cancel-by-order。上了 Kafka 之后，付完款到 DeliveryCreated 回来之间
 *   deliveryId 是 null（C6）→ 根本不问 delivery 就退钱，delivery 随后照样建单发货。
 *   所以：该调的没调（请求数 0）、409 / 5xx 时照样取消成功；另外没有 OrderCancelled 事件（store 不发事件）。
 */
class StoreCancelIntegrationTest extends DeliveryEventsTestBase {

    private static final String CANCEL_PATH = "/internal/deliveries/cancel-by-order/";

    /**
     * C6（4b 红灯的重点）：付款成功了，但 DeliveryCreated 还没回来，store 还不知道 deliveryId。
     * 这时取消也必须先问 delivery —— delivery 可能已经读到 OrderPaid、马上就要建单。
     */
    @Test
    void cancelPaidOrder_beforeDeliveryCreated_stillCallsDelivery() {
        PlacedOrder order = placePaidOrder();
        assertThat(orderOf(order).getDeliveryId()).as("前提：store 还不知道配送单号").isNull();

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).as("调了一次 delivery 的新取消接口").hasSize(1);
        assertThat(deliveryCancelRequests().get(0).getUrl()).isEqualTo(CANCEL_PATH + order.orderId());
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refundTasksOf(order)).hasSize(1);
    }

    /** C5：已经知道 deliveryId 的订单，也走新接口，不再调旧的 /api/deliveries/cancel-by-order */
    @Test
    void cancelPaidOrder_afterDeliveryCreated_callsTheNewInternalEndpoint() {
        PlacedOrder order = placePaidOrder();
        awaitConsumedByStore(publish(order, DELIVERY_CREATED));
        assertThat(orderOf(order).getDeliveryId()).as("前提：store 记下了配送单号").isNotNull();

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).hasSize(1);
        assertThat(FAKE_BANK.findAll(postRequestedFor(urlPathMatching("/api/deliveries/cancel-by-order/.*"))))
                .as("旧接口 delivery 那边已经删了").isEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    /** C5：delivery 的三种 200 都表示"delivery 这边不会再发货了"→ 取消订单、写一条 REFUND、还库存、写 OrderCancelled */
    @ParameterizedTest
    @ValueSource(strings = {"CANCELLED_BEFORE_CREATED", "CANCELLED", "ALREADY_CANCELLED"})
    void deliveryAgrees_orderIsCancelled_andRefunded(String result) {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers(okJson("{\"result\":\"" + result + "\"}"));

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).hasSize(1);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refundTasksOf(order)).hasSize(1);
        assertThat(stockOf(order)).as("库存还回去了").isEqualTo(100);
        assertThat(awaitOrderEventTypes(order, 3)).containsExactly("OrderPlaced", "OrderPaid", "OrderCancelled");
    }

    /** C5：409 TOO_LATE → 拒绝取消。订单还是 PAID，不退钱，库存不动 */
    @Test
    void deliverySaysTooLate_cancelIsRejected() {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers(aResponse().withStatus(409).withHeader("Content-Type", "application/json")
                .withBody("{\"result\":\"TOO_LATE\",\"status\":\"PICKED_UP\"}"));

        assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()));

        assertRejected(order);
    }

    /** C5：delivery 回 5xx → 不知道它那边取消没有，拒绝取消，让用户稍后再试 */
    @Test
    void deliveryFails_cancelIsRejected() {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers(aResponse().withStatus(503));

        assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()));

        assertRejected(order);
    }

    /** C5：delivery 没有回答（连接被重置，对 store 来说和超时一样）→ 拒绝取消 */
    @Test
    void deliveryDoesNotAnswer_cancelIsRejected() {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER));

        assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()));

        assertRejected(order);
    }

    /**
     * C7：未付款的订单（PENDING_PAYMENT）→ 不调 delivery（delivery 不可能有这单），直接取消，写 OrderCancelled（USER_REQUEST）。
     * delivery 读到 OrderCancelled 后会插一个取消标记，挡住以后可能迟到的 OrderPaid（delivery 那边的 B8 测试）。
     */
    @Test
    void cancelUnpaidOrder_doesNotCallDelivery_writesOrderCancelled() {
        PlacedOrder order = placeOrder();

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).isEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderCancelled");
        assertThat(payloadOf(order, "OrderCancelled", OrderCancelledPayload.class).reason()).isEqualTo("USER_REQUEST");
    }

    /** C7：扣款结果未知（PAY_UNKNOWN）也算未付款：不调 delivery，直接取消，写 OrderCancelled */
    @Test
    void cancelPayUnknownOrder_doesNotCallDelivery_writesOrderCancelled() {
        PlacedOrder order = placeOrder();
        bankTransferResultUnknown();
        runOutboxRounds(6);
        assertThat(paymentOf(order).getStatus()).as("前提：结果未知").isEqualTo(PaymentStatus.PAY_UNKNOWN);

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).isEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(awaitOrderEventTypes(order, 2)).containsExactly("OrderPlaced", "OrderCancelled");
    }

    /** 换掉假 delivery 对新取消接口的回答（后登记的剧本优先） */
    private void deliveryAnswers(ResponseDefinitionBuilder answer) {
        FAKE_BANK.stubFor(post(urlPathMatching(CANCEL_PATH + ".*")).willReturn(answer));
    }

    /** 被拒绝的取消：问过 delivery，订单还是 PAID，没有退款任务，库存还锁着 */
    private void assertRejected(PlacedOrder order) {
        assertThat(deliveryCancelRequests()).as("问过 delivery").isNotEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(refundTasksOf(order)).isEmpty();
        assertThat(stockOf(order)).as("库存还锁着").isEqualTo(99);
    }
}
