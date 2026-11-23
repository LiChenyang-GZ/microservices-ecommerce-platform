package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.util.Set;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 限流（Obsidian 54 号）R6–R18：store 的 5 个公开接口按 IP、按邮箱（登录按 IP + 账号）限速，计数存在 Redis。
 *
 * 规则（54 号"具体规则"）：
 *   send-verification、forgot-password：同一邮箱 15 分钟 3 次；同一 IP 1 小时 20 次
 *   activate、reset-password：同一 IP 15 分钟 20 次
 *   login：同一 IP + 账号 15 分钟 10 次（登录成功清零）；同一 IP 15 分钟 100 次
 *   超了：429 + Retry-After（秒）+ {"success":false,"message":"Too many attempts, ..."}
 *
 * 【层】集成测试：MockMvc + 安全链，真的 Postgres、真的 Redis（每个测试前清空），假 email 是 WireMock。
 * 【IP 从哪来】X-Forwarded-For 的最后一个值（入口加的）；没有就用连接地址。测试用 setRemoteAddr 模拟不同的连接。
 * 【红灯为什么红】现在没有任何限流：第 N+1 次照样 200 / 400 / 401，不是 429；send-verification 还不在 store。
 */
class AuthRateLimitIntegrationTest extends StoreHttpTest {

    private static final String EMAIL_SEND = "/api/email/send-verification";
    private static final String EMAIL_SEND_RESET = "/api/email/send-password-reset";
    private static final String EMAIL_VERIFY = "/api/email/verify-code";
    private static final String EMAIL_VERIFY_RESET = "/api/email/verify-password-reset";
    private static final String RIGHT_PASSWORD = "right-password";

    // ---------- 造数据、发请求 ----------

