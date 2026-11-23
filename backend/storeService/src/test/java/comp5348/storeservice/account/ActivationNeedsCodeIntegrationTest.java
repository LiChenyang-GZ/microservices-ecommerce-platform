package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.support.StoreHttpTest;
import com.github.tomakehurst.wiremock.client.WireMock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A11：激活账号要证明收到过验证码。失败场景表（Obsidian 50 号文件）V1–V8。
 *
 * 以前浏览器先调 email 的 verify-code，再调 store 的 activate（只带邮箱）——store 看不到第一步，
 * 所以谁都能直接 POST /api/user/activate {"email":"别人的邮箱"} 激活别人邮箱注册的账号。
 * 改完：activate 收 {email, code}，store 自己带服务密钥去问 email 的 verify-code（和找回密码一个做法）。
 *
 * 【层】集成测试：MockMvc + 安全链（activate 是公开接口，不带登录 token），真的 Postgres；
 *   假 email 是 WireMock（和假 bank 同一个，StoreIntegrationTest 里 email.service.url 指向它）。
 * 【红灯为什么红】AccountService#activateAccount 只看邮箱：不调 email，不带码也激活；
 *   email 说码不对、说密钥不对、挂了，都照样激活。不是编译错。
 * 【约定】email 回 200 {"success":true} = 码对；回 400 = 码不对或过期；回 401 = store 的密钥配错。
 *   码不对 → 400；email 那边出问题（401、挂了、超时）→ 503，不是用户的错。
 */
@ExtendWith(OutputCaptureExtension.class)
class ActivationNeedsCodeIntegrationTest extends StoreHttpTest {

    private static final String VERIFY = "/api/email/verify-code";

