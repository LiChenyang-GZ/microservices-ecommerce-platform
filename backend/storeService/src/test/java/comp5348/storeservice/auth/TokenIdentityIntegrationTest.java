package comp5348.storeservice.auth;

import comp5348.storeservice.support.StoreHttpTest;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;

import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 按令牌里的 userId 认人（Obsidian 82 号，I1–I5；75 号 🟡3）。
 *
 * 以前：JwtAuthenticationFilter 只取令牌的 subject（签令牌那一刻的邮箱），OrderProductService#accountOf 按它 findByEmail。
 *   邮箱只是账号的一个属性、而且是快照：没迁移的大小写混杂老数据里，一个人的令牌会被解析成另一个账号，或者谁都不是。
 * 改完（关口 0 方案 A）：过滤器取出 userId（必须是整数，没有就当无效令牌），store 按 id 找账号；邮箱不再用来认人。
 *
 * 【层】集成测试：MockMvc + 安全链，真的 Postgres。令牌用 store 自己的密钥签（和登录接口签的一样），只是故意让
 *   userId 和 subject 指向不同的账号、或者缺 userId，模拟"快照过时"——这种令牌只有拿着密钥的 store 自己签得出来。
 * 【红灯为什么红】store 按 subject 认人：I1 认成 B、I2 找不到人、I3 / I4 / I5 照样放行。不是编译错。
 */
class TokenIdentityIntegrationTest extends StoreHttpTest {

    @Value("${jwt.secret}")
    private String secret;

    /** store 的密钥签的令牌：userId 和 subject 可以各指各的；userId 为 null 就不放这个 claim */
    private String token(Object userId, String subject) {
        var builder = Jwts.builder()
                .subject(subject)
                .claim("role", "USER")
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000));
        if (userId != null) {
            builder.claim("userId", userId);
        }
        return "Bearer " + builder.signWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8))).compact();
    }

    /** I1：userId = A、subject = B 的邮箱 → 按 A 认人：A 的单能看，B 的单 404。现在：反过来。 */
    @Test
    void theUserIdDecides_notTheEmailInTheToken() throws Exception {
        PlacedOrder a = placeOrder();
        PlacedOrder b = placeOrder();
        String mixed = token(buyerIdOf(a), b.buyerEmail());

        mvc.perform(get("/api/store/orders/{id}/with-payment", a.orderId()).header("Authorization", mixed))
                .andExpect(status().isOk());
        mvc.perform(get("/api/store/orders/{id}/with-payment", b.orderId()).header("Authorization", mixed))
                .andExpect(status().isNotFound());
    }

    /** I1：同一种令牌下单，订单记在 userId 那个人名下。现在：记在 subject 那个人名下。 */
    @Test
    void anOrderIsPlacedForTheUserId() throws Exception {
        PlacedOrder a = placeOrder();
        PlacedOrder b = placeOrder();
        String body = "{\"orderItems\":[{\"productId\":" + productIdOf(a) + ",\"quantity\":1}]}";

        mvc.perform(post("/api/store/orders/create-with-payment")
                        .header("Authorization", token(buyerIdOf(a), b.buyerEmail()))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.order.userId").value(buyerIdOf(a)));
    }

    /** I2：subject 是库里已经没有的邮箱（老写法、快照过时），userId 对 → 照样能看自己的单。现在：找不到人，404。 */
    @Test
    void anOutdatedEmailInTheToken_doesNotMatter() throws Exception {
        PlacedOrder a = placeOrder();

        mvc.perform(get("/api/store/orders/{id}/with-payment", a.orderId())
                        .header("Authorization", token(buyerIdOf(a), "Gone-Spelling@Test.Local")))
                .andExpect(status().isOk());
    }

    /** I3：没有 userId 的令牌（store 签出去的都有）→ 当无效令牌，401。现在：按 subject 放行，200。 */
    @Test
    void aTokenWithoutAUserId_isRejected() throws Exception {
        PlacedOrder a = placeOrder();

        mvc.perform(get("/api/store/orders/{id}/with-payment", a.orderId())
                        .header("Authorization", token(null, a.buyerEmail())))
                .andExpect(status().isUnauthorized());
    }

    /** I4：userId 是字符串、小数，或者 5.0（值是整数，但 JSON 里是小数，Jackson 读成 Double：审查 83 号 🟡4）→ 401。现在：200。 */
    @Test
    void aUserIdThatIsNotAWholeNumber_isRejected() throws Exception {
        PlacedOrder a = placeOrder();

        for (Object bad : new Object[]{String.valueOf(buyerIdOf(a)), buyerIdOf(a) + 0.5, buyerIdOf(a).doubleValue()}) {
            mvc.perform(get("/api/store/orders/{id}/with-payment", a.orderId())
                            .header("Authorization", token(bad, a.buyerEmail())))
                    .andExpect(status().isUnauthorized());
        }
    }

    /**
     * I5：userId 指向不存在的账号（删掉了），subject 是 A 的邮箱 → 不能认成 A：和"账号没了"一样，查单 404。
     * 现在：按 subject 认成 A，200。
     */
    @Test
    void aUserIdWithNoAccount_isNobody_evenIfTheEmailMatchesSomeone() throws Exception {
        PlacedOrder a = placeOrder();
        Long missing = jdbc.queryForObject("SELECT coalesce(max(id), 0) + 1000 FROM account", Long.class);

        mvc.perform(get("/api/store/orders/{id}/with-payment", a.orderId())
                        .header("Authorization", token(missing, a.buyerEmail())))
                .andExpect(status().isNotFound());
    }

    /**
     * I3 的另一个入口（审查 83 号 🟡1）：delivery、tracking 用的 validate-token 对"没有 userId 的令牌"和 store 自己一样，
     * 401、valid = false。现在：200、valid = true、userId = null（tracking 的 Go 端读成 0）。
     */
    @Test
    void validateToken_aTokenWithoutAUserId_isInvalidThereToo() throws Exception {
        PlacedOrder a = placeOrder();
        String raw = token(null, a.buyerEmail()).substring("Bearer ".length());

        mvc.perform(post("/api/user/validate-token").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + raw + "\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.valid").value(false));
    }

    /**
     * I1 打到另外三条路（审查 83 号 🟡2）：同一个混合令牌（userId = A、subject = B），我的订单、Pay Now、取消都按 A 认人。
     * 回归保护，现在就该绿：老测试的令牌 id 和邮箱是同一个账号，某一条路改回按邮箱认人时它们不会红。
     */
    @Test
    void theUserIdDecides_forMyOrdersPayNowAndCancel() throws Exception {
        PlacedOrder a = placeOrder();
        PlacedOrder b = placeOrder();
        String mixed = token(buyerIdOf(a), b.buyerEmail());

        mvc.perform(get("/api/store/orders/user/{id}/with-payment", buyerIdOf(a)).header("Authorization", mixed))
                .andExpect(status().isOk());
        mvc.perform(get("/api/store/orders/user/{id}/with-payment", buyerIdOf(b)).header("Authorization", mixed))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/store/orders/{id}/retry-payment", b.orderId()).header("Authorization", mixed))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/store/orders/{id}/cancel", b.orderId()).header("Authorization", mixed))
                .andExpect(status().isNotFound());

        assertThat(orderOf(b).getStatus()).as("B 的订单没被取消").isNotEqualTo(comp5348.storeservice.model.OrderStatus.CANCELLED);
    }
}
