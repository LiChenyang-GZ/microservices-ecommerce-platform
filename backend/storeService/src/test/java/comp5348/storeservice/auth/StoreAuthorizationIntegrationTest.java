package comp5348.storeservice.auth;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 PR ②：store 的用户接口要登录，而且只能动自己的订单。失败场景表 36 号 A1、A4–A7、A10，
 * 以及"PR ② 开工核对"一节里"每个接口改完以后"那张表的前五行。
 *
 * 【层】集成测试：MockMvc 挂上 Spring Security 过滤器链（StoreHttpTest），真的 Postgres，假的 bank / delivery。
 * 【红灯为什么红】SecurityConfig#filterChain 最后是 anyRequest().permitAll()，JwtAuthenticationFilter
 *   遇到坏 token 只当成没登录；各接口（除了 Pay Now）都不看是谁。所以不带 token、带过期 / 伪造的 token、
 *   带别人的 token，取消、查单、下单、退款都**真的成功了**。
 * 【什么本来就绿】主人自己操作的几条、公开接口那几条、Pay Now 不带 token：它们防的是改坏。
 */
class StoreAuthorizationIntegrationTest extends StoreHttpTest {

    // ---------- 取消（A4） ----------

    @Nested
    class Cancel {

        @Test
        void withoutToken_isRejected_andOrderStaysAsItWas() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(post("/api/store/orders/{id}/cancel", order.orderId()))
                    .andExpect(status().isUnauthorized());

            assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void withExpiredToken_isRejected() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(post("/api/store/orders/{id}/cancel", order.orderId())
                            .header("Authorization", expiredBearer(ownerOf(order))))
                    .andExpect(status().isUnauthorized());

            assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        @Test
        void withForgedToken_isRejected() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(post("/api/store/orders/{id}/cancel", order.orderId())
                            .header("Authorization", forgedBearer(ownerOf(order))))
                    .andExpect(status().isUnauthorized());

            assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        }

        /** 已付款的单：取消会去问 delivery、写退款任务。别人来取消，这些都不能发生 */
        @Test
        void someoneElsesPaidOrder_is404_andNothingHappens() throws Exception {
            PlacedOrder order = placePaidOrder();
            Account stranger = newUser();

            mvc.perform(post("/api/store/orders/{id}/cancel", order.orderId())
                            .header("Authorization", bearer(stranger)))
                    .andExpect(status().isNotFound());

            assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
            assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(deliveryCancelRequests()).as("没去叫 delivery 取消配送").isEmpty();
            assertThat(openTasksOf(order, "REFUND")).as("没写退款任务").isEmpty();
        }

        @Test
        void owner_canStillCancel() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(post("/api/store/orders/{id}/cancel", order.orderId())
                            .header("Authorization", bearer(ownerOf(order))))
                    .andExpect(status().isOk());

            assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        }
    }

    // ---------- 查单个订单（A6） ----------

    @Nested
    class ViewOrder {

        @Test
        void withoutToken_isRejected() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(get("/api/store/orders/{id}/with-payment", order.orderId()))
                    .andExpect(status().isUnauthorized());
        }

        /** 404 而不是 403：订单号是连续的，403 等于告诉对方"这个号有订单" */
        @Test
        void someoneElsesOrder_is404() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(get("/api/store/orders/{id}/with-payment", order.orderId())
                            .header("Authorization", bearer(newUser())))
                    .andExpect(status().isNotFound());
        }

        @Test
        void owner_seesIt() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(get("/api/store/orders/{id}/with-payment", order.orderId())
                            .header("Authorization", bearer(ownerOf(order))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.order.id").value(order.orderId()));
        }
    }

    // ---------- 按 userId 查订单列表（A6） ----------

    @Nested
    class ListOrders {

        @Test
        void withoutToken_isRejected() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(get("/api/store/orders/user/{userId}/with-payment", ownerOf(order).getId()))
                    .andExpect(status().isUnauthorized());
        }

        @Test
        void someoneElsesList_is403() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(get("/api/store/orders/user/{userId}/with-payment", ownerOf(order).getId())
                            .header("Authorization", bearer(newUser())))
                    .andExpect(status().isForbidden());
        }

        @Test
        void owner_seesOwnList() throws Exception {
            PlacedOrder order = placeOrder();
            Account owner = ownerOf(order);

            mvc.perform(get("/api/store/orders/user/{userId}/with-payment", owner.getId())
                            .header("Authorization", bearer(owner)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$[*].order.id", hasItem(order.orderId().intValue())));
        }
    }

    // ---------- 下单（A5、A10） ----------

    @Nested
    class PlaceOrder {

        @Test
        void withoutToken_isRejected_andNoOrderIsCreated() throws Exception {
            PlacedOrder existing = placeOrder();
            Account victim = ownerOf(existing);
            int before = ordersOfUser(victim.getId());

            mvc.perform(post("/api/store/orders/create-with-payment")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(victim.getId(), productIdOf(existing))))
                    .andExpect(status().isUnauthorized());

            assertThat(ordersOfUser(victim.getId())).as("受害人名下没有多出订单").isEqualTo(before);
        }

        /** 请求体里写别人的 userId：忽略它，订单记在 token 的主人名下（小决定：忽略而不是 403，前端不用改） */
        @Test
        void userIdInBody_isIgnored_orderBelongsToTokenOwner() throws Exception {
            PlacedOrder existing = placeOrder();
            Account victim = ownerOf(existing);
            Account attacker = newUser();
            int victimBefore = ordersOfUser(victim.getId());

            mvc.perform(post("/api/store/orders/create-with-payment")
                            .header("Authorization", bearer(attacker))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(orderBody(victim.getId(), productIdOf(existing))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.order.userId").value(attacker.getId()));

            assertThat(ordersOfUser(victim.getId())).as("受害人名下没有多出订单").isEqualTo(victimBefore);
            assertThat(ordersOfUser(attacker.getId())).as("订单记在攻击者自己名下").isEqualTo(1);
        }

        private String orderBody(Long userId, Long productId) {
            return "{\"userId\":" + userId + ",\"orderItems\":[{\"productId\":" + productId + ",\"quantity\":1}]}";
        }
    }

    // ---------- Pay Now ----------

    @Nested
    class PayNow {

        /** 本来就绿：StoreController#retryPayment 自己查了登录 */
        @Test
        void withoutToken_isRejected() throws Exception {
            PlacedOrder order = placeOrder();

            mvc.perform(post("/api/store/orders/{id}/retry-payment", order.orderId()))
                    .andExpect(status().isUnauthorized());
        }

        /** 原来回 400（"does not belong to the current user"）；统一成和查单、取消一样的 404 */
        @Test
        void someoneElsesOrder_is404_andNoNewChargeIsQueued() throws Exception {
            PlacedOrder order = placeOrder();
            int payTasksBefore = openTasksOf(order, "PAY").size();

            mvc.perform(post("/api/store/orders/{id}/retry-payment", order.orderId())
                            .header("Authorization", bearer(newUser())))
                    .andExpect(status().isNotFound());

            assertThat(openTasksOf(order, "PAY")).hasSize(payTasksBefore);
        }
    }

    // ---------- 退款（A1；A2 在 PR ③ 改成管理员才能调） ----------

    @Nested
    class Refund {

        @Test
        void withoutToken_isRejected_andNoRefundIsQueued() throws Exception {
            PlacedOrder order = placePaidOrder();

            mvc.perform(post("/api/payments/{orderId}/refund", order.orderId()))
                    .andExpect(status().isUnauthorized());

            assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
            assertThat(openTasksOf(order, "REFUND")).isEmpty();
        }
    }

    // ---------- 公开接口（本来就绿，防改坏） ----------

    @Nested
    class PublicEndpoints {

        @Test
        void catalogue_needsNoToken() throws Exception {
            mvc.perform(get("/api/products")).andExpect(status().isOk());
        }

        /** 前端 localStorage 里留着过期 token 时，看商品不能被 401 踢回登录页 */
        @Test
        void catalogue_withExpiredToken_stillWorks() throws Exception {
            mvc.perform(get("/api/products").header("Authorization", expiredBearer(newUser())))
                    .andExpect(status().isOk());
        }

        /** delivery 的 TokenValidationClient、tracking 的 auth.go 不带用户登录头调它（B5） */
        @Test
        void validateToken_isCallableByOtherServices() throws Exception {
            Account user = newUser();
            String token = bearer(user).substring("Bearer ".length());

            mvc.perform(post("/api/user/validate-token")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"token\":\"" + token + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.valid").value(true))
                    .andExpect(jsonPath("$.userId").value(user.getId()));
        }

        @Test
        void checkEmail_needsNoToken() throws Exception {
            mvc.perform(post("/api/user/check-email")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"email\":\"nobody@test.local\"}"))
                    .andExpect(status().isOk());
        }

        /** 探针不带 token。测试里 RabbitMQ 连不上，健康可能是 DOWN（503），只要不是 401 */
        @Test
        void health_needsNoToken() throws Exception {
            int code = mvc.perform(get("/actuator/health")).andReturn().getResponse().getStatus();
            assertThat(code).isNotEqualTo(401).isNotEqualTo(403);
        }
    }
}
