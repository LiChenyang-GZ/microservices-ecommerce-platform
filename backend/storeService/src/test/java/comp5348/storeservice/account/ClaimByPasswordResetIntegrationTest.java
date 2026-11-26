package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 抢注（Obsidian 58 号，Q1–Q8）：别人先用你的邮箱注册了（密码他定），这个账号没激活，却一直占着你的邮箱。
 *
 * 以前：你再注册是 "Email already exists!"；找回密码能改密码，但改完还是没激活，登录一直是"请先验证邮箱"。
 *   没有任何一条路能用回自己的邮箱。
 * 改完（关口 0 方案 A）：找回密码就是认领。重置码只会进这个信箱，和注册码一样能证明你是主人，所以重置成功时
 *   没激活的账号顺带激活，密码是主人刚设的，抢注的人的密码作废。注册遇到"已存在但没激活"的邮箱，告诉用户怎么认领。
 *
 * 【层】集成测试：MockMvc + 安全链，真的 Postgres、Redis；假 email / bank 是 WireMock。
 * 【红灯为什么红】AccountService#resetPassword 只改密码、不动 active / emailVerified；createAccount 不区分
 *   "已存在且已激活"和"已存在但没激活"。不是编译错。
 * 【约定】email 的 verify-password-reset 回 200 {"success":true} = 重置码对；回 400 = 不对或过期。
 */
class ClaimByPasswordResetIntegrationTest extends StoreHttpTest {

    private static final String VERIFY_RESET = "/api/email/verify-password-reset";
    private static final String SQUATTER_PASSWORD = "squatter-password";
    private static final String OWNER_PASSWORD = "owner-password";
    private static final BCryptPasswordEncoder ENCODER = new BCryptPasswordEncoder();

