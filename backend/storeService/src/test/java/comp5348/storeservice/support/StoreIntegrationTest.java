package comp5348.storeservice.support;

import com.comp5348.contracts.bank.v1.Outcome;
import com.comp5348.contracts.bank.v1.RefundRequest;
import com.comp5348.contracts.bank.v1.RefundResponse;
import com.comp5348.contracts.bank.v1.TransferRequest;
import com.comp5348.contracts.bank.v1.TransferResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.google.protobuf.Message;
import comp5348.storeservice.support.FakeBankGrpc.Reply;
import io.grpc.Status;
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
 * 【这一层是什么】集成测试：真的 Spring 容器 + 真的 Postgres（Testcontainers 起在 Docker 里）+ 假的 bank。
 * 【真的】store 的全部代码、事务、数据库的唯一约束和行锁。
 * 【假的】bank：扣款、退款走 gRPC（FakeBankGrpc，第 2 波 B 起），查余额、开户还是 HTTP（WireMock）；
 *   delivery（同一个 WireMock 冒充）；发邮件的 EmailAdapter（Mockito）。
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
        "outbox.processor.enabled=true"
})
@Import(TestInfraConfig.class)
public abstract class StoreIntegrationTest {

    /** store 调 bank 的超时（gRPC deadline）是 5 秒：bank.grpc.deadline-ms 的默认值。比它慢就是"超时"。 */
    protected static final int SLOWER_THAN_STORE_TIMEOUT_MS = 6_000;

    // ---------- 两个外部替身：只启动一次，所有测试类共用 ----------

    /**
     * 一次性的 Postgres。@ServiceConnection：Spring Boot 自动把 datasource 的地址、用户名、密码换成这个容器的，
     * application.properties 里的 localhost:5432/webuser 根本不会被连。
     * 用 static 块手动 start、不 stop：Spring 会把测试容器缓存起来给所有测试类复用，容器也要活得一样久（进程退出时 Testcontainers 自己清理）。
     */
    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /**
     * 假 bank（顺便也当假 delivery）。先找一个空闲端口再固定下来，不和本机的 8084 撞；
     * 固定端口是为了 bankUnreachableFor()：停掉再启动后还在同一个地址，store 的配置不用变。
     */
    protected static final WireMockServer FAKE_BANK = new WireMockServer(wireMockConfig().port(freePort()));

    /** 假 bank 的 gRPC 那一半：扣款、退款（ADR-10）。也固定在一个空闲端口上，bankUnreachableFor() 停掉再启动。 */
    protected static final FakeBankGrpc FAKE_BANK_GRPC = new FakeBankGrpc(freePort());

    static {
        POSTGRES.start();
        FAKE_BANK.start();
        FAKE_BANK_GRPC.start();
    }

