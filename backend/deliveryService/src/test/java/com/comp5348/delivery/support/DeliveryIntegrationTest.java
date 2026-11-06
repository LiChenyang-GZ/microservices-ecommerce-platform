package com.comp5348.delivery.support;

import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.Topics;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.service.DeliveryService;
import com.comp5348.delivery.service.messaging.DeliveryStatusUpdateService;
import com.comp5348.contracts.events.OrderEventType;
import com.comp5348.contracts.events.payload.OrderPaidPayload;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.PostgreSQLContainer;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.kafka.KafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * delivery 集成测试的公共底座（第 2 波 A 新加；delivery 以前只有一个契约单元测试）。
 *
 * 【这一层是什么】集成测试：真的 Spring 容器 + 真的 Postgres + 真的 Kafka，都由 Testcontainers 起在 Docker 里。
 *   镜像和 compose 用的一样（postgres:16-alpine、apache/kafka:3.9.1），本机已经有，不用重新下载。
 * 【真的】delivery 的全部代码、事务、数据库约束；Kafka 的分区和 offset。
 * 【假的】RabbitMQ：RabbitTemplate 换成 Mockito 假对象，后台的配送 worker 监听器不启动。
 *   要推进一步配送，测试自己调 worker 的 processDelivery(id)，这样每一步什么时候发生是确定的。
 *
 * 【测试怎么看结果】只看外面能看到的东西：数据库里的配送单状态，和 delivery-events 这个 topic 里真的有什么消息。
 *   不直接调"事件投递程序"——它在红灯阶段还不存在；它应该自己在后台定时跑（规则 3）。
 */
@SpringBootTest(properties = {
        // application.properties 里这些值来自环境变量；测试里给假的，避免依赖本机的 .env
        "DELIVERY_DB_PASSWORD=unused-overridden-by-testcontainers",
        "RABBITMQ_USERNAME=guest",
        "RABBITMQ_PASSWORD=guest",
        // 指向没人监听的端口，并且不启动 @RabbitListener：配送 worker 只在测试调用时才跑
        "spring.rabbitmq.port=1",
        "spring.rabbitmq.listener.simple.auto-startup=false",
        // 下面两个是给绿灯实现的配置键（红灯时没人读，用的是代码里写死的值）：
        // 事件投递程序每 200 毫秒跑一轮，测试不用等 5 秒
        "event-outbox.publisher.interval-ms=200",
        // 模拟配送每一步等 1 秒（现在写死 10 秒，红灯时测试会慢一些，但结果一样）
        "delivery.simulation.step-delay-ms=1000",
        // 集成发现 I1 的绿灯配置键（红灯时没人读）：不是终态、2 秒没动的配送单算"卡住"，每 500 毫秒找一次
        // 2 秒 > 上面的每步 1 秒，正常推进中的单不会被当成卡住
        "delivery.stuck-job.stale-after-ms=2000",
        "delivery.stuck-job.check-interval-ms=500"
})
public abstract class DeliveryIntegrationTest {

    /** 测试里"等一条事件出现"最多等多久。红灯时根本没有事件，每个测试会在这里等满再失败。 */
    protected static final Duration EVENT_TIMEOUT = Duration.ofSeconds(10);

    /**
     * 设计规则 3 的"固定键"：投递程序每一轮先 pg_try_advisory_xact_lock(这个键)。
     * 规则只说"固定键"，具体数字在这里定下来，绿灯实现要用同一个。
     */
    protected static final long PUBLISHER_LOCK_KEY = 5348L;

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    /** 和 compose 一样关掉自动建 topic：名字写错就直接报错，而不是悄悄建一个空 topic */
    protected static final KafkaContainer KAFKA = new KafkaContainer("apache/kafka:3.9.1")
            .withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

    protected static final KafkaProbe kafka;

    static {
        // static 块里手动 start、不 stop：Spring 把测试容器缓存起来给所有测试类复用，容器也要活得一样久
        POSTGRES.start();
        KAFKA.start();
        kafka = new KafkaProbe(KAFKA.getBootstrapServers());
        kafka.createTopics(KafkaProbe.ALL_TOPICS);
    }

