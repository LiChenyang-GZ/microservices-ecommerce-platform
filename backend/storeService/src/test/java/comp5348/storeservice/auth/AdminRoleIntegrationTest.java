package comp5348.storeservice.auth;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.model.Warehouse;
import comp5348.storeservice.support.StoreHttpTest;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权 PR ③：管理员角色。失败场景表 36 号 A3、A13、A14、B1、B2、B6，决定 2（Account 加 role、JWT 带 role）、决定 4（退款只给管理员）。
 *
 * 【层】集成测试：MockMvc + Spring Security 过滤器链（StoreHttpTest），真的 Postgres。
 * 【角色从哪来】只看 token 里的 role claim（无状态）。管理员的 token 由 StoreHttpTest#adminBearer 签，
 *   和登录接口用同一把密钥；红灯时 store 还不认这个 claim，所以"管理员能做"的几条本来就绿（谁登录都能做）。
 * 【红灯为什么红】SecurityConfig 只分"登录 / 没登录"，没有角色：普通用户读全站订单、改价格、改库存、建商品、删仓库
 *   都**真的成功了**；订单主人能给已付款的单退款（真的进了 REFUND_PENDING）；管理员给别人的单退款反而 404（PR ② 的归属检查）；
 *   validate-token 和登录的回复里没有 role。
 */
class AdminRoleIntegrationTest extends StoreHttpTest {

    /** 和 AccountService 一样的编码方式（store 没有把它注册成 bean，AccountService 自己 new 的） */
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    // ---------- B1：管理页读的数据 ----------

    @Test
    void normalUser_cannotReadAdminData() throws Exception {
        PlacedOrder order = placeOrder();
        Long warehouseId = warehouseIdOf(order);
        Long productId = productIdOf(order);
        String user = bearer(newUser());

        Map<String, MockHttpServletRequestBuilder> reads = new LinkedHashMap<>();
        reads.put("GET /api/orders", get("/api/orders"));
        reads.put("GET /api/warehouses", get("/api/warehouses"));
        reads.put("GET /api/warehouses/{id}", get("/api/warehouses/{id}", warehouseId));
        reads.put("GET /api/warehouses/audit-logs/out-transactions", get("/api/warehouses/audit-logs/out-transactions"));
        reads.put("GET /api/warehouses/audit-logs/failed", get("/api/warehouses/audit-logs/failed"));
        reads.put("GET /api/warehouses/admin/out-transactions", get("/api/warehouses/admin/out-transactions"));
        reads.put("GET /api/warehouses/{id}/inventory-records", get("/api/warehouses/{id}/inventory-records", warehouseId));
        reads.put("GET /api/products/{id}/inventory-records", get("/api/products/{id}/inventory-records", productId));

        Map<String, Integer> codes = new LinkedHashMap<>();
        for (Map.Entry<String, MockHttpServletRequestBuilder> read : reads.entrySet()) {
            codes.put(read.getKey(), mvc.perform(read.getValue().header("Authorization", user))
                    .andReturn().getResponse().getStatus());
        }
        Map<String, Integer> allForbidden = new LinkedHashMap<>();
        reads.keySet().forEach(k -> allForbidden.put(k, 403));
        assertThat(codes).isEqualTo(allForbidden);
    }

