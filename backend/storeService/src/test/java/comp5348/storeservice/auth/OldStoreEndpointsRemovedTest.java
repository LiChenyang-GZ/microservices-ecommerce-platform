package comp5348.storeservice.auth;

import comp5348.storeservice.dto.CreateOrderRequest;
import comp5348.storeservice.dto.OrderDTO;
import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 PR ②：没有调用方的危险接口删掉（决定 3）。失败场景表 36 号 B3、B4，
 * 以及"PR ② 开工核对"里多出来的 8 个（取消、下单、查单、查付款的旁门）。
 *
 * 【调用方】删之前 grep 过：frontend/src/services/、backend/docker/、k8s/scripts/、delivery、email、bank、tracking
 *   都不调这些接口（2026-11-22）。入口（routes.yaml、nginx.conf）也没放行，但集群里、本机端口上能直接调。
 * 【为什么每个请求都带订单主人的 token】PR ② 之后没登录的请求在安全链就被 401 挡住了，测不出"接口还在不在"。
 *   带上合法 token，证明的是"就算是订单主人也调不了"：接口没了，不是被挡了。
 * 【红灯为什么红】这些接口都还在，而且**真的生效**：没付钱的订单变成 PROCESSING、库存被还回去、建了一笔任意金额的付款……
 * 【期望的状态码】大多是 404。两个例外是 Spring MVC 的路由规则，不是接口还在：
 *   POST /api/orders → 405（同一路径还有管理页用的 GET /api/orders）；
 *   PUT /api/warehouses/unhold → 400（会落到 PUT /api/warehouses/{id}，"unhold" 不是数字）。
 *   所以写操作只断言"4xx 而且什么都没变"，关键证据是"什么都没变"。
 */
class OldStoreEndpointsRemovedTest extends StoreHttpTest {

    @Autowired OrderService orderService;

