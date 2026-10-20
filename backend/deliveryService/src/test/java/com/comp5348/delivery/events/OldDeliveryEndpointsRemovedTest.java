package com.comp5348.delivery.events;

import com.comp5348.delivery.support.DeliveryIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Christy 的决定 ❓5：删掉三个旧接口。
 *   - POST /api/deliveries/create、/create/batch：store 不再调（建单只来自 OrderPaid）。留着就是第二个建单入口，
 *     能给没付款的订单建单，还绕过"一个订单一张配送单"。
 *   - POST /api/deliveries/cancel-by-order/{orderId}：被 /internal/... 新接口替代（旧的分不清"已揽收"和"不存在"）。
 * 保留 POST /api/deliveries/{id}/cancel：前端配送详情页在用；它产生的 Cancelled 由 store 补完取消（4a 的 A11）。
 *
 * 【层】MockMvc，不经过网络。
 * 【红灯为什么红】三个接口都还在：建单回 200，旧取消回 400（配送单不存在）。
 */
class OldDeliveryEndpointsRemovedTest extends DeliveryIntegrationTest {

    private static final String DELIVERY_JSON = """
            {"orderId":"%s","email":"x@test.local","userName":"x","toAddress":"1 Test Street",
             "fromAddress":["Warehouse-1"],"productName":"Test Phone","quantity":1}""";

    @Test
    void createEndpointIsGone() throws Exception {
        mvc().perform(post("/api/deliveries/create").contentType(MediaType.APPLICATION_JSON)
                        .content(DELIVERY_JSON.formatted(newOrderId())))
                .andExpect(status().isNotFound());
    }

    @Test
    void createBatchEndpointIsGone() throws Exception {
        mvc().perform(post("/api/deliveries/create/batch").contentType(MediaType.APPLICATION_JSON)
                        .content("[" + DELIVERY_JSON.formatted(newOrderId()) + "]"))
                .andExpect(status().isNotFound());
    }

    @Test
    void oldCancelByOrderEndpointIsGone() throws Exception {
        mvc().perform(post("/api/deliveries/cancel-by-order/" + newOrderId()))
                .andExpect(status().isNotFound());
    }
}
