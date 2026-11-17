package com.comp5348.delivery;

import com.comp5348.delivery.support.KafkaProbe;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * delivery 没配服务凭据就不能启动。失败场景表（Obsidian 36 号文件）E2。
 * 和 bank 的 BankStartupWithoutServiceTokenTest 是同一个写法，说明看那边。
 *
 * 【层】启动测试：测试自己调 SpringApplicationBuilder(...).run(...) 把 delivery 整个启动一次。
 * 【为什么自己起一套 Postgres + Kafka，不用 DeliveryIntegrationTest 的】这里启动的是第二个 delivery。
 *   要是连同一套，它会加入同一个消费者组、抢别的测试的 OrderPaid，也会去动同一个库里的配送单。
 *   所以：自己的空库、自己的 Kafka、另一个消费者组名。RabbitMQ 指向没人监听的端口、监听器不启动。
 * 【红灯为什么是红】delivery 现在根本不读 delivery.service-token，空的也照常启动。不是编译错。
 * 【约定】配置项 delivery.service-token 为空或只有空格 → 启动失败，异常信息里要有这个键名。
 */
class DeliveryStartupWithoutServiceTokenTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    static {
        POSTGRES.start();
        KAFKA.start();
        new KafkaProbe(KAFKA.getBootstrapServers()).createTopics(KafkaProbe.ALL_TOPICS);
    }

    /** 把 delivery 启动一次再关掉。启动成功返回 null，启动失败返回那个异常。 */
    private static Throwable startDelivery(String serviceToken) {
        try (ConfigurableApplicationContext context = new SpringApplicationBuilder(DeliveryApplication.class).run(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.rabbitmq.username=guest",
                "--spring.rabbitmq.password=guest",
                "--spring.rabbitmq.port=1",
                "--spring.rabbitmq.listener.simple.auto-startup=false",
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--spring.kafka.consumer.group-id=delivery-startup-test",
                "--server.port=0",
                "--delivery.service-token=" + serviceToken)) {
            return null;
        } catch (Throwable startupFailure) {
            return startupFailure;
        }
    }

    /** E2：凭据是空的，或者只有空格 → 启动失败，异常里点名 delivery.service-token。 */
    @ParameterizedTest(name = "token = \"{0}\"")
    @ValueSource(strings = {"", "   "})
    void blankServiceToken_failsStartup(String blank) {
        Throwable failure = startDelivery(blank);

        assertThat(failure).as("没配服务凭据，delivery 不该启动成功").isNotNull();
        assertThat(failure).hasStackTraceContaining("delivery.service-token");
    }

    /** 对照：同样的启动方式，配了凭据就能启动。现在就是绿的；防上面那条因为别的原因启动失败而"假绿"。 */
    @Test
    void withServiceToken_starts() {
        assertThat(startDelivery("test-delivery-service-token")).as("配了凭据应该正常启动").isNull();
    }
}
