package com.comp5348.email;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * email 没配服务凭据就不能启动。失败场景表（Obsidian 36 号文件）E2。
 * 和 bank 的 BankStartupWithoutServiceTokenTest 是同一个写法，说明看那边。
 *
 * 【层】启动测试：测试自己调 SpringApplicationBuilder(...).run(...) 把 email 整个启动一次。真 Postgres；
 *   RabbitMQ 指向没人监听的端口、监听器不启动；不会发邮件（启动时本来就不发）。
 * 【红灯为什么是红】email 现在根本不读 email.service-token，空的也照常启动。不是编译错。
 * 【约定】配置项 email.service-token 为空或只有空格 → 启动失败，异常信息里要有这个键名。
 */
class EmailStartupWithoutServiceTokenTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    /** 把 email 启动一次再关掉。启动成功返回 null，启动失败返回那个异常。 */
    private static Throwable startEmail(String serviceToken) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(EmailApplication.class).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.rabbitmq.username=guest",
                "--spring.rabbitmq.password=guest",
                "--spring.rabbitmq.port=1",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.mail.username=unused@test.local",
                "--spring.mail.password=unused",
                "--server.port=0",
                "--email.service-token=" + serviceToken)) {
            return null;
        } catch (Throwable startupFailure) {
            return startupFailure;
        }
    }

    /** E2：凭据是空的，或者只有空格 → 启动失败，异常里点名 email.service-token。 */
    @ParameterizedTest(name = "token = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void blankServiceToken_failsStartup(String blank) {
        Throwable failure = startEmail(blank);

        assertThat(failure).as("没配服务凭据，email 不该启动成功").isNotNull();
        assertThat(failure).hasStackTraceContaining("email.service-token");
    }

    /** 对照：同样的启动方式，配了凭据就能启动。现在就是绿的；防上面那条因为别的原因启动失败而"假绿"。 */
    @Test
    void withServiceToken_starts() {
        assertThat(startEmail("test-email-service-token")).as("配了凭据应该正常启动").isNull();
    }
}
