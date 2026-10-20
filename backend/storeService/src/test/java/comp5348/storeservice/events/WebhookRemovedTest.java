package comp5348.storeservice.events;

import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失败场景表 E1：webhook 删掉了。配送状态只从 Kafka 来，不能再有一个 HTTP 后门让人改订单状态。
 * 设计：推进顺序第 4 步"替换掉 webhook 和 notification_queue"。
 *
 * 【层】切片风格：MockMvc 直接对 Spring MVC 发请求，不经过真的网络。
 *   这里借用集成测试已经起好的容器（不另起上下文），只是用它的 Web 层。
 * 【红灯为什么红】/api/delivery-webhook 还在：找不到这个配送单号时回 500，而不是 404。
 */
class WebhookRemovedTest extends StoreIntegrationTest {

    @Autowired
    WebApplicationContext web;

    @Test
    void webhookEndpointIsGone() throws Exception {
        MockMvc mvc = MockMvcBuilders.webAppContextSetup(web).build();

        mvc.perform(post("/api/delivery-webhook")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deliveryId\":123456,\"status\":\"PICKED_UP\"}"))
                .andExpect(status().isNotFound());
    }
}
