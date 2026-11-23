package com.comp5348.email;

import com.comp5348.email.repository.EmailOutboxRepository;
import com.comp5348.email.service.EmailService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * email 的接口分两类：给浏览器的（公开）和给别的服务的（要服务凭据）。失败场景表（Obsidian 36 号文件）E1。
 * 这是 email 的第一个测试。
 *
 *   公开（注册页在用，入口放行）：POST /api/email/send-verification。verify-code 在 A11（Obsidian 50 号）之后只给 store。
 *   要服务凭据：其余全部 —— 四个通知（send-delivery-update、send-order-cancelled、send-order-failed、
 *     send-refund-success）、找回密码的两个（只有 store 的 AccountService 在调，前端不调）、check-verified。
 *
 * 【层】集成测试：真的 email（Spring + 真 Postgres），MockMvc 发请求，会经过 servlet 过滤器，不经过网络。
 * 【假的】JavaMailSender 换成 Mockito 假对象：不真发邮件，但能数"发了几封"。RabbitMQ 的监听器不启动。
 * 【红灯为什么是红】TokenAuthenticationFilter#doFilterInternal 没带 token 就放行，EmailController 自己也不查：
 *   不带凭据、带错凭据的请求都回 200，真的调了 mailSender.send（能冒充商店给任意邮箱发信）。不是编译错。
 * 【约定】凭据放在 HTTP 头 X-Service-Token；email 这边的配置项是 email.service-token
 *   （部署时来自环境变量 EMAIL_SERVICE_TOKEN）。不带或不对 → 401，email 什么都不做。
 */
