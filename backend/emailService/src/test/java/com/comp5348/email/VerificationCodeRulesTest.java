package com.comp5348.email;

import com.comp5348.email.model.EmailOutbox;
import com.comp5348.email.repository.EmailOutboxRepository;
import com.comp5348.email.service.EmailService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;

import org.springframework.mail.MailSendException;
import org.springframework.mail.SimpleMailMessage;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

/**
 * A11（Obsidian 50 号文件）V11–V15：激活改成"store 拿着码来问 email"以后，码本身得经得起猜。
 *
 *   V11 / V12  注册码和找回密码的码各管各的：EmailService#verifyCode 只认 REGISTRATION，
 *              #verifyPasswordResetCode 只认 PASSWORD_RESET。
 *   V13        同一邮箱、同一类型连续猜错 5 次，当前的码作废，之后猜对也不行，要重发。
 *   V14        重发时旧码作废：同一时间只有一个码有效（不然多发几次，猜中的概率就跟着翻倍）。
 *   V15        过期的码不行（老逻辑，回归）。
 *
 * 【层】集成测试：真的 email（Spring + 真 Postgres），直接调 EmailService，不走 HTTP（HTTP 那层的凭据检查在
 *   EmailServiceTokenIntegrationTest）。JavaMailSender 换成 Mockito 假对象，发信什么都不做 → 码直接是 SENT。
 * 【红灯为什么红】EmailService 按 email + code + SENT 查一行就算对：不看类型、不数错几次、重发不作废旧码。
 */