    /** 抢注的人建的账号：没激活，密码是他的 */
    private Account squattedAccount(boolean withBankAccount) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account account = new Account();
        account.setEmail("victim-" + tag + "@test.local");
        account.setUsername("squatter-" + tag);
        account.setPassword(ENCODER.encode(SQUATTER_PASSWORD));
        account.setEmailVerified(false);
        account.setActive(false);
        if (withBankAccount) {
            account.setBankAccountNumber("ACC-" + tag);
        }
        return accountRepository.save(account);
    }

    private Account reload(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private ResultActions json(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private ResultActions resetPassword(String email, String newPassword) throws Exception {
        return json("/api/user/reset-password",
                "{\"email\":\"" + email + "\",\"code\":\"123456\",\"newPassword\":\"" + newPassword + "\"}");
    }

    private ResultActions login(String email, String password) throws Exception {
        return json("/api/user/login", "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}");
    }

    private ResultActions register(String username, String email) throws Exception {
        return json("/api/user",
                "{\"username\":\"" + username + "\",\"email\":\"" + email + "\",\"password\":\"" + OWNER_PASSWORD + "\"}");
    }

    private static void emailSaysResetCodeIsRight() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY_RESET)).willReturn(okJson("{\"success\":true,\"message\":\"ok\"}")));
    }

    // ---------- reset-password：认领 ----------

    /**
     * Q1–Q3：没激活的账号，重置码对 → 改成主人的密码，并且激活；主人用新密码能登录，抢注的人的密码登不上。
     * 现在：密码改了，但还是没激活，主人登录 401 "Please verify your email first"。
     */
    @Test
    void passwordReset_onAnUnverifiedAccount_claimsIt_withTheOwnersPassword() throws Exception {
        Account account = squattedAccount(true);
        emailSaysResetCodeIsRight();

        resetPassword(account.getEmail(), OWNER_PASSWORD).andExpect(status().isOk());

        Account now = reload(account);
        assertThat(now.getEmailVerified()).as("重置码证明了信箱是主人的：邮箱算验证过").isTrue();
        assertThat(now.getActive()).as("认领以后账号能用").isTrue();
        assertThat(now.getBankAccountNumber()).as("已有的银行账户不换").isEqualTo(account.getBankAccountNumber());

        login(account.getEmail(), OWNER_PASSWORD).andExpect(status().isOk());
        login(account.getEmail(), SQUATTER_PASSWORD).andExpect(status().isUnauthorized());
    }

    /** Q1：认领时还没有银行账户（注册时 bank 没连上），和激活一样补开一个。现在：没激活，也不开户。 */
    @Test
    void passwordReset_onAnUnverifiedAccountWithoutBankAccount_opensOne() throws Exception {
        Account account = squattedAccount(false);
        emailSaysResetCodeIsRight();
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo("/api/bank/account"))
                .willReturn(okJson("{\"accountNumber\":\"ACC-CLAIMED-1\"}")));

        resetPassword(account.getEmail(), OWNER_PASSWORD).andExpect(status().isOk());

        Account now = reload(account);
        assertThat(now.getActive()).isTrue();
        assertThat(now.getBankAccountNumber()).isEqualTo("ACC-CLAIMED-1");
    }

    /** Q4：已激活的账号重置密码：只改密码，状态不动（防改坏，现在就该绿） */
    @Test
    void passwordReset_onAnActiveAccount_onlyChangesThePassword() throws Exception {
        Account account = squattedAccount(true);
        account.setActive(true);
        account.setEmailVerified(true);
        accountRepository.save(account);
        emailSaysResetCodeIsRight();

        resetPassword(account.getEmail(), OWNER_PASSWORD).andExpect(status().isOk());

        Account now = reload(account);
        assertThat(now.getActive()).isTrue();
        assertThat(now.getEmailVerified()).isTrue();
        login(account.getEmail(), OWNER_PASSWORD).andExpect(status().isOk());
    }

    /** Q5：重置码不对 → 400，不改密码、不激活（防改坏，现在就该绿） */
    @Test
    void wrongResetCode_neitherChangesThePasswordNorActivates() throws Exception {
        Account account = squattedAccount(true);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY_RESET)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"message\":\"Verification code invalid or expired\"}")));

        resetPassword(account.getEmail(), OWNER_PASSWORD).andExpect(status().isBadRequest());

        Account now = reload(account);
        assertThat(now.getActive()).isFalse();
        assertThat(now.getEmailVerified()).isFalse();
        assertThat(ENCODER.matches(SQUATTER_PASSWORD, now.getPassword())).as("密码没变").isTrue();
    }

    // ---------- 注册：告诉用户怎么认领 ----------

    /**
     * Q7：邮箱已存在但没激活 → 400，说清楚"如果是你的邮箱，用找回密码认领"，并带 emailNotVerified=true 给前端放链接。
     * 现在：400 "Email already exists!"，没有这个标记。
     */
    @Test
    void register_withAnUnverifiedEmail_saysHowToClaimIt() throws Exception {
        Account account = squattedAccount(true);

        register("owner-" + UUID.randomUUID().toString().substring(0, 8), account.getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.emailNotVerified").value(true))
                .andExpect(jsonPath("$.message").value(containsString("reset the password")));
    }

    /**
     * 74 号 E6：抢注的人用 VICTIM-...@Test.Local 注册（没激活），主人再用小写注册 → 是同一个信箱：
     * 告诉主人去认领，不建第二个账号；主人用小写重置密码就认领了，抢注的人的密码作废。
     * 现在：大小写不同算两个邮箱，主人注册 200，抢注的账号一直留着。
     */
    @Test
    void squatterUsedCapitals_ownerRegistersInLowercase_isToldToClaim_andResetClaimsIt() throws Exception {
        String lower = "victim-" + UUID.randomUUID().toString().substring(0, 8) + "@test.local";
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo("/api/bank/account")).willReturn(okJson("{\"accountNumber\":\"ACC-SQ-1\"}")));
        json("/api/user", "{\"username\":\"squatter-" + UUID.randomUUID().toString().substring(0, 8)
                + "\",\"email\":\"" + lower.toUpperCase() + "\",\"password\":\"" + SQUATTER_PASSWORD + "\"}")
                .andExpect(status().isOk());
        // 只给抢注的人开户：红灯时主人的注册会建第二个账号，再拿到同一个账号号码就撞银行账号的唯一约束，红错地方
        FAKE_BANK.resetMappings();

        register("owner-" + UUID.randomUUID().toString().substring(0, 8), lower)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.emailNotVerified").value(true));
        assertThat(jdbcCount("SELECT count(*) FROM account WHERE lower(email) = ?", lower)).as("只有一个账号").isEqualTo(1);

        emailSaysResetCodeIsRight();
        resetPassword(lower, OWNER_PASSWORD).andExpect(status().isOk());
        login(lower, OWNER_PASSWORD).andExpect(status().isOk());
        login(lower, SQUATTER_PASSWORD).andExpect(status().isUnauthorized());
    }

    private int jdbcCount(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    /** Q8：邮箱已存在且已激活 → 还是 "Email already exists!"，不提认领（防改坏） */
    @Test
    void register_withAnActiveEmail_stillSaysItExists() throws Exception {
        Account account = squattedAccount(true);
        account.setActive(true);
        account.setEmailVerified(true);
        accountRepository.save(account);

        register("owner-" + UUID.randomUUID().toString().substring(0, 8), account.getEmail())
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Email already exists!"))
                .andExpect(jsonPath("$.message").value(not(containsString("reset"))));
    }
}
