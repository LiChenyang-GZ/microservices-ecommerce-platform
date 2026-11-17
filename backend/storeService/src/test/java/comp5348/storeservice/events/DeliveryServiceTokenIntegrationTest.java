package comp5348.storeservice.events;

import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * store 调 delivery 的取消接口时带上服务凭据；delivery 回 401 时 store 怎么办。
 * 失败场景表（Obsidian 36 号文件）D3（store 这一半）、D4。
 *
 * 【层】集成测试：真的 store（Spring + 真 Postgres + 真 Kafka），假 delivery（WireMock）。
 *   调真实的 OrderService#cancelOrder，它会通过 DeliveryAdapter 去问 delivery。
 * 【红灯为什么是红】不是编译错，是 store 少做了事：
 *   - DeliveryAdapter#cancelByOrderId 发请求时不加头 → 假 delivery 收到的请求里没有 X-Service-Token。
 *   - 401 落进 catch (Exception)，和超时、503 一样只打一行 WARN → 没有 ALERT。
 * 【约定】HTTP 头 X-Service-Token；store 的配置项 delivery.service-token。
 *   告警行：ALERT delivery_auth_failed orderId={订单号}。
 */
@ExtendWith(OutputCaptureExtension.class) // 把控制台输出（日志）抓下来，检查有没有打出告警
class DeliveryServiceTokenIntegrationTest extends DeliveryEventsTestBase {

    private static final String CANCEL_PATH = "/internal/deliveries/cancel-by-order/";

    private void deliveryAnswers401() {
        FAKE_BANK.stubFor(post(urlPathMatching(CANCEL_PATH + ".*")).willReturn(aResponse().withStatus(401)));
    }

    /** D3：取消已付款订单时，发给 delivery 的请求带 X-Service-Token。现在：没带。 */
    @Test
    void cancelPaidOrder_carriesTheServiceToken() {
        PlacedOrder order = placePaidOrder();

        orderService.cancelOrder(order.orderId());

        assertThat(deliveryCancelRequests()).as("前提：问过 delivery").hasSize(1);
        FAKE_BANK.verify(postRequestedFor(urlEqualTo(CANCEL_PATH + order.orderId()))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(DELIVERY_SERVICE_TOKEN)));
    }

    /**
     * D4：delivery 回 401（store 的密钥配错了）→ 取消被拒绝，而且是"稍后再试"那一种：
     * 不能当成已取消（订单还是 PAID、不退钱、库存不还），也不能当成 TOO_LATE（包裹并没有被揽收）。
     * 这条本来就绿（401 现在落进 catch (Exception) → UNAVAILABLE），写下来防绿灯时把 401 单独处理改坏。
     */
    @Test
    void deliveryAnswers401_cancelIsRejectedAsRetryable_notTooLate() {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers401();

        assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()))
                .hasMessageContaining("try again later")
                .hasMessageNotContaining("after pickup");

        assertThat(deliveryCancelRequests()).as("问过 delivery").isNotEmpty();
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(refundTasksOf(order)).isEmpty();
        assertThat(stockOf(order)).as("库存还锁着").isEqualTo(99);
    }

    /** D4：401 要打 ALERT。超时和 503 等一会儿自己会好，密钥配错不会，要人去改。现在：只有一行 WARN。 */
    @Test
    void deliveryAnswers401_raisesAlert(CapturedOutput output) {
        PlacedOrder order = placePaidOrder();
        deliveryAnswers401();

        assertThatThrownBy(() -> orderService.cancelOrder(order.orderId()));

        // \b：订单号后面不能紧跟数字，避免 orderId=12 匹配到 orderId=123
        assertThat(output.getAll()).containsPattern("ALERT delivery_auth_failed orderId=" + order.orderId() + "\\b");
    }
}
