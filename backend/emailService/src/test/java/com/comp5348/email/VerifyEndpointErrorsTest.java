package com.comp5348.email;

import com.comp5348.email.service.EmailService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 独立审查（Obsidian 51 号）🟡1 的 email 一半：校验验证码时 email 自己出了错（数据库断了、死锁、提交失败），
 * 不能回 400 "码不对"——store 会把它告诉用户"码不对"，用户反复重试，也没人知道 email 的库坏了。
 * 回 500，store 把它当成"email 那边出问题"（503 + 日志）。回复里不带异常信息（会把内部细节给出去）。
 *
 * 【层】集成测试：真的 email 的 HTTP 层（过滤器 + controller），EmailService 换成 Mockito 假对象，让它抛异常。
 * 【红灯为什么红】EmailController#verifyCode、#verifyPasswordResetCode 的 catch (Exception) 回 400，
 *   而且把 e.getMessage() 拼进回复。
 */
@SpringBootTest(properties = {
        "EMAIL_DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        "MAIL_USERNAME=unused@test.local",
        "MAIL_PASSWORD=unused",
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "email.service-token=" + VerifyEndpointErrorsTest.TOKEN
})
@AutoConfigureMockMvc
class VerifyEndpointErrorsTest {

    static final String TOKEN = "test-email-service-token";

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @Autowired MockMvc mvc;
    @MockitoBean EmailService emailService;

    @ParameterizedTest
    @ValueSource(strings = {"/api/email/verify-code", "/api/email/verify-password-reset"})
    void internalError_is500_withoutTheExceptionText(String path) throws Exception {
        when(emailService.verifyCode(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db connection secret-detail"));
        when(emailService.verifyPasswordResetCode(anyString(), anyString()))
                .thenThrow(new DataAccessResourceFailureException("db connection secret-detail"));

        mvc.perform(post(path).header("X-Service-Token", TOKEN).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"someone@test.local\",\"code\":\"123456\"}"))
                .andExpect(status().isInternalServerError())
                .andExpect(content().string(not(containsString("secret-detail"))));
    }
}