    /** 把 Spring Kafka 的地址指到测试容器（契约：SPRING_KAFKA_BOOTSTRAP_SERVERS 自动绑定到这个键） */
    @DynamicPropertySource
    static void kafkaAddress(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    /** RabbitMQ 不是这一步要测的：发作业到队列的调用全部变成"什么都不做" */
    @MockitoBean
    protected RabbitTemplate rabbitTemplate;

    @Autowired protected DeliveryService deliveryService;
    @Autowired protected DeliveryStatusUpdateService worker;
    @Autowired protected DeliveryRepository deliveryRepository;
    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected WebApplicationContext web;

    /** 契约：delivery 的消费者组 id */
    protected static final String DELIVERY_GROUP = "delivery";

    /** MockMvc：直接对 Spring MVC 发 HTTP 请求，不经过真的网络（测取消接口用） */
    protected MockMvc mvc() {
        return MockMvcBuilders.webAppContextSetup(web).build();
    }

    // ---------- 扮演 store：往 order-events 发事件（4b） ----------

    /** 一条 OrderPaid：payload 带着建配送单要的全部字段（契约 OrderPaidPayload），delivery 不用回调 store */
    protected static EventEnvelope orderPaid(String orderId) {
        return new EventEnvelope(UUID.randomUUID(), OrderEventType.ORDER_PAID.wireName(), orderId, Instant.now(),
                EventCodec.toTree(new OrderPaidPayload(Long.parseLong(orderId), new BigDecimal("6999.00"), "9001",
                        "buyer-" + orderId + "@test.local", "buyer-" + orderId, "1 Test Street",
                        List.of("Warehouse-1, 456 Storage Rd"), "Test Phone", 1)));
    }

    /** 某种 order-events 事件，payload 用给定的 JSON 对象（OrderCancelled 等） */
    protected static EventEnvelope orderEvent(String orderId, OrderEventType type, Object payload) {
        return new EventEnvelope(UUID.randomUUID(), type.wireName(), orderId, Instant.now(), EventCodec.toTree(payload));
    }

    /** 发到 order-events，key = orderId，等 Kafka 确认 */
    protected static RecordMetadata publishOrderEvent(EventEnvelope event) {
        return kafka.send(Topics.ORDER_EVENTS, event.orderId(), EventCodec.write(event));
    }

    /** 等 delivery 的消费者组处理完这条（它的数据库事务已提交）。红灯时没有这个组，会等满后失败。 */
    protected static void awaitConsumedByDelivery(RecordMetadata record) {
        kafka.awaitConsumed(DELIVERY_GROUP, record, EVENT_TIMEOUT);
    }

    /** 等这个订单在 delivery_db 里有一行 */
    protected void awaitDeliveryRow(String orderId) {
        await().atMost(EVENT_TIMEOUT).pollInterval(Duration.ofMillis(200))
                .until(() -> deliveryRepository.findByOrderId(orderId).isPresent());
    }

    /** 这个订单在 delivery 表里有几行（唯一约束要保证最多 1 行） */
    protected int rowsOf(String orderId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM delivery WHERE order_id = ?", Integer.class, orderId);
        return n == null ? 0 : n;
    }

    // ---------- 造数据 ----------

    /** 每个测试用一个新的订单号，测试之间的事件互不干扰（topic 是所有测试共用的） */
    protected static String newOrderId() {
        return String.valueOf(Math.abs(UUID.randomUUID().getMostSignificantBits() % 1_000_000_000L));
    }

    /** 建一张配送单（走现有的 DeliveryService.createDelivery，状态 CREATED） */
    protected Long createDelivery(String orderId) {
        DeliveryRequest request = new DeliveryRequest();
        request.setOrderId(orderId);
        request.setEmail("buyer-" + orderId + "@test.local");
        request.setUserName("buyer-" + orderId);
        request.setToAddress("1 Test Street");
        request.setFromAddress(List.of("Warehouse-1, 456 Storage Rd"));
        request.setProductName("Test Phone");
        request.setQuantity(1);
        deliveryService.createDelivery(request);
        return deliveryRepository.findByOrderId(orderId).map(Delivery::getId).orElseThrow();
    }

    protected Delivery deliveryOf(String orderId) {
        return deliveryRepository.findByOrderId(orderId).orElseThrow();
    }

    // ---------- 读 delivery-events ----------

    /** 这个订单在 delivery-events 里的事件类型，按 offset 先后（同一订单同一分区，先后就是发送顺序） */
    protected List<String> eventTypesOf(String orderId) {
        return kafka.recordsOfOrder(Topics.DELIVERY_EVENTS, orderId).stream()
                .map(r -> parse(r).eventType())
                .toList();
    }

    /** 等到这个订单在 delivery-events 里至少有 n 条事件，返回它们的类型 */
    protected List<String> awaitEventTypes(String orderId, int n) {
        await().atMost(EVENT_TIMEOUT).pollInterval(Duration.ofMillis(300))
                .until(() -> eventTypesOf(orderId).size() >= n);
        return eventTypesOf(orderId);
    }

    /** 用契约的 EventCodec 解析消息；解析不了说明 delivery 发出去的就是坏消息，测试直接失败 */
    protected static EventEnvelope parse(ConsumerRecord<String, String> record) {
        try {
            return EventCodec.parse(record.value());
        } catch (Exception e) {
            throw new AssertionError("delivery published an event that does not match the contract: " + record.value(), e);
        }
    }
}
