package comp5348.storeservice.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import comp5348.storeservice.adapter.EmailAdapter;
import comp5348.storeservice.dto.CreateOrderRequest;
import comp5348.storeservice.dto.OrderDTO;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.*;
import comp5348.storeservice.scheduler.OutboxProcessor;
import comp5348.storeservice.service.OrderProductService;
import comp5348.storeservice.service.PaymentService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * store 集成测试的公共底座。
 *
 * 【这一层是什么】集成测试：真的 Spring 容器 + 真的 Postgres（Testcontainers 起在 Docker 里）+ 假的 bank（WireMock）。
 * 【真的】store 的全部代码、事务、数据库的唯一约束和行锁。
 * 【假的】bank 和 delivery 两个外部服务（同一个 WireMock 冒充），发邮件的 EmailAdapter（Mockito）。
 *
 * 为什么不用本机的 webuser 库：那是平时开发用的库，测试往里写会把它弄乱；
 * 并发测试要真正的行锁和唯一约束，H2 这种内存库模拟得不准。
 */
@SpringBootTest(properties = {
        // application.properties 里这些值来自环境变量；测试里给假的，避免依赖本机的 .env
        "jwt.secret=test-only-secret-test-only-secret-0123456789abcdef",
        "DB_USERNAME=unused-overridden-by-testcontainers",
        "DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        // 指向一个没人监听的端口：就算哪段代码绕过了 EmailAdapter，也发不到本机真的 RabbitMQ
        "spring.rabbitmq.port=1",
        "outbox.processor.enabled=true",
        // 4b：store 的事件投递程序每 200 毫秒跑一轮（生产默认 1 秒），测试等事件不用等太久。红灯时没人读这个键
        "event-outbox.publisher.interval-ms=200"
})
@Import(TestInfraConfig.class)
public abstract class StoreIntegrationTest {

    /** store 现在调 bank 的读超时是 5 秒（BankAdapter 里写死的）。比它慢就是"超时"。 */
    protected static final int SLOWER_THAN_STORE_TIMEOUT_MS = 6_000;

    // ---------- 两个外部替身：只启动一次，所有测试类共用 ----------

    /**
     * 一次性的 Postgres。@ServiceConnection：Spring Boot 自动把 datasource 的地址、用户名、密码换成这个容器的，
     * application.properties 里的 localhost:5432/webuser 根本不会被连。
     * 用 static 块手动 start、不 stop：Spring 会把测试容器缓存起来给所有测试类复用，容器也要活得一样久（进程退出时 Testcontainers 自己清理）。
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 假 bank（顺便也当假 delivery）。dynamicPort：随便找一个空闲端口，不和本机的 8084 撞。 */
    protected static final WireMockServer FAKE_BANK = new WireMockServer(wireMockConfig().dynamicPort());

    /**
     * 第 2 波 A：真的 Kafka（和 compose 同一个镜像）。和 compose 一样关掉自动建 topic，topic 由测试自己建（3 分区）。
     * store 要消费 delivery-events、发布 order-events，测试用 KafkaProbe 往里发事件、读结果。
     */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    protected static final KafkaProbe kafka;

    static {
        POSTGRES.start();
        FAKE_BANK.start();
        KAFKA.start();
        kafka = new KafkaProbe(KAFKA.getBootstrapServers());
        kafka.createTopics(KafkaProbe.ALL_TOPICS);
    }

    /** 把 store 配置里的 bank、delivery 地址改成 WireMock 的地址 */
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("bank.service.url", FAKE_BANK::baseUrl);
        registry.add("delivery.service.url", FAKE_BANK::baseUrl);
        // 契约：compose 里的 SPRING_KAFKA_BOOTSTRAP_SERVERS 自动绑定到这个键
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** 邮件不是这一步要测的东西：换成什么都不做的 Mockito 假对象 */
    @MockitoBean
    protected EmailAdapter emailAdapter;

    @Autowired protected MutableClock clock;
    @Autowired protected OutboxProcessor outboxProcessor;
    @Autowired protected OrderProductService orderProductService;
    @Autowired protected PaymentRepository paymentRepository;
    @Autowired protected PaymentOutboxRepository outboxRepository;
    @Autowired protected OrderRepository orderRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected WarehouseRepository warehouseRepository;
    @Autowired protected WarehouseProductRepository warehouseProductRepository;
    @Autowired protected AccountRepository accountRepository;
    @Autowired protected JdbcTemplate jdbc;

    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeEach
    void resetExternalWorld() {
        // 每个测试从干净的假 bank 开始：清掉上一个测试的剧本和请求记录
        FAKE_BANK.resetAll();
        // 默认剧本：查余额总是够；建配送单总是成功（不返回 id，避免 orders.delivery_id 唯一约束在测试之间撞车）
        FAKE_BANK.stubFor(get(urlPathMatching("/api/bank/account/.*/balance"))
                .willReturn(okJson("{\"success\":true,\"balance\":1000000}")));
        FAKE_BANK.stubFor(post(urlEqualTo("/api/deliveries/create")).willReturn(okJson("{}")));
        FAKE_BANK.stubFor(post(urlPathMatching("/api/deliveries/cancel-by-order/.*")).willReturn(ok()));
        // 第 2 波 A · 4b 的新取消接口（设计"取消接口"一节）：默认 delivery 同意取消
        FAKE_BANK.stubFor(post(urlPathMatching("/internal/deliveries/cancel-by-order/.*"))
                .willReturn(okJson("{\"result\":\"CANCELLED\"}")));
        // OutboxProcessor 每一轮会处理表里所有待办任务，所以清掉别的测试留下的，只留本测试自己的
        jdbc.update("DELETE FROM payment_outbox");
        // 别的测试留下的"待支付"订单直接标成已取消：否则 #14 拨表 16 分钟跑自动取消时，会把它们也取消、触发它们的退款
        jdbc.update("UPDATE orders SET status = 'CANCELLED' WHERE status = 'PENDING_PAYMENT'");
        // 表拨回"现在"
        clock.set(Instant.now());
    }