    /** 把 store 配置里的 bank、delivery 地址改成假服务的地址 */
    @DynamicPropertySource
    static void externalServices(DynamicPropertyRegistry registry) {
        registry.add("bank.service.url", FAKE_BANK::baseUrl);
        registry.add("bank.grpc.address", () -> "localhost:" + FAKE_BANK_GRPC.port());
        registry.add("delivery.service.url", FAKE_BANK::baseUrl);
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

    @BeforeEach
    void resetExternalWorld() {
        // 每个测试从干净的假 bank 开始：清掉上一个测试的剧本和请求记录
        FAKE_BANK.resetAll();
        FAKE_BANK_GRPC.reset();
        // 默认剧本：查余额总是够；建配送单总是成功（不返回 id，避免 orders.delivery_id 唯一约束在测试之间撞车）
        FAKE_BANK.stubFor(get(urlPathMatching("/api/bank/account/.*/balance"))
                .willReturn(okJson("{\"success\":true,\"balance\":1000000}")));
        FAKE_BANK.stubFor(post(urlEqualTo("/api/deliveries/create")).willReturn(okJson("{}")));
        FAKE_BANK.stubFor(post(urlPathMatching("/api/deliveries/cancel-by-order/.*")).willReturn(ok()));
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
        forgetBankRequests();
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

    /** 假 bank 收到的所有扣款请求（gRPC 的 TransferRequest，按收到的先后） */
    protected List<TransferRequest> transferRequests() {
        return FAKE_BANK_GRPC.transfers();
    }

    /** 假 bank 收到的所有退款请求 */
    protected List<RefundRequest> refundRequests() {
        return FAKE_BANK_GRPC.refunds();
    }

    /** 清掉假 bank 的请求记录（剧本不动），后面只数从现在起的请求 */
    protected void forgetBankRequests() {
        FAKE_BANK.resetRequests();
        FAKE_BANK_GRPC.forgetRequests();
    }

    /** 假 delivery 收到的建配送单请求 */
    protected List<LoggedRequest> deliveryCreateRequests() {
        return FAKE_BANK.findAll(postRequestedFor(urlEqualTo("/api/deliveries/create")));
    }

    /**
     * 从 bank 请求里取出幂等键。扣款和退款的 proto 消息里都有 idempotency_key 这个字段，按字段名读，一个方法两种都能用
     * （写成两个重载的话，测试里的 StoreIntegrationTest::keyOf 会让编译器分不清用哪个）。
     */
    protected static String keyOf(Message request) {
        return (String) request.getField(request.getDescriptorForType().findFieldByName("idempotency_key"));
    }

    // ---------- 给假 bank 写剧本 ----------
    // 剧本 = 第 1 个请求怎么回、第 2 个怎么回……用完了一直用最后一条。后写的剧本覆盖前面的，所以测试中途可以"换剧本"。

    private static TransferResponse transferSucceeded() {
        return TransferResponse.newBuilder().setOutcome(Outcome.SUCCEEDED).setTransactionId("9001").build();
    }

    protected void bankTransferSucceeds() {
        FAKE_BANK_GRPC.onTransfer(Reply.ok(transferSucceeded()));
    }

    /** 成功，但慢一点（用来制造"银行处理中，用户这时候点了取消 / 又点了 Pay Now"） */
    protected void bankTransferSucceedsSlowly(int delayMs) {
        FAKE_BANK_GRPC.onTransfer(Reply.okAfter(Duration.ofMillis(delayMs), transferSucceeded()));
    }

    /**
     * 结果未知：gRPC 回 UNAVAILABLE（连接中途断开、bank 连不上都是这个码）。钱可能扣了，也可能没扣。
     * 用它代替真的等 5 秒超时，测试快很多。
     */
    protected void bankTransferResultUnknown() {
        FAKE_BANK_GRPC.onTransfer(Reply.error(Status.Code.UNAVAILABLE));
    }

    /** 前 n 次真的超时（bank 其实收到了、也处理了，只是回答比 store 的 deadline 慢），第 n+1 次起正常成功。 */
    protected void bankTransferTimesOutThenSucceeds(int timeouts) {
        Reply[] replies = new Reply[timeouts + 1];
        for (int i = 0; i < timeouts; i++) {
            replies[i] = Reply.okAfter(Duration.ofMillis(SLOWER_THAN_STORE_TIMEOUT_MS), transferSucceeded());
        }
        replies[timeouts] = Reply.ok(transferSucceeded());
        FAKE_BANK_GRPC.onTransfer(replies);
    }

    /** bank 明确拒绝：状态 OK + outcome = REJECTED，reason 写原因（bank.proto 的约定） */
    protected void bankRejectsTransfer(String reason) {
        FAKE_BANK_GRPC.onTransfer(Reply.ok(
                TransferResponse.newBuilder().setOutcome(Outcome.REJECTED).setReason(reason).build()));
    }

    /** bank 自己出错了（比如它的数据库挂了）：gRPC 的 INTERNAL，不是 bank 的"拒绝"回答。钱动没动，store 不知道。 */
    protected void bankTransferFailsInternally() {
        bankTransferFailsWith(Status.Code.INTERNAL);
    }

    /** bank 回某一个 gRPC 错误码 */
    protected void bankTransferFailsWith(Status.Code code) {
        FAKE_BANK_GRPC.onTransfer(Reply.error(code));
    }

    /** bank 说成功，但没给流水号（proto3 里没设的字符串就是空串 ""） */
    protected void bankTransferSucceedsWithoutTransactionId() {
        FAKE_BANK_GRPC.onTransfer(Reply.ok(TransferResponse.newBuilder().setOutcome(Outcome.SUCCEEDED).build()));
    }

    /** 状态 OK，但 outcome 没设（= OUTCOME_UNSPECIFIED，枚举的 0）：bank 写漏了字段 */
    protected void bankTransferAnswersWithoutOutcome() {
        FAKE_BANK_GRPC.onTransfer(Reply.ok(TransferResponse.newBuilder().setTransactionId("9001").build()));
    }

    /**
     * bank 重启了一下：现在停掉 gRPC 服务（断开所有连接），过 downtime 之后在同一个端口重新启动（剧本不变）。
     * 后台线程负责重启，测试线程同时去跑 OutboxProcessor。
     */
    protected void bankUnreachableFor(Duration downtime) {
        FAKE_BANK_GRPC.stop();
        Thread restarter = new Thread(() -> {
            try {
                Thread.sleep(downtime.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            FAKE_BANK_GRPC.start();
        });
        restarter.setDaemon(true);
        restarter.start();
    }

    /** 找一个现在没人用的端口：让操作系统分一个，记下号码，再关掉。 */
    protected static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("no free port", e);
        }
    }

    protected void bankRefundSucceeds() {
        FAKE_BANK_GRPC.onRefund(Reply.ok(
                RefundResponse.newBuilder().setOutcome(Outcome.SUCCEEDED).setRefundTransactionId("9101").build()));
    }

    protected void bankRefundResultUnknown() {
        FAKE_BANK_GRPC.onRefund(Reply.error(Status.Code.UNAVAILABLE));
    }

    /** 退款时 bank 回某一个 gRPC 错误码 */
    protected void bankRefundFailsWith(Status.Code code) {
        FAKE_BANK_GRPC.onRefund(Reply.error(code));
    }

    /** 退款：状态 OK，但 outcome 没设（OUTCOME_UNSPECIFIED） */
    protected void bankRefundAnswersWithoutOutcome() {
        FAKE_BANK_GRPC.onRefund(Reply.ok(RefundResponse.newBuilder().setRefundTransactionId("9101").build()));
    }

    protected void bankRejectsRefund(String reason) {
        FAKE_BANK_GRPC.onRefund(Reply.ok(
                RefundResponse.newBuilder().setOutcome(Outcome.REJECTED).setReason(reason).build()));
    }
}
