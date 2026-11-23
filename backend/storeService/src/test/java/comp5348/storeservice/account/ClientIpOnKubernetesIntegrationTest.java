package comp5348.storeservice.account;

import comp5348.storeservice.support.StoreIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 限流（Obsidian 54 号）R16 在 Kubernetes 上：kind 实测时发现的。
 *
 * 【场景】kind 上带一个自己写的 X-Forwarded-For: 1.2.3.4 去要码，store 按 1.2.3.4 计数——换个值就是新的额度，限流形同虚设。
 * 【原因】Spring Boot 发现自己在 Kubernetes 里（有 KUBERNETES_SERVICE_HOST），会自动打开 Tomcat 的 RemoteIpValve：
 *   它从右往左跳过内网地址，把第一个外网地址当成客户端，还把处理过的部分从头里删掉。kind 里真实客户端是 172.22.0.1（内网），
 *   被当成"可信代理"跳过，于是客户端自己写的值成了"客户端"。compose 不是 Kubernetes，没这个问题。
 * 【约定】store 自己决定信哪一段（ClientIp：入口追加的最后一个值），Tomcat 不要动这个头。
 *
 * 【层】集成测试，真的 Tomcat（随机端口），spring.main.cloud-platform=kubernetes 假装在集群里。MockMvc 不经过 Tomcat，测不出来。
 *   和 StoreIntegrationTest 同样的配置（@SpringBootTest 在子类上会整个替换父类的，所以照抄一份）。
 * 【红灯为什么红】X-Forwarded-For "1.2.3.4, 203.0.113.9"（203.0.113.9 是入口追加的）：valve 把 203.0.113.9 拿走、头里只剩 1.2.3.4，
 *   store 按 1.2.3.4 计数。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "jwt.secret=test-only-secret-test-only-secret-0123456789abcdef",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        "spring.rabbitmq.port=1",
        "outbox.processor.enabled=true",
        "event-outbox.publisher.interval-ms=200",
        "bank.service-token=test-bank-service-token",
        "delivery.service-token=test-delivery-service-token",
        "email.service-token=test-email-service-token",
        // 这一条才是这个测试要的：像在 Kubernetes 里一样
        "spring.main.cloud-platform=kubernetes"
})
class ClientIpOnKubernetesIntegrationTest extends StoreIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void onKubernetes_countsTheAddressTheEntryAppended_notOneTheClientWrote() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + "/api/user/send-verification"))
                .header("Content-Type", "application/json")
                .header("X-Forwarded-For", "1.2.3.4, 203.0.113.9")
                .POST(HttpRequest.BodyPublishers.ofString("{\"email\":\"nobody-k8s@test.local\"}"))
                .build();

        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(redis.keys("rl:send-verification:ip:*"))
                .as("按入口追加的地址计数，不是客户端自己写的 1.2.3.4")
                .containsExactly("rl:send-verification:ip:203.0.113.9");
    }
}