    // ---------- 造数据：走真实的下单流程 ----------

    /** 下单之后测试要用到的几个 id */
    public record PlacedOrder(Long orderId, Long paymentId, String buyerEmail, String buyerBankAccount,
                              Long warehouseProductId) {}

    /**
     * 像真用户一样下一单：新建商品、仓库（库存 100）、买家（绑定银行账户），
     * 然后调真实的 OrderProductService.createOrderWithPayment —— 它会锁库存、建订单、建 Payment、写 PAY 任务。
     * 每次都用新的商品和买家，测试之间互不影响。
     */
    protected PlacedOrder placeOrder() {
        String tag = UUID.randomUUID().toString().substring(0, 8);

        Product product = new Product();
        product.setName("Test Phone " + tag);
        product.setPrice(new BigDecimal("100.00"));
        product.setStockQuantity(0);
        product = productRepository.save(product);

        Warehouse warehouse = new Warehouse();
        warehouse.setName("Test Warehouse " + tag);
        warehouse.setLocation("Test Street " + tag);
        warehouse = warehouseRepository.save(warehouse);

        WarehouseProduct stock = new WarehouseProduct();
        stock.setWarehouse(warehouse);
        stock.setProduct(product);
        stock.setQuantity(100);
        stock = warehouseProductRepository.save(stock);

        Account buyer = new Account();
        buyer.setEmail("buyer-" + tag + "@test.local");
        buyer.setUsername("buyer-" + tag);
        buyer.setPassword("not-used");
        buyer.setBankAccountNumber("ACC-TEST-" + tag);
        buyer = accountRepository.save(buyer);

        CreateOrderRequest request = new CreateOrderRequest(buyer.getId(),
                List.of(new CreateOrderRequest.OrderItemRequest(product.getId(), 1)));
        OrderDTO order = orderProductService.createOrderWithPayment(request);

        Payment payment = paymentRepository.findByOrderId(order.getId()).orElseThrow();
        return new PlacedOrder(order.getId(), payment.getId(), buyer.getEmail(), buyer.getBankAccountNumber(),
                stock.getId());
    }

    /** 下单并让它付款成功（订单变成 PAID），用来测退款。最后清掉 bank 的请求记录，后面只数退款请求。 */
    protected PlacedOrder placePaidOrder() {
        PlacedOrder order = placeOrder();
        bankTransferSucceeds();
        runOutboxRounds(3);
        assertThat(orderOf(order).getStatus()).as("前提：订单已经付款成功").isEqualTo(OrderStatus.PAID);
        assertThat(paymentOf(order).getStatus()).as("前提：Payment 已经成功").isEqualTo(PaymentStatus.SUCCESS);
        FAKE_BANK.resetRequests();
        return order;
    }

    /** 让 OutboxProcessor 跑 n 轮。每轮之间把表拨 30 秒（n=6 也只有 3 分钟，到不了"10 分钟后再重试"）。 */
    protected void runOutboxRounds(int n) {
        for (int i = 0; i < n; i++) {
            outboxProcessor.processOutbox();
            clock.advance(Duration.ofSeconds(30));
        }
    }

    // ---------- 读结果 ----------

    protected Payment paymentOf(PlacedOrder order) {
        return paymentRepository.findById(order.paymentId()).orElseThrow();
    }

    protected Order orderOf(PlacedOrder order) {
        return orderRepository.findById(order.orderId()).orElseThrow();
    }

    /**
     * 这一单"当前这次扣款"的键：PAY-{paymentId}-{attempt}-{随机段}。随机段存在 Payment 上，所以从数据库读。
     * （审查 #1 之后键里加了随机段，测试不能再自己拼出键来，攻击者也不能。）
     */
    protected String payKeyOf(PlacedOrder order) {
        return PaymentService.payKey(paymentOf(order));
    }

    /** 这一单的退款键：REFUND-{paymentId}-{随机段} */
    protected String refundKeyOf(PlacedOrder order) {
        return PaymentService.refundKey(paymentOf(order));
    }

    /** 这一单在 payment_outbox 里的所有任务 */
    protected List<PaymentOutbox> tasksOf(PlacedOrder order) {
        return outboxRepository.findAll().stream()
                .filter(t -> order.orderId().equals(t.getOrderId()))
                .toList();
    }

