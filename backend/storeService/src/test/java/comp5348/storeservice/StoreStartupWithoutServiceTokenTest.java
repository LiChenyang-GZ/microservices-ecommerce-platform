package comp5348.storeservice;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * store 少配了任何一把服务凭据就不能启动。失败场景表（Obsidian 36 号文件）E2 的 store 这一半。
 * 和 bank 的 BankStartupWithoutServiceTokenTest 是同一个写法。
 *
 * 为什么 store 也要：store 是调用方，漏配时它照常启动，然后每次调 bank / delivery / email 都被拒（401 /
 * UNAUTHENTICATED）。有 ALERT，但要等到第一笔付款、第一次取消、第一次找回密码才会出现；启动失败则在部署那一刻就暴露。
 *
 * 【层】启动测试：测试自己调 SpringApplicationBuilder(...).run(...) 把 store 整个启动一次。
 *   真 Postgres、真 Kafka（Testcontainers）；RabbitMQ 指向没人监听的端口；OutboxProcessor 关掉，不会去调 bank。
 * 【红灯为什么是红】store 现在读这三个键，但不检查是不是空的，空的也照常启动。不是编译错。
 * 【约定】bank.service-token、delivery.service-token、email.service-token 任何一个为空或只有空格 → 启动失败，
 *   异常信息里要有那个键名（运维一看就知道少配了哪一把）。
 */
class StoreStartupWithoutServiceTokenTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");
    static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    /** 三把凭据都配好的样子（只在测试里用的假值） */
    private static Map<String, String> allTokens() {
        return Map.of(
                "bank.service-token", "test-bank-service-token",
                "delivery.service-token", "test-delivery-service-token",
                "email.service-token", "test-email-service-token");
    }

    /** 把 store 启动一次再关掉。启动成功返回 null，启动失败返回那个异常。 */
    private static Throwable startStore(Map<String, String> tokens) {
        List<String> args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
                "--jwt.secret=test-only-secret-test-only-secret-0123456789abcdef",
                "--RABBITMQ_USERNAME=guest",
                "--RABBITMQ_PASSWORD=guest",
                "--spring.rabbitmq.port=1",
                "--outbox.processor.enabled=false",
                "--server.port=0"));
        // 命令行参数优先级最高：就算本机 backend/.env 里配了真凭据，这里给的值也会盖掉它
        tokens.forEach((key, value) -> args.add("--" + key + "=" + value));
        try (ConfigurableApplicationContext context =
                     new SpringApplicationBuilder(StoreServiceApplication.class).run(args.toArray(String[]::new))) {
            return null;
        } catch (Throwable startupFailure) {
            return startupFailure;
        }
    }

    /** 每一把凭据 × 两种"没配"（空的、只有空格） */
    static Stream<Arguments> oneBlankToken() {
        return Stream.of("bank.service-token", "delivery.service-token", "email.service-token")
                .flatMap(key -> Stream.of(Arguments.of(key, ""), Arguments.of(key, "   ")));
    }

    /** E2：三把里有一把是空的（另外两把正常）→ 启动失败，异常里点名空掉的那个键。 */
    @ParameterizedTest(name = "{0} = \"{1}\"")
    @MethodSource("oneBlankToken")
    void oneBlankServiceToken_failsStartup(String key, String blank) {
        Map<String, String> tokens = new java.util.HashMap<>(allTokens());
        tokens.put(key, blank);

        Throwable failure = startStore(tokens);

        assertThat(failure).as("没配 " + key + "，store 不该启动成功").isNotNull();
        assertThat(failure).hasStackTraceContaining(key);
    }

    /** 对照：同样的启动方式，三把都配了就能启动。现在就是绿的；防上面那条因为别的原因启动失败而"假绿"。 */
    @Test
    void withAllServiceTokens_starts() {
        assertThat(startStore(allTokens())).as("三把凭据都配了应该正常启动").isNull();
    }
}
