package com.comp5348.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Christy 的决定 3（2026-11-17，同一天对 email 再确认了一次）：没有调用方的危险接口删掉。
 *   - POST /api/email/send-delivery-update、/send-order-cancelled、/send-order-failed、/send-refund-success：
 *     通知邮件走 RabbitMQ 的 email_queue（store 的 EmailAdapter、email 的 EmailQueueConsumer），
 *     这四个 HTTP 接口没有任何调用方。留着就是第二个"让 email 发信"的入口。
 *   - GET /api/email/check-verified/{email}：前端和别的服务都不调（store 有自己的 /api/user/check-verified）。
 *
 * 【层】集成测试：真的 email（Spring + 真 Postgres），MockMvc 发请求，不经过网络。发信器是 Mockito 假对象。
 * 【为什么每个请求都带对的服务凭据】绿灯以后 email 的内部接口都要凭据，不带的话会先被挡成 401，
 *   那就分不清"接口没了"和"接口还在、只是没凭据"。带对凭据还是 404，才证明接口真的删了。
 *   （不带凭据的那一半在 EmailServiceTokenIntegrationTest：401。）
 * 【红灯为什么是红】五个接口都还在：回 200，四个通知接口真的调了 mailSender.send。不是编译错。
 */
@SpringBootTest(properties = {
        // 和 EmailServiceTokenIntegrationTest 一模一样：配置相同，Spring 就复用同一个容器，不用再启动一次
        "EMAIL_DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        "MAIL_USERNAME=unused@test.local",
        "MAIL_PASSWORD=unused",
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        "email.service-token=" + EmailServiceTokenIntegrationTest.SERVICE_TOKEN
})
@AutoConfigureMockMvc
class OldEmailEndpointsRemovedTest {

    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = EmailServiceTokenIntegrationTest.POSTGRES;

    static {
        POSTGRES.start(); // 已经启动过的话什么都不做
    }

    @Autowired MockMvc mvc;
    @MockitoBean JavaMailSender mailSender;

    private static String newEmail() {
        return "victim-" + UUID.randomUUID() + "@test.local";
    }

    /** 四个通知接口：带对凭据也是 404，一封信都没发。第二列是请求体里除 email 以外的字段 */
    @ParameterizedTest(name = "{0}")
    @CsvSource(delimiter = '|', value = {
            "/api/email/send-delivery-update | \"orderId\":\"42\",\"deliveryId\":7,\"status\":\"DELIVERED\"",
            "/api/email/send-order-cancelled | \"orderId\":\"42\",\"reason\":\"fake\"",
            "/api/email/send-order-failed    | \"orderId\":\"42\",\"reason\":\"fake\"",
            "/api/email/send-refund-success  | \"orderId\":\"42\",\"refundTxnId\":\"9101\""
    })
    void notificationEndpointIsGone_andSendsNothing(String path, String otherFields) throws Exception {
        String json = "{\"email\":\"%s\",%s}".formatted(newEmail(), otherFields);

        mvc.perform(post(path).header(EmailServiceTokenIntegrationTest.TOKEN_HEADER,
                                EmailServiceTokenIntegrationTest.SERVICE_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isNotFound());

        verify(mailSender, never()).send(any(SimpleMailMessage.class));
    }

    @Test
    void checkVerifiedEndpointIsGone() throws Exception {
        mvc.perform(get("/api/email/check-verified/" + newEmail())
                        .header(EmailServiceTokenIntegrationTest.TOKEN_HEADER,
                                EmailServiceTokenIntegrationTest.SERVICE_TOKEN))
                .andExpect(status().isNotFound());
    }
}