    /** 这一单还没做完的某类任务（类型按规格起名：PAY / REFUND） */
    protected List<PaymentOutbox> openTasksOf(PlacedOrder order, String type) {
        return tasksOf(order).stream()
                .filter(t -> type.equals(t.getEventType()) && "PENDING".equals(t.getStatus()))
                .toList();
    }

    /** 假 bank 收到的所有扣款请求 */
    protected List<LoggedRequest> transferRequests() {
        return FAKE_BANK.findAll(postRequestedFor(urlEqualTo("/api/bank/transfer")));
    }

    /** 假 bank 收到的所有退款请求 */
    protected List<LoggedRequest> refundRequests() {
        return FAKE_BANK.findAll(postRequestedFor(urlEqualTo("/api/bank/refund")));
    }

    /** 假 delivery 收到的建配送单请求 */
    protected List<LoggedRequest> deliveryCreateRequests() {
        return FAKE_BANK.findAll(postRequestedFor(urlEqualTo("/api/deliveries/create")));
    }

    /** 假 delivery 收到的新取消接口请求（POST /internal/deliveries/cancel-by-order/{orderId}） */
    protected List<LoggedRequest> deliveryCancelRequests() {
        return FAKE_BANK.findAll(postRequestedFor(urlPathMatching("/internal/deliveries/cancel-by-order/.*")));
    }

    /**
     * 从请求里取出幂等键。
     * 规格要求字段叫 idempotencyKey（照 bank.proto 起名）；现在的代码叫 transactionRef。
     * 两个都认，这样红灯阶段和绿灯阶段用的是同一个测试。退款现在根本没有键，取出来是 null。
     */
    protected static String keyOf(LoggedRequest request) {
        try {
            JsonNode body = JSON.readTree(request.getBodyAsString());
            if (body.hasNonNull("idempotencyKey")) return body.get("idempotencyKey").asText();
            if (body.hasNonNull("transactionRef")) return body.get("transactionRef").asText();
            return null;
        } catch (Exception e) {
            throw new IllegalStateException("bank request body is not JSON: " + request.getBodyAsString(), e);
        }
    }

    // ---------- 给假 bank 写剧本 ----------
    // WireMock：stubFor 登记"收到什么请求 → 回什么"。后登记的优先，所以测试中途可以"换剧本"。

    protected void bankTransferSucceeds() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                .willReturn(okJson("{\"success\":true,\"transactionId\":\"9001\",\"message\":\"Transfer successful\"}")));
    }

    /** 成功，但慢一点（用来制造"银行处理中，用户这时候点了取消 / 又点了 Pay Now"） */
    protected void bankTransferSucceedsSlowly(int delayMs) {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                .willReturn(okJson("{\"success\":true,\"transactionId\":\"9001\",\"message\":\"Transfer successful\"}")
                        .withFixedDelay(delayMs)));
    }

    /**
     * 结果未知：连接被对方重置，store 收不到任何回答。
     * 对 store 来说和"超时"是一回事（RestTemplate 都抛 ResourceAccessException）：钱可能扣了，也可能没扣。
     * 用它代替真的等 5 秒超时，测试快很多。
     */
    protected void bankTransferResultUnknown() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    /**
     * 前 n 次真的超时（bank 其实收到了、也处理了，只是回答比 store 的超时慢），第 n+1 次起正常成功。
     * Scenario 是 WireMock 的"状态机"：每收到一次请求就把状态往前推一格，按状态决定回什么。
     */
    protected void bankTransferTimesOutThenSucceeds(int timeouts) {
        String state = Scenario.STARTED;
        for (int i = 1; i <= timeouts; i++) {
            String next = "timed-out-" + i;
            FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                    .inScenario("transfer").whenScenarioStateIs(state).willSetStateTo(next)
                    .willReturn(okJson("{\"success\":true,\"transactionId\":\"9001\",\"message\":\"Transfer successful\"}")
                            .withFixedDelay(SLOWER_THAN_STORE_TIMEOUT_MS)));
            state = next;
        }
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                .inScenario("transfer").whenScenarioStateIs(state)
                .willReturn(okJson("{\"success\":true,\"transactionId\":\"9001\",\"message\":\"Transfer successful\"}")));
    }

    /** bank 明确拒绝（真 bank 的做法：HTTP 400 + success=false） */
    protected void bankRejectsTransfer(String reason) {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/transfer"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"transactionId\":\"9002\",\"message\":\"" + reason + "\"}")));
    }

    protected void bankRefundSucceeds() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/refund"))
                .willReturn(okJson("{\"success\":true,\"refundTransactionId\":\"9101\",\"message\":\"Refund successful\"}")));
    }

    protected void bankRefundResultUnknown() {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/refund"))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    protected void bankRejectsRefund(String reason) {
        FAKE_BANK.stubFor(post(urlEqualTo("/api/bank/refund"))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"success\":false,\"refundTransactionId\":null,\"message\":\"" + reason + "\"}")));
    }
}
