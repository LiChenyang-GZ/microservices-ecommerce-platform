package comp5348.storeservice.support;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.utils.JwtUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;

/**
 * 鉴权 PR ②：要从 HTTP 这一层测"谁能调哪个接口"的测试的底座。
 *
 * 【和 WebhookRemovedTest 的区别】那个测试的 MockMvc 没挂 Spring Security 的过滤器链，
 *   请求不经过 SecurityConfig、JwtAuthenticationFilter，测不出"要不要登录"。
 *   这里把 springSecurityFilterChain 挂上，请求走的路和真的 Tomcat 一样：先过安全链，再到 controller。
 * 【token 从哪来】和登录接口一样用 JwtUtil#generateToken 签；过期的、签名不对的自己拼（JwtUtil 不会签这种）。
 */
public abstract class StoreHttpTest extends StoreIntegrationTest {

    @Autowired private WebApplicationContext web;
    @Autowired private JwtUtil jwtUtil;

    @Value("${jwt.secret}")
    private String jwtSecret;

    protected MockMvc mvc;

    @BeforeEach
    void buildMockMvcWithSecurity() {
        Filter securityChain = web.getBean("springSecurityFilterChain", Filter.class);
        mvc = MockMvcBuilders.webAppContextSetup(web).addFilters(securityChain).build();
    }

    /** 新建一个普通用户（已激活、有银行账号），和 placeOrder() 里的买家一样，只是没有订单 */
    protected Account newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account user = new Account();
        user.setEmail("other-" + tag + "@test.local");
        user.setUsername("other-" + tag);
        user.setPassword("not-used");
        user.setBankAccountNumber("ACC-OTHER-" + tag);
        return accountRepository.save(user);
    }

    /** 这一单的买家 */
    protected Account ownerOf(PlacedOrder order) {
        return accountRepository.findByEmail(order.buyerEmail()).orElseThrow();
    }

    /** "Bearer <token>"：和登录接口签的一样（JwtUtil#generateToken），有效期 24 小时 */
    protected String bearer(Account account) {
        return "Bearer " + jwtUtil.generateToken(account.getId(), account.getEmail());
    }

    /** 同一个用户、同一把密钥签的，但一小时前就过期了 */
    protected String expiredBearer(Account account) {
        long now = System.currentTimeMillis();
        String token = Jwts.builder()
                .claim("userId", account.getId())
                .subject(account.getEmail())
                .issuedAt(new Date(now - 7_200_000))
                .expiration(new Date(now - 3_600_000))
                .signWith(Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8)))
                .compact();
        return "Bearer " + token;
    }

    /** 内容是这个用户、没过期，但用别的密钥签的：攻击者自己造的 token */
    protected String forgedBearer(Account account) {
        String token = Jwts.builder()
                .claim("userId", account.getId())
                .subject(account.getEmail())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + 3_600_000))
                .signWith(Keys.hmacShaKeyFor(
                        "not-the-store-secret-not-the-store-secret-0123".getBytes(StandardCharsets.UTF_8)))
                .compact();
        return "Bearer " + token;
    }

    /** 这一单商品的 id（订单上的 product 是懒加载，直接读列） */
    protected Long productIdOf(PlacedOrder order) {
        return jdbc.queryForObject("SELECT product_id FROM orders WHERE id = ?", Long.class, order.orderId());
    }

    protected int ordersOfUser(Long userId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM orders WHERE user_id = ?", Integer.class, userId);
        return n == null ? 0 : n;
    }
}