    /** 红：没付钱的订单变成 PAID 再变成 PROCESSING */
    @Test
    void paymentSuccess_isGone() throws Exception {
        PlacedOrder order = placeOrder();

        int code = call(post("/api/store/orders/{id}/payment-success", order.orderId()), order);

        assertThat(code).isEqualTo(404);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** 红：订单被取消 */
    @Test
    void paymentFailure_isGone() throws Exception {
        PlacedOrder order = placeOrder();

        int code = call(post("/api/store/orders/{id}/payment-failure", order.orderId()), order);

        assertThat(code).isEqualTo(404);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** 红：随便改成 PAID */
    @Test
    void setOrderStatus_isGone() throws Exception {
        PlacedOrder order = placeOrder();

        int code = call(put("/api/orders/{id}/status", order.orderId()).param("status", "PAID"), order);

        assertThat(code).isEqualTo(404);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** 红：取消的第二个入口，不查是谁 */
    @Test
    void secondCancelEndpoint_isGone() throws Exception {
        PlacedOrder order = placeOrder();

        int code = call(put("/api/orders/{id}/cancel", order.orderId()), order);

        assertThat(code).isEqualTo(404);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
    }

    /** 红：给一张还没有付款记录的订单建一笔 0.01 元的付款（金额由调用方给），还写了 PAY 任务 */
    @Test
    void createPaymentWithAnyAmount_isGone() throws Exception {
        PlacedOrder existing = placeOrder();
        Account owner = ownerOf(existing);
        // 只建订单、不建付款：走 OrderService#createOrder（create-with-payment 的第一步）
        OrderDTO bare = orderService.createOrder(new CreateOrderRequest(owner.getId(),
                List.of(new CreateOrderRequest.OrderItemRequest(productIdOf(existing), 1))));

        int code = mvc.perform(post("/api/payments")
                        .header("Authorization", bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"orderId\":" + bare.getId() + ",\"amount\":0.01}"))
                .andReturn().getResponse().getStatus();

        assertThat(code).isBetween(400, 499);
        assertThat(paymentRepository.findByOrderId(bare.getId())).as("没有建出付款").isEmpty();
    }

    /** 红：用别人的 userId 建订单（锁了库存，不建付款） */
    @Test
    void createOrderWithoutPayment_isGone() throws Exception {
        PlacedOrder existing = placeOrder();
        Account victim = ownerOf(existing);
        int before = ordersOfUser(victim.getId());

        int code = mvc.perform(post("/api/orders")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":" + victim.getId() + ",\"orderItems\":[{\"productId\":"
                                + productIdOf(existing) + ",\"quantity\":1}]}"))
                .andReturn().getResponse().getStatus();

        assertThat(code).isBetween(400, 499);
        assertThat(ordersOfUser(victim.getId())).as("受害人名下没有多出订单").isEqualTo(before);
    }

    /** 红：把这一单锁着的库存还回去（订单还在、库存却回来了，可以超卖） */
    @Test
    void unhold_isGone() throws Exception {
        PlacedOrder order = placeOrder();
        String txIds = jdbc.queryForObject(
                "SELECT inventory_transaction_ids FROM orders WHERE id = ?", String.class, order.orderId());
        int stockBefore = warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity();

        int code = call(put("/api/warehouses/unhold")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"inventoryTransactionIds\":[" + txIds + "]}"), order);

        assertThat(code).isBetween(400, 499);
        assertThat(warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity())
                .as("库存没被还回去").isEqualTo(stockBefore);
    }

    /**
     * 绿灯时才发现（没在红灯提交里跑过红）：这是个 GET，却会真的锁库存（orderId 为空的 HOLD），
     * 任何登录用户反复调就能把库存锁光。没人调用，照决定 3 删掉。
     */
    @Test
    void stockHoldingGet_isGone() throws Exception {
        PlacedOrder order = placeOrder();
        int stockBefore = warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity();

        int code = call(get("/api/warehouses/available/{productId}/{quantity}", productIdOf(order), 5), order);

        assertThat(code).isEqualTo(404);
        assertThat(warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity())
                .as("库存没被锁走").isEqualTo(stockBefore);
    }

    /** 红：这些读接口都回 200，能看到任何人的订单、付款、出库记录，能查邮箱有没有注册 */
    @Test
    void readEndpointsThatLeakOtherPeoplesOrders_areGone() throws Exception {
        PlacedOrder order = placeOrder();
        Long ownerId = ownerOf(order).getId();

        Map<String, MockHttpServletRequestBuilder> reads = new LinkedHashMap<>();
        reads.put("GET /api/orders/{id}", get("/api/orders/{id}", order.orderId()));
        reads.put("GET /api/orders/user/{userId}", get("/api/orders/user/{userId}", ownerId));
        reads.put("GET /api/orders/status/{status}", get("/api/orders/status/{status}", "PENDING_PAYMENT"));
        reads.put("GET /api/orders/{id}/inventory-records", get("/api/orders/{id}/inventory-records", order.orderId()));
        reads.put("GET /api/payments/order/{orderId}", get("/api/payments/order/{orderId}", order.orderId()));
        reads.put("GET /api/payments/{paymentId}", get("/api/payments/{paymentId}", order.paymentId()));
        // 44 号审查 🟡5：和删掉的 /api/orders/{id}/inventory-records 读的是同一批数据，绿灯时漏了
        reads.put("GET /api/warehouses/audit-logs/order/{orderId}",
                get("/api/warehouses/audit-logs/order/{orderId}", order.orderId()));
        reads.put("GET /api/warehouses/admin/out-transactions/order/{orderId}",
                get("/api/warehouses/admin/out-transactions/order/{orderId}", order.orderId()));
        // 44 号审查 🟡4：前端、脚本、别的服务都不调，却是公开的，能用来枚举"这个邮箱有没有账户、激活没有"
        reads.put("GET /api/user/check-verified/{email}",
                get("/api/user/check-verified/{email}", order.buyerEmail()));
        reads.put("POST /api/user/check-email", post("/api/user/check-email")
                .contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"" + order.buyerEmail() + "\"}"));

        Map<String, Integer> codes = new LinkedHashMap<>();
        for (Map.Entry<String, MockHttpServletRequestBuilder> read : reads.entrySet()) {
            codes.put(read.getKey(), call(read.getValue(), order));
        }

        Map<String, Integer> allGone = new LinkedHashMap<>();
        reads.keySet().forEach(k -> allGone.put(k, 404));
        assertThat(codes).isEqualTo(allGone);
    }

    /** 带上订单主人的 token 发请求，回状态码 */
    private int call(MockHttpServletRequestBuilder request, PlacedOrder order) throws Exception {
        return mvc.perform(request.header("Authorization", bearer(ownerOf(order))))
                .andReturn().getResponse().getStatus();
    }
}