    /** 一个注册了还没激活的账号。bank 账号先填上，激活时就不会去 bank 开户（这里不测开户） */
    private Account newInactiveAccount() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account account = new Account();
        account.setEmail("activate-" + tag + "@test.local");
        account.setUsername("activate-" + tag);
        account.setPassword("hash");
        account.setEmailVerified(false);
        account.setActive(false);
        account.setBankAccountNumber("ACC-" + tag);
        return accountRepository.save(account);
    }

    private ResultActions activate(String json) throws Exception {
        return mvc.perform(post("/api/user/activate").contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private static String body(String email, String code) {
        return code == null
                ? "{\"email\":\"" + email + "\"}"
                : "{\"email\":\"" + email + "\",\"code\":\"" + code + "\"}";
    }

    private Account reload(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow();
    }

    private void assertStillInactive(Account account) {
        Account now = reload(account);
        assertThat(now.getActive()).as("账号没被激活").isFalse();
        assertThat(now.getEmailVerified()).as("邮箱没被标成验证过").isFalse();
    }

    private static void emailSaysCodeIsRight() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY)).willReturn(okJson("{\"success\":true,\"message\":\"ok\"}")));
    }

    // ---------- V1：不带码 ----------

    /** V1：只带邮箱（以前的请求体）→ 400，账号不变，store 根本不去问 email。现在：200，激活了。 */
    @Test
    void withoutCode_is400_andNothingChanges() throws Exception {
        Account account = newInactiveAccount();
        emailSaysCodeIsRight(); // 就算 email 什么码都说对，不带码也不行

        activate(body(account.getEmail(), null)).andExpect(status().isBadRequest());

        assertStillInactive(account);
        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(VERIFY)));
    }

    /** V1：码是空字符串 → 同上 */
    @Test
    void blankCode_is400_andNothingChanges() throws Exception {
        Account account = newInactiveAccount();
        emailSaysCodeIsRight();

        activate(body(account.getEmail(), "  ")).andExpect(status().isBadRequest());

        assertStillInactive(account);
        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(VERIFY)));
    }

    // ---------- V2、V3：email 说码错 / 码对 ----------

    /** V2：email 说码不对（400）→ store 回 400，账号不变。现在：200，激活了。 */
    @Test
    void wrongCode_is400_andNothingChanges() throws Exception {
        Account account = newInactiveAccount();
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"message\":\"Verification code is invalid or expired\"}")));

        activate(body(account.getEmail(), "000000")).andExpect(status().isBadRequest());

        assertStillInactive(account);
    }

    /**
     * V3 + V4：email 说码对 → 200，账号激活。store 发给 email 的是这个邮箱和这个码，带着 email 的服务密钥
     * （verify-code 以后只给 store，不带就 401）。现在：200 是有的，但 store 根本没去问 email。
     */
    @Test
    void rightCode_activates_andStoreAsksEmailWithServiceToken() throws Exception {
        Account account = newInactiveAccount();
        emailSaysCodeIsRight();

        activate(body(account.getEmail(), "123456")).andExpect(status().isOk());

        Account now = reload(account);
        assertThat(now.getActive()).isTrue();
        assertThat(now.getEmailVerified()).isTrue();
        FAKE_BANK.verify(1, postRequestedFor(urlEqualTo(VERIFY))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(EMAIL_SERVICE_TOKEN))
                .withRequestBody(matchingJsonPath("$.email", equalTo(account.getEmail())))
                .withRequestBody(matchingJsonPath("$.code", equalTo("123456"))));
    }

    // ---------- V5、V6：email 那边出问题 ----------

    /** V5：email 回 401（store 的密钥配错了）→ 不激活，打 ALERT，回 503。现在：200，激活了。 */
    @Test
    void emailRefusesStoresToken_isNotActivated_alerts_and503(CapturedOutput output) throws Exception {
        Account account = newInactiveAccount();
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY)).willReturn(aResponse().withStatus(401)));

        activate(body(account.getEmail(), "123456")).andExpect(status().isServiceUnavailable());

        assertStillInactive(account);
        assertThat(output.getAll()).contains("ALERT email_auth_failed");
    }

    /** V6：email 挂了（连接被掐断）→ 不激活，503。现在：200，激活了。 */
    @Test
    void emailDown_isNotActivated_and503() throws Exception {
        Account account = newInactiveAccount();
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY)).willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        activate(body(account.getEmail(), "123456")).andExpect(status().isServiceUnavailable());

        assertStillInactive(account);
    }

    /** V6：email 一直不回 → store 5 秒放弃（沿用找回密码的超时），不激活，503。测试给 10 秒。 */
    @Test
    void emailHangs_givesUpWithinTimeout_isNotActivated() throws Exception {
        Account account = newInactiveAccount();
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(VERIFY))
                .willReturn(okJson("{\"success\":true,\"message\":\"ok\"}").withFixedDelay(15_000)));

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> activate(body(account.getEmail(), "123456")).andExpect(status().isServiceUnavailable()));

        assertStillInactive(account);
    }

    // ---------- V7、V8：没有这个账号 / 已经激活 ----------

    /** V7：邮箱没注册过 → 400，不去调 email（不替别人白白耗掉一个码）。现在：400，但这条写下来防绿灯时先调 email。 */
    @Test
    void unknownEmail_is400_andEmailIsNotAsked() throws Exception {
        emailSaysCodeIsRight();

        activate(body("nobody-" + UUID.randomUUID() + "@test.local", "123456")).andExpect(status().isBadRequest());

        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(VERIFY)));
    }

    /** V8：已经激活过 → 200，什么都不变，不去调 email（重复点、刷新页面不该耗掉码）。现在：200，但这条防改坏。 */
    @Test
    void alreadyActive_is200_andEmailIsNotAsked() throws Exception {
        Account account = newInactiveAccount();
        account.setActive(true);
        account.setEmailVerified(true);
        accountRepository.save(account);
        emailSaysCodeIsRight();

        activate(body(account.getEmail(), "123456")).andExpect(status().isOk());

        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(VERIFY)));
        assertThat(reload(account).getActive()).isTrue();
    }
}
