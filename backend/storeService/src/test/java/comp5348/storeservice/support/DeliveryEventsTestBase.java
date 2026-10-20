package comp5348.storeservice.support;

import com.comp5348.contracts.events.DeliveryEventType;
import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.DeliveryEventPayload;
import comp5348.storeservice.model.PaymentOutbox;
import comp5348.storeservice.service.OrderService;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;

/**
 * store 消费 delivery-events 的测试共用的工具（第 2 波 A · 4a）。
 *
 * 测试扮演 delivery：用 KafkaProbe 直接往 delivery-events 发事件（格式用契约的 EventCodec），
 * 然后看 store 的数据库变成了什么样。store 自己的消费者在后台跑，测试不直接调它——红灯时它还不存在。
 */
public abstract class DeliveryEventsTestBase extends StoreIntegrationTest {

    /** 契约：store 的消费者组 id */
    protected static final String STORE_GROUP = "store";

    /** 等 store 处理完一条事件最多等多久。红灯时没有消费者，会等满再失败。 */
    protected static final Duration CONSUME_TIMEOUT = Duration.ofSeconds(10);

    @Autowired protected OrderService orderService;

    // ---------- 扮演 delivery：造事件、发事件 ----------

    /**
     * 给这一单编一个配送单号。订单表的 delivery_id 有唯一约束，所以每单不同；加一个大数，不和真的 id 撞。
     */
    protected static long deliveryIdOf(PlacedOrder order) {
        return 900_000_000L + order.orderId();
    }

    /** 一条 delivery-events 事件：外壳 + payload（deliveryId、status），和 delivery 真发出来的一样 */
    protected static EventEnvelope deliveryEvent(PlacedOrder order, DeliveryEventType type) {
        return deliveryEvent(order.orderId().toString(), type, deliveryIdOf(order));
    }

    protected static EventEnvelope deliveryEvent(String orderId, DeliveryEventType type, Long deliveryId) {
        return new EventEnvelope(UUID.randomUUID(), type.wireName(), orderId, Instant.now(),
                EventCodec.toTree(new DeliveryEventPayload(deliveryId, type.deliveryStatus())));
    }

    /** 发到 delivery-events，key = orderId（规则 5），等 Kafka 确认，返回它的分区和 offset */
    protected static RecordMetadata publish(EventEnvelope event) {
        return kafka.send(Topics.DELIVERY_EVENTS, event.orderId(), EventCodec.write(event));
    }

    protected static RecordMetadata publish(PlacedOrder order, DeliveryEventType type) {
        return publish(deliveryEvent(order, type));
    }

    // ---------- 等 store 处理 ----------

    /** 等订单状态变成某个值。用名字比较：LOST、REFUNDED 在红灯时还不是 OrderStatus 的值，写成枚举编译不过 */
    protected void awaitOrderStatus(PlacedOrder order, String status) {
        awaitOrderStatus(order, status, CONSUME_TIMEOUT);
    }

    protected void awaitOrderStatus(PlacedOrder order, String status, Duration timeout) {
        await().atMost(timeout).pollInterval(Duration.ofMillis(200))
                .until(() -> orderOf(order).getStatus().name().equals(status));
    }

    /** 等 store 的消费者组处理完这条事件（它的数据库事务已提交，见 KafkaProbe.awaitConsumed） */
    protected void awaitConsumedByStore(RecordMetadata record) {
        kafka.awaitConsumed(STORE_GROUP, record, CONSUME_TIMEOUT);
    }

    /** 等 delivery-events.DLT 里出现这个订单的消息 */
    protected ConsumerRecord<String, String> awaitDeadLetter(String orderId, Duration timeout) {
        return kafka.awaitRecord(Topics.deadLetter(Topics.DELIVERY_EVENTS), r -> orderId.equals(r.key()), timeout);
    }

    // ---------- 读 order-events（4b：store 自己发的事件） ----------

    /** 这一单在 order-events 里的事件类型，按 offset 先后 */
    protected List<String> orderEventTypesOf(PlacedOrder order) {
        return orderEventsOf(order).stream().map(EventEnvelope::eventType).toList();
    }

    /** 这一单在 order-events 里的全部事件（解析好的外壳） */
    protected List<EventEnvelope> orderEventsOf(PlacedOrder order) {
        return kafka.recordsOfOrder(Topics.ORDER_EVENTS, order.orderId().toString()).stream()
                .map(r -> {
                    try {
                        return EventCodec.parse(r.value());
                    } catch (Exception e) {
                        throw new AssertionError("store published an event that does not match the contract: " + r.value(), e);
                    }
                })
                .toList();
    }

    /** 等到这一单在 order-events 里至少有 n 条事件，返回类型。红灯时 store 不发事件，会等满后失败。 */
    protected List<String> awaitOrderEventTypes(PlacedOrder order, int n) {
        await().atMost(CONSUME_TIMEOUT).pollInterval(Duration.ofMillis(300))
                .until(() -> orderEventTypesOf(order).size() >= n);
        return orderEventTypesOf(order);
    }

    /** 这一单某种事件的 payload，转成契约里的 record */
    protected <T> T payloadOf(PlacedOrder order, String eventType, Class<T> type) {
        EventEnvelope event = orderEventsOf(order).stream().filter(e -> e.eventType().equals(eventType))
                .findFirst().orElseThrow(() -> new AssertionError("no " + eventType + " for order " + order.orderId()));
        try {
            return EventCodec.payload(event, type);
        } catch (Exception e) {
            throw new AssertionError("bad " + eventType + " payload: " + event.payload(), e);
        }
    }

    // ---------- 读结果 ----------

    /** 这一单的全部 REFUND 任务（不管做没做完）：用来数"有没有写第二条退款" */
    protected List<PaymentOutbox> refundTasksOf(PlacedOrder order) {
        return tasksOf(order).stream().filter(t -> "REFUND".equals(t.getEventType())).toList();
    }

    /** 这一单那件商品在仓库里的可用数量（下单锁库存后是 99，还回去是 100） */
    protected int stockOf(PlacedOrder order) {
        return warehouseProductRepository.findById(order.warehouseProductId()).orElseThrow().getQuantity();
    }
}
