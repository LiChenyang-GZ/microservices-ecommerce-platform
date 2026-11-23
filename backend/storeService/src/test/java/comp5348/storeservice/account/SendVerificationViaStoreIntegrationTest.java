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
 * 限流（Obsidian 54 号）R1–R5：注册验证码改成浏览器调 store，store 带服务密钥转给 email。
 *
 * 为什么挪：以前浏览器直接调 email 的 send-verification（公开），email 不知道有没有这个账号，谁都能让它给任意地址发信。
 * 挪到 store 以后：email 没有公开接口了；store 先看账号（存在且没激活才发），限流也只写在 store 一处。
 *
 * 【层】集成测试：MockMvc + 安全链（公开接口，不带登录 token），真的 Postgres、Redis；假 email 是 WireMock。
 * 【红灯为什么红】store 还没有 POST /api/user/send-verification：不在 SecurityConfig 的公开名单里 → 401。不是编译错。
 * 【约定】email 的 send-verification 回 200 {"success":true} = 发出去了；400 = 它发信失败；401 = store 的密钥配错。
 *   只有"发出去了"是 200；email 那边任何问题都是 503（不是用户的错，A11 的经验）。
 */
@ExtendWith(OutputCaptureExtension.class)
class SendVerificationViaStoreIntegrationTest extends StoreHttpTest {

    private static final String EMAIL_SEND = "/api/email/send-verification";

    private Account newAccount(boolean active) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account account = new Account();
        account.setEmail("send-" + tag + "@test.local");
        account.setUsername("send-" + tag);
        account.setPassword("hash");
        account.setEmailVerified(active);
        account.setActive(active);
        account.setBankAccountNumber("ACC-" + tag);
        return accountRepository.save(account);
    }

    private ResultActions sendVerification(String email) throws Exception {
        return mvc.perform(post("/api/user/send-verification").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\"}"));
    }

    private static void emailSends() {
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND))
                .willReturn(okJson("{\"success\":true,\"message\":\"sent\"}")));
    }

    /** R1：没激活的账号 → 200，store 带 email 的服务密钥调 send-verification，body 里是这个邮箱。现在：401（没这个接口）。 */
    @Test
    void inactiveAccount_is200_andStoreAsksEmailToSendWithServiceToken() throws Exception {
        Account account = newAccount(false);
        emailSends();

        sendVerification(account.getEmail()).andExpect(status().isOk());

        FAKE_BANK.verify(1, postRequestedFor(urlEqualTo(EMAIL_SEND))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(EMAIL_SERVICE_TOKEN))
                .withRequestBody(matchingJsonPath("$.email", equalTo(account.getEmail()))));
    }

    /** R2：邮箱没注册过 → 200（和发了一样的回答），但不调 email：不给没注册的地址发信。现在：401。 */
    @Test
    void unknownEmail_is200_andEmailIsNotAsked() throws Exception {
        emailSends();

        sendVerification("nobody-" + UUID.randomUUID() + "@test.local").andExpect(status().isOk());

        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(EMAIL_SEND)));
    }

    /** R3：账号已经激活 → 200，不调 email。现在：401。 */
    @Test
    void activeAccount_is200_andEmailIsNotAsked() throws Exception {
        Account account = newAccount(true);
        emailSends();

        sendVerification(account.getEmail()).andExpect(status().isOk());

        FAKE_BANK.verify(0, postRequestedFor(urlEqualTo(EMAIL_SEND)));
    }

    /** R4：email 回 401（store 的密钥配错）→ 503 + ALERT。现在：401（没这个接口）。 */
    @Test
    void emailRefusesStoresToken_is503_andAlerts(CapturedOutput output) throws Exception {
        Account account = newAccount(false);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND)).willReturn(aResponse().withStatus(401)));

        sendVerification(account.getEmail()).andExpect(status().isServiceUnavailable());

        assertThat(output.getAll()).contains("ALERT email_auth_failed");
    }

    /** R5：email 发信失败（它回 400 "Sending failed"）→ 503：SMTP 坏了不是用户的错。现在：401。 */
    @Test
    void emailFailsToSend_is503() throws Exception {
        Account account = newAccount(false);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"success\":false,\"message\":\"Sending failed: smtp down\"}")));

        sendVerification(account.getEmail()).andExpect(status().isServiceUnavailable());
    }

    /** R5：email 回 5xx → 503。现在：401。 */
    @Test
    void emailAnswers500_is503() throws Exception {
        Account account = newAccount(false);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND)).willReturn(aResponse().withStatus(500)));

        sendVerification(account.getEmail()).andExpect(status().isServiceUnavailable());
    }

    /** R5：email 挂了（连接被掐断）→ 503。现在：401。 */
    @Test
    void emailDown_is503() throws Exception {
        Account account = newAccount(false);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND)).willReturn(aResponse()
                .withFault(com.github.tomakehurst.wiremock.http.Fault.CONNECTION_RESET_BY_PEER)));

        sendVerification(account.getEmail()).andExpect(status().isServiceUnavailable());
    }

    /** R5：email 一直不回 → store 5 秒放弃（和激活、找回密码同一个超时），503。测试给 10 秒。现在：401（很快，但不是 503）。 */
    @Test
    void emailHangs_givesUpWithinTimeout_and503() {
        Account account = newAccount(false);
        FAKE_BANK.stubFor(WireMock.post(urlEqualTo(EMAIL_SEND))
                .willReturn(okJson("{\"success\":true,\"message\":\"sent\"}").withFixedDelay(15_000)));

        assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> sendVerification(account.getEmail()).andExpect(status().isServiceUnavailable()));
    }
}
