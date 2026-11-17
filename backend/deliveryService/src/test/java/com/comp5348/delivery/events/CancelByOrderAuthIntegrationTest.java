package com.comp5348.delivery.events;

import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * POST /internal/deliveries/cancel-by-order/{orderId} 只给带服务凭据的调用方（store）。
 * 失败场景表（Obsidian 36 号文件）D1、D2，回归 D3。
 *
 * 【层】集成测试：真的 delivery（Spring + 真 Postgres + 真 Kafka）。请求用 mvcWithFilters() 发：
 *   会经过 servlet 过滤器，不经过网络。不能用 mvc()，它不带过滤器。
 * 【红灯为什么是红】FilterConfig 只把 token 过滤器挂在 /api/*，/internal/* 不经过任何过滤器，
 *   InternalDeliveryController#cancelByOrder 自己也不查：不带凭据、带错凭据都回 200，
 *   配送单真的被取消，Cancelled 事件真的发到了 delivery-events（store 读到后会取消订单并退款）。不是编译错。
 * 【约定】凭据放在 HTTP 头 X-Service-Token；delivery 这边的配置项是 delivery.service-token
 *   （部署时来自环境变量 DELIVERY_SERVICE_TOKEN，和 bank 的不是同一把）。不带或不对 → 401，delivery 什么都不做。
 * 【参数化】每条"被拒绝"的测试跑两遍：token = null（不带头）和 "not-the-token"（带错）。
 */
class CancelByOrderAuthIntegrationTest extends DeliveryIntegrationTest {

    private static final String CANCEL = "/internal/deliveries/cancel-by-order/";

    /** token 为 null 就不带头（curl 直接打 8081 就是这样） */
    private static MockHttpServletRequestBuilder cancel(String orderId, String token) {
        MockHttpServletRequestBuilder request = post(CANCEL + orderId);
        return token == null ? request : request.header(SERVICE_TOKEN_HEADER, token);
    }

    /** D1、D2：配送单是 CREATED，不带 / 带错凭据取消 → 401，配送单还是 CREATED，没有 Cancelled 事件。 */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void cancel_withoutOrWrongToken_is401_andDeliveryStaysCreated(String token) throws Exception {
        String orderId = newOrderId();
        createDelivery(orderId);

        mvcWithFilters().perform(cancel(orderId, token))
                .andExpect(status().isUnauthorized());

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CREATED);
        awaitEventTypes(orderId, 1);
        Thread.sleep(1_000); // 投递程序每 200 毫秒一轮：要是有 Cancelled，这时候早就发出来了
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated");
    }

    /**
     * D1、D2：配送单还不存在（任意订单号）。不带 / 带错凭据 → 401，不插取消标记，没有事件。
     * 现在：插了一行 CANCELLED 的标记并发 Cancelled —— 攻击者不用等配送单出现，猜一个订单号就能让 store 取消它。
     */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void cancel_withoutOrWrongToken_beforeDeliveryExists_is401_andInsertsNoMarker(String token) throws Exception {
        String orderId = newOrderId();

        mvcWithFilters().perform(cancel(orderId, token))
                .andExpect(status().isUnauthorized());

        assertThat(rowsOf(orderId)).as("没有插取消标记").isZero();
        Thread.sleep(1_000);
        assertThat(eventTypesOf(orderId)).isEmpty();
    }

    /** D3 回归：带对凭据照常取消。这条现在就是绿的（delivery 不看这个头），写下来防以后改坏。 */
    @Test
    void cancel_withCorrectToken_stillCancels() throws Exception {
        String orderId = newOrderId();
        createDelivery(orderId);

        mvcWithFilters().perform(cancel(orderId, SERVICE_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CANCELLED"));

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(awaitEventTypes(orderId, 2)).containsExactly("DeliveryCreated", "Cancelled");
    }
}