@SpringBootTest(properties = {
        // application.properties 里这些值来自环境变量；测试里给假的，避免依赖本机的 .env
        "EMAIL_DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        "MAIL_USERNAME=unused@test.local",
        "MAIL_PASSWORD=unused",
        // 指向没人监听的端口，并且不启动 @RabbitListener：这个测试不碰队列
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "email.service-token=" + EmailServiceTokenIntegrationTest.SERVICE_TOKEN
})
@AutoConfigureMockMvc
class EmailServiceTokenIntegrationTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** email 认的那一把（只在这个测试里用的假值） */
    static final String SERVICE_TOKEN = "test-email-service-token";
    static final String TOKEN_HEADER = "X-Service-Token";

    static {
        POSTGRES.start();
    }

    @Autowired MockMvc mvc;
    @Autowired EmailService emailService;
    @Autowired EmailOutboxRepository outboxRepository;

    /** 假的发信器：send() 什么都不做，Mockito 记下被调了几次 */
    @MockitoBean JavaMailSender mailSender;

    // ---------- 小工具 ----------

    private static String newEmail() {
        return "victim-" + UUID.randomUUID() + "@test.local";
    }

    /** POST + JSON；token 为 null 就不带头 */
    private static MockHttpServletRequestBuilder postJson(String path, String json, String token) {
        MockHttpServletRequestBuilder request = post(path).contentType(MediaType.APPLICATION_JSON).content(json);
        return token == null ? request : request.header(TOKEN_HEADER, token);
    }

    private long outboxRowsFor(String email) {
        return outboxRepository.findAll().stream().filter(row -> email.equals(row.getEmail())).count();
    }

    /** 五个"会发信"的内部接口 × 两种凭据（null = 不带头，"not-the-token" = 带错）。%s 处填收件邮箱 */
    static Stream<Arguments> sendingEndpoints_withoutOrWrongToken() {
        return Stream.of(
                        Arguments.of("/api/email/send-delivery-update",
                                "{\"email\":\"%s\",\"orderId\":\"42\",\"deliveryId\":7,\"status\":\"DELIVERED\"}"),
                        Arguments.of("/api/email/send-order-cancelled",
                                "{\"email\":\"%s\",\"orderId\":\"42\",\"reason\":\"fake\"}"),
                        Arguments.of("/api/email/send-order-failed",
                                "{\"email\":\"%s\",\"orderId\":\"42\",\"reason\":\"fake\"}"),
                        Arguments.of("/api/email/send-refund-success",
                                "{\"email\":\"%s\",\"orderId\":\"42\",\"refundTxnId\":\"9101\"}"),
                        Arguments.of("/api/email/send-password-reset",
                                "{\"email\":\"%s\"}"))
                .flatMap(endpoint -> Stream.of(
                        Arguments.of(endpoint.get()[0], endpoint.get()[1], null),
                        Arguments.of(endpoint.get()[0], endpoint.get()[1], "not-the-token")));
    }

    // ---------- 被拒绝的 ----------

    /** E1：不带 / 带错凭据调会发信的内部接口 → 401，一封信都没发，email_outbox 里没有这个收件人的行。 */
    @ParameterizedTest(name = "{0}, token = {2}")
    @MethodSource("sendingEndpoints_withoutOrWrongToken")
    void sendingEndpoint_withoutOrWrongToken_is401_andSendsNothing(String path, String json, String token)
            throws Exception {
        String victim = newEmail();

        mvc.perform(postJson(path, json.formatted(victim), token))
                .andExpect(status().isUnauthorized());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
        assertThat(outboxRowsFor(victim)).as("email_outbox 里没有这个收件人").isZero();
    }

    /**
     * E1：校验找回密码验证码的接口也只给 store。先让 email 真的发一个验证码（直接调 EmailService，拿到码），
     * 再不带 / 带错凭据去校验 → 401，而且码没被用掉（之后带着正确流程还能校验通过）。
     * 现在：200，码被用掉 —— 谁都可以拿这个接口一个个试 6 位数字。
     */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void verifyPasswordReset_withoutOrWrongToken_is401_andDoesNotUseUpTheCode(String token) throws Exception {
        String email = newEmail();
        String code = emailService.sendPasswordResetCode(email);

        mvc.perform(postJson("/api/email/verify-password-reset",
                        "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code), token))
                .andExpect(status().isUnauthorized());

        assertThat(emailService.verifyPasswordResetCode(email, code)).as("码还没被用掉").isTrue();
    }

    /** E1：查"这个邮箱验证过没有"。前端和别的服务都不调它（store 有自己的 /api/user/check-verified）。现在：200。 */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void checkVerified_withoutOrWrongToken_is401(String token) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/email/check-verified/" + newEmail());

        mvc.perform(token == null ? request : request.header(TOKEN_HEADER, token))
                .andExpect(status().isUnauthorized());
    }

    // ---------- 回归（这两条现在就是绿的，写下来防以后改坏） ----------

    /** 注册页发验证码的接口保持公开：不带任何凭据也能发。 */
    @Test
    void sendVerification_staysPublic() throws Exception {
        String email = newEmail();

        mvc.perform(postJson("/api/email/send-verification", "{\"email\":\"%s\"}".formatted(email), null))
                .andExpect(status().isOk());

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }

    // ---------- A11（Obsidian 50 号文件 V10）：校验注册验证码只给 store ----------

    /**
     * V10：以前 verify-code 是公开的，浏览器自己调完再告诉 store "验证过了"，store 没法确认。
     * 现在由 store 的 activate 带着码来问，所以 verify-code 只认 store 的服务凭据：
     * 不带 / 带错 → 401，码没被用掉（外人不能绕过 store 一个个试）。现在：200，码被用掉。
     */
    @ParameterizedTest(name = "token = {0}")
    @NullSource
    @ValueSource(strings = "not-the-token")
    void verifyCode_withoutOrWrongToken_is401_andDoesNotUseUpTheCode(String token) throws Exception {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        mvc.perform(postJson("/api/email/verify-code",
                        "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code), token))
                .andExpect(status().isUnauthorized());

        assertThat(emailService.verifyCode(email, code)).as("码还没被用掉").isTrue();
    }

    /** V10：带对凭据（store 的 activate 就是这样调的）→ 200。这条现在就绿，防绿灯时把它锁死。 */
    @Test
    void verifyCode_withCorrectToken_works() throws Exception {
        String email = newEmail();
        String code = emailService.sendRegistrationVerificationCode(email);

        mvc.perform(postJson("/api/email/verify-code",
                        "{\"email\":\"%s\",\"code\":\"%s\"}".formatted(email, code), SERVICE_TOKEN))
                .andExpect(status().isOk());
    }

    /** 带对凭据，store 在用的找回密码接口照常：200，发一封信。 */
    @Test
    void sendPasswordReset_withCorrectToken_stillSends() throws Exception {
        mvc.perform(postJson("/api/email/send-password-reset",
                        "{\"email\":\"%s\"}".formatted(newEmail()), SERVICE_TOKEN))
                .andExpect(status().isOk());

        verify(mailSender, times(1)).send(any(SimpleMailMessage.class));
    }
}
