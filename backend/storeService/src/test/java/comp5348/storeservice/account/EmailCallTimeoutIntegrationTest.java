package comp5348.storeservice.account;

import comp5348.storeservice.model.Account;
import comp5348.storeservice.service.AccountService;
import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

/**
 * email 能连上但一直不回时，store 的找回密码不能跟着一直等。
 * 失败场景表（Obsidian 36 号文件）E1b；独立审查（40 号文件）🟡2。
 *
 * 【层】集成测试：真的 store（Spring + 真 Postgres），假 email（WireMock，故意 15 秒才回）。
 * 【红灯为什么是红】AccountService 调 email 用的 RestTemplate 没设超时（老问题：原来的 `new RestTemplate()` 也没有，
 *   鉴权 PR ① 换成 RestTemplateBuilder 时没补上）→ 一直等到 email 回答。测试给 10 秒，等不到就判失败。
 * 【约定】和 BankAdapter、DeliveryAdapter 一样：连接、读取各 5 秒；超时当成失败，返回 false。
 *   为什么要紧：每个卡住的请求占着一个 Tomcat 线程，email 卡住几分钟，store 的所有接口都可能没线程可用。
 */
class EmailCallTimeoutIntegrationTest extends StoreIntegrationTest {

    private static final String SEND = "/api/email/send-password-reset";

    /** email 比 store 的超时（5 秒）慢得多才回 */
    private static final int EMAIL_HANGS_MS = 15_000;

    @Autowired AccountService accountService;

    private Account newUser() {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        Account user = new Account();
        user.setEmail("timeout-" + tag + "@test.local");
        user.setUsername("timeout-" + tag);
        user.setPassword("old-password-hash");
        return accountRepository.save(user);
    }

    /** email 15 秒才回 → store 在 10 秒内放弃，返回 false（"发送失败"）。现在：一直等，10 秒到了还没返回。 */
    @Test
    void emailHangs_passwordResetGivesUpWithinTimeout() {
        Account user = newUser();
        FAKE_BANK.stubFor(post(urlEqualTo(SEND))
                .willReturn(okJson("{\"success\":true,\"message\":\"sent\"}").withFixedDelay(EMAIL_HANGS_MS)));

        // assertTimeoutPreemptively：10 秒一到就在另一个线程里打断它、判失败，测试本身不会跟着卡 15 秒
        boolean sent = assertTimeoutPreemptively(Duration.ofSeconds(10),
                () -> accountService.sendPasswordResetEmail(user.getEmail()));

        FAKE_BANK.verify(postRequestedFor(urlEqualTo(SEND)));
        assertThat(sent).as("超时算发送失败").isFalse();
    }
}
