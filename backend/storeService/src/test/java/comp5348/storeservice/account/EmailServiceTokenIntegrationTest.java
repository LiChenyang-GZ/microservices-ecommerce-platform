package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.service.AccountService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * store 调 email 的找回密码接口时带上服务凭据；email 回 401 时 store 怎么办。
 * 失败场景表（Obsidian 36 号文件）E1 的 store 这一半：email 开始检查以后，store 不带就全被拒，找回密码就坏了。
 *
 * store 只有这两处用 HTTP 调 email（AccountService#sendPasswordResetEmail、#resetPassword）；
 * 通知邮件走 RabbitMQ 的 email_queue，不在这里。
 *
 * 【层】集成测试：真的 store（Spring + 真 Postgres），假 email（WireMock，和假 bank 是同一个）。
 *   直接调 AccountService 的方法。
 * 【红灯为什么是红】不是编译错，是 store 少做了事：
 *   - AccountService 用 restTemplate.postForObject 直接发，不加头 → 假 email 收到的请求里没有 X-Service-Token。
 *   - 401 落进 catch (Exception)，只往 System.err 打一行 → 没有 ALERT。
 * 【约定】HTTP 头 X-Service-Token；store 的配置项 email.service-token。
 *   告警行：ALERT email_auth_failed（和 bank_auth_failed、delivery_auth_failed 一个风格）。
 */
@ExtendWith(OutputCaptureExtension.class) // 把控制台输出（日志）抓下来，检查有没有打出告警
class EmailServiceTokenIntegrationTest extends StoreIntegrationTest {

    private static final String SEND = "/api/email/send-password-reset";
    private static final String VERIFY = "/api/email/verify-password-reset";

    @Autowired AccountService accountService;

    /** 一个已注册的用户（找回密码先查邮箱存不存在，不存在就不会去调 email） */
    private Account newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account user = new Account();
        user.setEmail("reset-" + tag + "@test.local");
        user.setUsername("reset-" + tag);
        user.setPassword("old-password-hash");
        return accountRepository.save(user);
    }

    private String passwordOf(Account user) {
        return accountRepository.findById(user.getId()).orElseThrow().getPassword();
    }

    // ---------- store 带没带凭据 ----------

    /** 发找回密码验证码：请求带 X-Service-Token。现在：没带。 */
    @Test
    void sendPasswordResetEmail_carriesTheServiceToken() {
        Account user = newUser();
        FAKE_BANK.stubFor(post(urlEqualTo(SEND)).willReturn(okJson("{\"success\":true,\"message\":\"sent\"}")));

        boolean sent = accountService.sendPasswordResetEmail(user.getEmail());

        assertThat(sent).as("前提：store 调到了假 email 并读到成功").isTrue();
        FAKE_BANK.verify(postRequestedFor(urlEqualTo(SEND))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(EMAIL_SERVICE_TOKEN)));
    }

    /** 校验验证码并改密码：请求带 X-Service-Token。现在：没带。 */
    @Test
    void resetPassword_carriesTheServiceToken() {
        Account user = newUser();
        FAKE_BANK.stubFor(post(urlEqualTo(VERIFY)).willReturn(okJson("{\"success\":true,\"message\":\"ok\"}")));

        boolean reset = accountService.resetPassword(user.getEmail(), "123456", "new-password");

        assertThat(reset).as("前提：store 调到了假 email，密码改了").isTrue();
        FAKE_BANK.verify(postRequestedFor(urlEqualTo(VERIFY))
                .withHeader(SERVICE_TOKEN_HEADER, equalTo(EMAIL_SERVICE_TOKEN)));
    }

    // ---------- email 说凭据不对 ----------

    /**
     * email 回 401（store 的密钥配错了）→ 密码不能被改。
     * 这条本来就绿（401 抛异常 → catch → 返回 false），写下来防绿灯时把 401 单独处理改坏：
     * 最坏的改法是"校验没成功但没抛异常"，于是不用验证码就能改任何人的密码。
     */
    @Test
    void emailAnswers401_passwordIsNotChanged() {
        Account user = newUser();
        FAKE_BANK.stubFor(post(urlEqualTo(VERIFY)).willReturn(aResponse().withStatus(401)));

        boolean reset = accountService.resetPassword(user.getEmail(), "123456", "new-password");

        FAKE_BANK.verify(postRequestedFor(urlEqualTo(VERIFY)));
        assertThat(reset).isFalse();
        assertThat(passwordOf(user)).as("密码没变").isEqualTo("old-password-hash");
    }

    /** email 回 401 要打 ALERT：否则用户只看到"发送失败"，日志里只有 System.err 的一行，没人知道是密钥配错了。 */
    @Test
    void emailAnswers401_raisesAlert(CapturedOutput output) {
        Account user = newUser();
        FAKE_BANK.stubFor(post(urlEqualTo(SEND)).willReturn(aResponse().withStatus(401)));

        boolean sent = accountService.sendPasswordResetEmail(user.getEmail());

        FAKE_BANK.verify(postRequestedFor(urlEqualTo(SEND)));
        assertThat(sent).isFalse();
        assertThat(output.getAll()).contains("ALERT email_auth_failed");
    }
}