@SpringBootTest(properties = {
        "EMAIL_DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        "MAIL_USERNAME=unused@test.local",
        "MAIL_PASSWORD=unused",
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "email.service-token=test-email-service-token"
})
class VerificationCodeRulesTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired EmailService emailService;
    @Autowired EmailOutboxRepository outboxRepository;
    @MockitoBean JavaMailSender mailSender;

    private static String newEmail() {
        return "code-" + UUID.randomUUID() + "@test.local";
    }

    /** 一个肯定不是 code 的 6 位数 */
    private static String notThe(String code) {
        return code.equals("000000") ? "111111" : "000000";
    }

    // ---------- V11、V12：类型 ----------

    /** V11：找回密码的码不能用来激活（注册校验）。现在：能。 */
    @Test
    void passwordResetCode_doesNotPassRegistrationCheck() {
        String email = newEmail();
        String resetCode = emailService.sendPasswordResetCode(email);

        assertThat(emailService.verifyCode(email, resetCode)).isFalse();
        assertThat(emailService.verifyPasswordResetCode(email, resetCode)).as("它自己那边还能用").isTrue();
    }

    /** V12：注册码不能用来重置密码。现在：能。 */
    @Test
    void registrationCode_doesNotPassPasswordResetCheck() {
        String email = newEmail();
        String registrationCode = emailService.sendRegistrationVerificationCode(email);

        assertThat(emailService.verifyPasswordResetCode(email, registrationCode)).isFalse();
        assertThat(emailService.verifyCode(email, registrationCode)).as("它自己那边还能用").isTrue();
    }

    // ---------- V13：猜错次数 ----------

    /** V13：连续猜错 5 次以后，对的码也不行了。现在：猜多少次都行，第 6 次猜对就过。 */
    @Test
    void fiveWrongGuesses_burnTheCode() {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        for (int i = 0; i < 5; i++) {
            assertThat(emailService.verifyCode(email, notThe(code))).isFalse();
        }

        assertThat(emailService.verifyCode(email, code)).as("错满 5 次，码作废").isFalse();
    }

    /** V13 的边界：错 4 次，第 5 次猜对还能过（不能把手抖输错的人锁得太早）。现在就绿。 */
    @Test
    void fourWrongGuesses_thenTheRightCode_stillWorks() {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        for (int i = 0; i < 4; i++) {
            assertThat(emailService.verifyCode(email, notThe(code))).isFalse();
        }

        assertThat(emailService.verifyCode(email, code)).isTrue();
    }

    /** V13：码作废以后重发一个新码，新码能用。 */
    @Test
    void afterBurning_aNewCodeWorks() {
        String email = newEmail();
        String first = emailService.sendRegistrationVerificationCode(email);
        for (int i = 0; i < 5; i++) {
            emailService.verifyCode(email, notThe(first));
        }

        String second = emailService.sendRegistrationVerificationCode(email);

        assertThat(emailService.verifyCode(email, second)).isTrue();
    }

    /** V13 对找回密码的码也一样。现在：不限次数。 */
    @Test
    void fiveWrongGuesses_burnThePasswordResetCode() {
        String email = newEmail();
        String code = emailService.sendPasswordResetCode(email);

        for (int i = 0; i < 5; i++) {
            assertThat(emailService.verifyPasswordResetCode(email, notThe(code))).isFalse();
        }

        assertThat(emailService.verifyPasswordResetCode(email, code)).isFalse();
    }

    // ---------- V14：重发 ----------

    /** V14：重发以后旧码不能用，新码能用。现在：两个都能用。 */
    @Test
    void resend_invalidatesTheOlderCode() {
        String email = newEmail();
        String first = emailService.sendRegistrationVerificationCode(email);
        String second = emailService.sendRegistrationVerificationCode(email);
        if (first.equals(second)) {
            return; // 百万分之一：两次正好一样，这次没法区分新旧
        }

        assertThat(emailService.verifyCode(email, first)).as("旧码").isFalse();
        assertThat(emailService.verifyCode(email, second)).as("新码").isTrue();
    }

    /**
     * 独立审查（51 号）🟡2：重发时发信失败（SMTP 抖了一下）→ 旧码不能跟着没了，不然用户手里一个能用的码都没有。
     * 旧码只在新码真的发出去以后才作废。现在：先作废旧码再发信，发信失败旧码也死了。
     */
    @Test
    void resendThatFailsToSend_keepsTheOlderCode() {
        String email = newEmail();
        String first = emailService.sendRegistrationVerificationCode(email);
        doThrow(new MailSendException("smtp down")).when(mailSender).send(any(SimpleMailMessage.class));

        assertThatThrownBy(() -> emailService.sendRegistrationVerificationCode(email))
                .isInstanceOf(RuntimeException.class); // 这次发信失败，接口会回 400 "Sending failed"

        assertThat(emailService.verifyCode(email, first)).as("旧码还能用").isTrue();
    }

    // ---------- 独立审查 🟡6：并发（防改坏：把 lockCodes 上的 @Lock 去掉，这两条会红） ----------

    /** 两个请求同时拿同一个对的码来校验（双击、两个标签页）→ 恰好一个成功，码只能用一次。 */
    @Test
    void sameRightCode_twiceAtOnce_onlyOneSucceeds() throws Exception {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        List<Boolean> results = runTogether(2, () -> emailService.verifyCode(email, code));

        assertThat(results).containsExactlyInAnyOrder(true, false);
    }

    /** 5 个错码同时到 → 计数一个不丢，码作废，之后对的码也不行。没有锁时计数会丢更新，对码还能过。 */
    @Test
    void fiveWrongGuessesAtOnce_stillBurnTheCode() throws Exception {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        runTogether(5, () -> emailService.verifyCode(email, notThe(code)));

        assertThat(emailService.verifyCode(email, code)).isFalse();
    }

    /** n 个线程在同一时刻开跑同一件事，等它们都做完，返回各自的结果 */
    private static List<Boolean> runTogether(int n, java.util.concurrent.Callable<Boolean> task) throws Exception {
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(n);
        try {
            java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < n; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<Boolean> results = new java.util.ArrayList<>();
            for (java.util.concurrent.Future<Boolean> f : futures) {
                results.add(f.get(30, java.util.concurrent.TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---------- V15：过期（回归） ----------

    /** V15：过了 5 分钟的码不行。现在就绿。 */
    @Test
    void expiredCode_fails() {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);
        EmailOutbox row = outboxRepository.findAll().stream()
                .filter(r -> email.equals(r.getEmail())).findFirst().orElseThrow();
        row.setExpireAt(LocalDateTime.now().minusMinutes(1));
        outboxRepository.save(row);

        assertThat(emailService.verifyCode(email, code)).isFalse();
    }
}