    /** 本来就绿：管理员照样能读（防绿灯时把管理员也挡了） */
    @Test
    void admin_canReadAdminData() throws Exception {
        String admin = adminBearer(newUser());
        mvc.perform(get("/api/orders").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/warehouses").header("Authorization", admin)).andExpect(status().isOk());
        mvc.perform(get("/api/warehouses/audit-logs/out-transactions").header("Authorization", admin))
                .andExpect(status().isOk());
    }

    // ---------- B2：改价格、改库存、建商品、删仓库 ----------

    @Test
    void normalUser_cannotChangeAPrice() throws Exception {
        PlacedOrder order = placeOrder();
        Long productId = productIdOf(order);

        mvc.perform(put("/api/products/{id}", productId)
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Cheap Phone\",\"price\":1.00,\"description\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(productRepository.findById(productId).orElseThrow().getPrice())
                .isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void normalUser_cannotChangeStock() throws Exception {
        PlacedOrder order = placeOrder();
        int before = stockOf(order);

        mvc.perform(put("/api/warehouses/products/quantity")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"updates\":[{\"warehouseId\":" + warehouseIdOf(order) + ",\"productId\":"
                                + productIdOf(order) + ",\"newQuantity\":0}]}"))
                .andExpect(status().isForbidden());

        assertThat(stockOf(order)).isEqualTo(before);
    }

    @Test
    void normalUser_cannotCreateAProduct() throws Exception {
        String name = "Fake Product " + UUID.randomUUID().toString().substring(0, 8);

        mvc.perform(post("/api/products")
                        .header("Authorization", bearer(newUser()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"price\":1.00,\"description\":\"x\"}"))
                .andExpect(status().isForbidden());

        assertThat(productRepository.findByNameContainingIgnoreCase(name)).isEmpty();
    }

    @Test
    void normalUser_cannotDeleteAWarehouse() throws Exception {
        Warehouse warehouse = new Warehouse();
        warehouse.setName("Empty Warehouse " + UUID.randomUUID().toString().substring(0, 8));
        warehouse.setLocation("Nowhere");
        warehouse = warehouseRepository.save(warehouse);

        mvc.perform(delete("/api/warehouses/{id}", warehouse.getId())
                        .header("Authorization", bearer(newUser())))
                .andExpect(status().isForbidden());

        assertThat(warehouseRepository.findById(warehouse.getId())).isPresent();
    }

    // ---------- A3、决定 4：退款只给管理员 ----------

    /** 红：订单主人给已付款（这里也可以是已送达）的单退款，真的进了 REFUND_PENDING */
    @Test
    void owner_cannotRequestARefund() throws Exception {
        PlacedOrder order = placePaidOrder();

        mvc.perform(post("/api/payments/{orderId}/refund", order.orderId())
                        .header("Authorization", bearer(ownerOf(order))))
                .andExpect(status().isForbidden());

        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.SUCCESS);
        assertThat(openTasksOf(order, "REFUND")).isEmpty();
    }

    /** 红：管理员给别人的单退款，现在被 PR ② 的归属检查挡成 404 */
    @Test
    void admin_canRefundAnyOrder() throws Exception {
        PlacedOrder order = placePaidOrder();

        mvc.perform(post("/api/payments/{orderId}/refund", order.orderId())
                        .header("Authorization", adminBearer(newUser())))
                .andExpect(status().isOk());

        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
    }

    // ---------- A13、A14：老 token、自己写的 role ----------

    /** 角色上线前签发的 token（没有 role）：普通功能照用，管理接口 403，不能当成管理员，也不能 500 */
    @Test
    void legacyTokenWithoutRole_isAnOrdinaryUser() throws Exception {
        PlacedOrder order = placeOrder();
        Account owner = ownerOf(order);

        mvc.perform(get("/api/store/orders/user/{userId}/with-payment", owner.getId())
                        .header("Authorization", legacyBearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders").header("Authorization", legacyBearer(owner)))
                .andExpect(status().isForbidden());
    }

    /** 本来就绿：自己往 token 里写 role = ADMIN，签名对不上 → 当成没登录 */
    @Test
    void selfMadeAdminToken_isNotLoggedIn() throws Exception {
        mvc.perform(get("/api/orders").header("Authorization", forgedAdminBearer(newUser())))
                .andExpect(status().isUnauthorized());
    }

    // ---------- B6：别的服务和前端怎么知道角色 ----------

    /** delivery 靠 validate-token 回的 role 判断 DLQ 接口 */
    @Test
    void validateToken_tellsTheRole() throws Exception {
        Account user = newUser();
        Account admin = newUser();

        mvc.perform(validate(adminBearer(admin))).andExpect(jsonPath("$.role").value("ADMIN"));
        mvc.perform(validate(bearer(user))).andExpect(jsonPath("$.role").value("USER"));
        mvc.perform(validate(legacyBearer(user))).andExpect(jsonPath("$.role").value("USER"));
    }

    /** 前端登录后要知道角色，才能决定显不显示管理页；登录签的 token 里也要带上 */
    @Test
    void login_returnsTheRole_andTheTokenCarriesIt() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account user = new Account();
        user.setEmail("login-" + tag + "@test.local");
        user.setUsername("login-" + tag);
        user.setPassword(passwordEncoder.encode("Passw0rd!"));
        user.setEmailVerified(true);
        user.setActive(true);
        accountRepository.save(user);

        String body = mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + user.getEmail() + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("USER"))
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");

        mvc.perform(validate("Bearer " + token)).andExpect(jsonPath("$.role").value("USER"));
    }

    /** 47 号 🟡6：库里是 ADMIN → 登录 → 回复和 token 都是 ADMIN → 能用管理接口。本来就绿，防有人把登录写死成 USER */
    @Test
    void adminLogin_returnsAdmin_andTheTokenOpensAdminEndpoints() throws Exception {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account admin = new Account();
        admin.setEmail("real-admin-" + tag + "@test.local");
        admin.setUsername("real-admin-" + tag);
        admin.setPassword(passwordEncoder.encode("Passw0rd!"));
        admin.setEmailVerified(true);
        admin.setActive(true);
        admin.setRole(comp5348.storeservice.model.Role.ADMIN);
        accountRepository.save(admin);

        String body = mvc.perform(post("/api/user/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + admin.getEmail() + "\",\"password\":\"Passw0rd!\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.role").value("ADMIN"))
                .andReturn().getResponse().getContentAsString();
        String token = com.jayway.jsonpath.JsonPath.read(body, "$.token");

        mvc.perform(get("/api/orders").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
    }

    /**
     * 47 号 🟡8：付款还没成功（这里是 PENDING）时，PaymentService#requestRefund 什么都不做，接口却回 200 "Refund requested"。
     * 管理员以为退了，之后扣款成功，钱扣了、没有退款任务。红：200。期望 409，说清楚付款现在是什么状态。
     */
    @Test
    void adminRefund_withNothingToRefund_saysSo() throws Exception {
        PlacedOrder order = placeOrder();

        mvc.perform(post("/api/payments/{orderId}/refund", order.orderId())
                        .header("Authorization", adminBearer(newUser())))
                .andExpect(status().isConflict());

        assertThat(openTasksOf(order, "REFUND")).isEmpty();
    }

    // ---------- 帮手 ----------

    private MockHttpServletRequestBuilder validate(String bearer) {
        String token = bearer.substring("Bearer ".length());
        return post("/api/user/validate-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}");
    }

    private Long warehouseIdOf(PlacedOrder order) {
        return jdbc.queryForObject("SELECT warehouse_id FROM warehouse_products WHERE id = ?", Long.class,
                order.warehouseProductId());
    }

    private int stockOf(PlacedOrder order) {
        return warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity();
    }
}