    private Account newAccount(boolean active) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account account = new Account();
        account.setEmail("limit-" + tag + "@test.local");
        account.setUsername("limit-" + tag);
        account.setPassword(new BCryptPasswordEncoder().encode(RIGHT_PASSWORD));
        account.setEmailVerified(active);
        account.setActive(active);
        account.setBankAccountNumber("ACC-" + tag);
        return accountRepository.save(account);
    }

    private static String unknownEmail() {
        return "nobody-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
    }

    /** 从这个地址连过来（没有入口加的 X-Forwarded-For 时，store 用的就是它） */
    private static RequestPostProcessor from(String ip) {
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    private static MockHttpServletRequestBuilder json(String path, String body) {
        return post(path).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private ResultActions sendVerification(String ip, String email) throws Exception {
        return mvc.perform(json("/api/user/send-verification", "{\"email\":\"" + email + "\"}").with(from(ip)));
    }

    private ResultActions forgotPassword(String ip, String email) throws Exception {
        return mvc.perform(json("/api/user/forgot-password", "{\"email\":\"" + email + "\"}").with(from(ip)));
    }

    private ResultActions activate(String ip, String email) throws Exception {
        return mvc.perform(json("/api/user/activate",
                "{\"email\":\"" + email + "\",\"code\":\"000000\"}").with(from(ip)));
    }

    private ResultActions resetPassword(String ip, String email) throws Exception {
        return mvc.perform(json("/api/user/reset-password",
                "{\"email\":\"" + email + "\",\"code\":\"000000\",\"newPassword\":\"new-password\"}").with(from(ip)));
    }

    private ResultActions login(String ip, String identifier, String password) throws Exception {
        return mvc.perform(json("/api/user/login",
                "{\"email\":\"" + identifier + "\",\"password\":\"" + password + "\"}").with(from(ip)));
    }

    private static void emailAcceptsEverything() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND)).willReturn(okJson("{\"success\":true,\"message\":\"sent\"}")));
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND_RESET)).willReturn(okJson("{\"success\":true,\"message\":\"sent\"}")));
    }

    private static void emailSaysEveryCodeIsWrong() {
        String wrong = "{\"success\":false,\"message\":\"Verification code is invalid or expired\"}";
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_VERIFY)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(wrong)));
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_VERIFY_RESET)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json").withBody(wrong)));
    }

    /** R18：429 的样子。Retry-After 是 1..windowSeconds 的整数秒，body 说 success=false 并让人稍后再试 */
    private static void assertTooMany(ResultActions result, int windowSeconds) throws Exception {
        MockHttpServletResponse response = result.andExpect(status().isTooManyRequests()).andReturn().getResponse();
        String retryAfter = response.getHeader("Retry-After");
        assertThat(retryAfter).as("Retry-After 头").isNotNull();
        assertThat(Integer.parseInt(retryAfter)).isBetween(1, windowSeconds);
        assertThat(response.getContentAsString()).contains("\"success\":false").contains("try again");
    }

    // ---------- send-verification ----------

    /** R6 + R18：同一个邮箱 15 分钟第 4 次 → 429；email 只被调了 3 次。现在：store 没这个接口（401）。 */
    @Test
    void sendVerification_fourthForSameEmail_is429_andEmailSentOnlyThree() throws Exception {
        Account account = newAccount(false);
        emailAcceptsEverything();

        for (int i = 0; i < 3; i++) {
            sendVerification("10.0.6.1", account.getEmail()).andExpect(status().isOk());
        }
        assertTooMany(sendVerification("10.0.6.1", account.getEmail()), 15 * 60);

        FAKE_BANK.verify(3, postRequestedFor(urlEqualTo(EMAIL_SEND)));
    }

    /** R6：邮箱的额度不看 IP：换 IP 也是第 4 次。现在：401。 */
    @Test
    void sendVerification_sameEmailFromOtherIps_stillCountsTogether() throws Exception {
        Account account = newAccount(false);
        emailAcceptsEverything();

        for (int i = 0; i < 3; i++) {
            sendVerification("10.0.6." + (10 + i), account.getEmail()).andExpect(status().isOk());
        }
        assertTooMany(sendVerification("10.0.6.20", account.getEmail()), 15 * 60);
    }

    /** R7：大小写、前后空格不同也算同一个邮箱（信会进同一个信箱）。现在：401。 */
    @Test
    void sendVerification_caseAndSpacesDoNotGetAroundTheEmailLimit() throws Exception {
        Account account = newAccount(false);
        emailAcceptsEverything();

        for (int i = 0; i < 3; i++) {
            sendVerification("10.0.7.1", account.getEmail()).andExpect(status().isOk());
        }
        assertTooMany(sendVerification("10.0.7.1", "  " + account.getEmail().toUpperCase() + " "), 15 * 60);
    }

    /** R8：同一个 IP 一小时第 21 次（每次换邮箱）→ 429。现在：401。 */
    @Test
    void sendVerification_twentyFirstFromSameIp_is429() throws Exception {
        for (int i = 0; i < 20; i++) {
            sendVerification("10.0.8.1", unknownEmail()).andExpect(status().isOk());
        }
        assertTooMany(sendVerification("10.0.8.1", unknownEmail()), 60 * 60);
    }

    /**
     * R9：被 IP 挡下的请求不占邮箱的额度（先数 IP，过了才数邮箱）。
     * 攻击者的 IP 用完以后再替受害人要码，被挡；受害人从自己的 IP 照样能要 3 次。现在：401。
     */
    @Test
    void sendVerification_blockedByIp_doesNotUseTheEmailsQuota() throws Exception {
        Account victim = newAccount(false);
        emailAcceptsEverything();
        for (int i = 0; i < 20; i++) {
            sendVerification("10.0.9.1", unknownEmail()).andExpect(status().isOk());
        }
        sendVerification("10.0.9.1", victim.getEmail()).andExpect(status().isTooManyRequests());

        for (int i = 0; i < 3; i++) {
            sendVerification("10.0.9.2", victim.getEmail()).andExpect(status().isOk());
        }
        FAKE_BANK.verify(3, postRequestedFor(urlEqualTo(EMAIL_SEND)));
    }

    // ---------- forgot-password ----------

    /** R10：同一邮箱 15 分钟第 4 次 → 429，email 只发了 3 次；注册的额度不受影响。现在：第 4 次照样 200。 */
    @Test
    void forgotPassword_fourthForSameEmail_is429_andRegistrationQuotaIsSeparate() throws Exception {
        Account account = newAccount(false);
        emailAcceptsEverything();

        for (int i = 0; i < 3; i++) {
            forgotPassword("10.0.10.1", account.getEmail()).andExpect(status().isOk());
        }
        assertTooMany(forgotPassword("10.0.10.1", account.getEmail()), 15 * 60);
        FAKE_BANK.verify(3, postRequestedFor(urlEqualTo(EMAIL_SEND_RESET)));

        sendVerification("10.0.10.2", account.getEmail()).andExpect(status().isOk());
    }

    /** R10：同一 IP 一小时第 21 次 → 429。现在：没注册的邮箱照样 400。 */
    @Test
    void forgotPassword_twentyFirstFromSameIp_is429() throws Exception {
        for (int i = 0; i < 20; i++) {
            forgotPassword("10.0.10.9", unknownEmail()).andExpect(status().isBadRequest());
        }
        assertTooMany(forgotPassword("10.0.10.9", unknownEmail()), 60 * 60);
    }

    // ---------- activate、reset-password ----------

    /** R11：同一 IP 15 分钟第 21 次激活 → 429，而且不去调 email（不耗码）。现在：照样 400，email 被调 21 次。 */
    @Test
    void activate_twentyFirstFromSameIp_is429_andEmailIsNotAsked() throws Exception {
        Account account = newAccount(false);
        emailSaysEveryCodeIsWrong();

        for (int i = 0; i < 20; i++) {
            activate("10.0.11.1", account.getEmail()).andExpect(status().isBadRequest());
        }
        assertTooMany(activate("10.0.11.1", account.getEmail()), 15 * 60);

        FAKE_BANK.verify(20, postRequestedFor(urlEqualTo(EMAIL_VERIFY)));
    }

    /** R12：同一 IP 15 分钟第 21 次重置 → 429，不调 email。现在：照样 400。 */
    @Test
    void resetPassword_twentyFirstFromSameIp_is429_andEmailIsNotAsked() throws Exception {
        Account account = newAccount(true);
        emailSaysEveryCodeIsWrong();

        for (int i = 0; i < 20; i++) {
            resetPassword("10.0.12.1", account.getEmail()).andExpect(status().isBadRequest());
        }
        assertTooMany(resetPassword("10.0.12.1", account.getEmail()), 15 * 60);

        FAKE_BANK.verify(20, postRequestedFor(urlEqualTo(EMAIL_VERIFY_RESET)));
    }

    // ---------- login ----------

    /** R13：同一 IP + 账号连错 10 次，第 11 次密码对了也是 429（不然就等于告诉对方猜中了）。现在：200。 */
    @Test
    void login_eleventhForSameAccountAndIp_is429_evenWithTheRightPassword() throws Exception {
        Account account = newAccount(true);

        for (int i = 0; i < 10; i++) {
            login("10.0.13.1", account.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }
        assertTooMany(login("10.0.13.1", account.getEmail(), RIGHT_PASSWORD), 15 * 60);
    }

    /** R13：别的 IP 不受这个账号在那个 IP 上的计数影响（不能让别人把你锁在门外）。现在：200（这条现在就绿，防改成"只按账号"）。 */
    @Test
    void login_anotherIpCanStillLogIn() throws Exception {
        Account account = newAccount(true);
        for (int i = 0; i < 10; i++) {
            login("10.0.13.5", account.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }

        login("10.0.13.6", account.getEmail(), RIGHT_PASSWORD).andExpect(status().isOk());
    }

    /** R14：登录成功把这个 IP + 账号的计数清零：错 9 次、对 1 次，之后又能错 10 次才被挡。现在：没有 429。 */
    @Test
    void login_successClearsTheCount() throws Exception {
        Account account = newAccount(true);
        for (int i = 0; i < 9; i++) {
            login("10.0.14.1", account.getEmail(), "wrong-" + i).andExpect(status().isUnauthorized());
        }
        login("10.0.14.1", account.getEmail(), RIGHT_PASSWORD).andExpect(status().isOk());

        for (int i = 0; i < 10; i++) {
            login("10.0.14.1", account.getEmail(), "wrong-again-" + i).andExpect(status().isUnauthorized());
        }
        assertTooMany(login("10.0.14.1", account.getEmail(), "wrong-last"), 15 * 60);
    }

    /** R15：同一 IP 15 分钟第 101 次登录（每次换账号，撞库）→ 429。现在：401。 */
    @Test
    void login_hundredAndFirstFromSameIp_is429() throws Exception {
        for (int i = 0; i < 100; i++) {
            login("10.0.15.1", unknownEmail(), "whatever").andExpect(status().isUnauthorized());
        }
        assertTooMany(login("10.0.15.1", unknownEmail(), "whatever"), 15 * 60);
    }

    // ---------- 客户端 IP、键的过期时间 ----------

    /**
     * R16：IP 取 X-Forwarded-For 的最后一个值（我们的入口加的）。前面的值是客户端自己写的：随便换，还是同一个 IP。
     * 最后一个值不同就分开数；没有这个头就用连接地址（也分开数）。现在：401（没这个接口）。
     */
    @Test
    void clientIp_isTheLastForwardedForValue() throws Exception {
        for (int i = 0; i < 20; i++) {
            mvc.perform(json("/api/user/send-verification", "{\"email\":\"" + unknownEmail() + "\"}")
                            .header("X-Forwarded-For", "198.51.100." + i + ", 203.0.113.7")
                            .with(from("172.18.0.5")))
                    .andExpect(status().isOk());
        }
        // 客户端换了自己写的那一段，入口加的还是 203.0.113.7：照样挡
        mvc.perform(json("/api/user/send-verification", "{\"email\":\"" + unknownEmail() + "\"}")
                        .header("X-Forwarded-For", "192.0.2.99, 203.0.113.7").with(from("172.18.0.5")))
                .andExpect(status().isTooManyRequests());
        // 入口加的是另一个地址：另一个人
        mvc.perform(json("/api/user/send-verification", "{\"email\":\"" + unknownEmail() + "\"}")
                        .header("X-Forwarded-For", "203.0.113.8").with(from("172.18.0.5")))
                .andExpect(status().isOk());
        // 没有这个头：用连接地址 172.18.0.5，也是另一个计数
        sendVerification("172.18.0.5", unknownEmail()).andExpect(status().isOk());
    }

    /**
     * R17：每个计数的键都有过期时间，而且不超过最长的窗口（1 小时）。没有过期时间的键会让那个邮箱 / IP 永远被挡。
     * 现在：Redis 里什么键都没有（没人限流）。
     */
    @Test
    void everyCounterExpires_withinItsWindow() throws Exception {
        Account account = newAccount(false);
        emailAcceptsEverything();
        emailSaysEveryCodeIsWrong();

        sendVerification("10.0.17.1", account.getEmail());
        forgotPassword("10.0.17.1", account.getEmail());
        activate("10.0.17.1", account.getEmail());
        resetPassword("10.0.17.1", account.getEmail());
        login("10.0.17.1", account.getEmail(), "wrong");

        Set<String> keys = redis.keys("*");
        assertThat(keys).as("限流的计数在 Redis 里").isNotEmpty();
        for (String key : keys) {
            Long ttl = redis.getExpire(key);
            assertThat(ttl).as("键 %s 的剩余秒数", key).isNotNull().isBetween(1L, 3600L);
        }
    }
}
