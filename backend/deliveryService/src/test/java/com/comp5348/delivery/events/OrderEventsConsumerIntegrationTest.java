package com.comp5348.delivery.events;

import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.payload.DeliveryEventPayload;
import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.OrderEventType;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.OrderCancelledPayload;
import com.comp5348.contracts.events.payload.OrderPlacedPayload;
import com.comp5348.contracts.events.payload.OrderRefundedPayload;
import com.comp5348.contracts.events.payload.PaymentFailedPayload;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.support.KafkaProbe;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 失败场景表（17-第2波A）B4–B9、D1（delivery 那份）：delivery 读 order-events，用 OrderPaid 建单、用 OrderCancelled 取消。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka。
 * 【测试扮演谁】store：直接往 order-events 发事件，然后看 delivery_db 和 delivery-events。
 * 【红灯为什么红】delivery 现在不读 Kafka（还在等 store 用 HTTP 调 /api/deliveries/create），没有 "delivery" 消费者组：
 *   等配送单出现、等消费者组处理完，都会等满 10 秒失败。
 */
@ExtendWith(OutputCaptureExtension.class)
class OrderEventsConsumerIntegrationTest extends DeliveryIntegrationTest {

    /**
     * B4：读到 OrderPaid → 一个事务里建配送单（CREATED）+ 写 DeliveryCreated。字段全部来自 payload，delivery 不回调 store。
     * 设计：时序图 A 最后两步；推进顺序第 5 步"delivery 改为订阅 OrderPaid 建单"。
     */
    @Test
    void orderPaid_createsDelivery_andDeliveryCreated() {
        String orderId = newOrderId();
        publishOrderEvent(orderPaid(orderId));

        awaitDeliveryRow(orderId);
        Delivery d = deliveryOf(orderId);
        assertThat(d.getDeliveryStatus()).isEqualTo(DeliveryStatus.CREATED);
        assertThat(d.getEmail()).isEqualTo("buyer-" + orderId + "@test.local");
        assertThat(d.getUserName()).isEqualTo("buyer-" + orderId);
        assertThat(d.getToAddress()).isEqualTo("1 Test Street");
        assertThat(d.getProductName()).isEqualTo("Test Phone");
        assertThat(d.getQuantity()).isEqualTo(1);
        assertThat(awaitEventTypes(orderId, 1)).containsExactly("DeliveryCreated");
    }

    /**
     * B5：OrderPaid 收到两次（同一条重复 + 一条新 eventId 的）。orderId 唯一约束：只有一张配送单、一条 DeliveryCreated。
     * 设计：规则 4"delivery：配送单的 orderId 加唯一约束"。
     */
    @Test
    void duplicateOrderPaid_createsOneDelivery() throws Exception {
        String orderId = newOrderId();
        EventEnvelope paid = orderPaid(orderId);
        publishOrderEvent(paid);
        publishOrderEvent(paid);
        RecordMetadata last = publishOrderEvent(orderPaid(orderId));
        awaitConsumedByDelivery(last);

        assertThat(rowsOf(orderId)).isEqualTo(1);
        Thread.sleep(1_000); // 给投递程序时间：如果真有第二条 DeliveryCreated，这时它也该发出来了
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated");
    }

    /**
     * B6：取消先到（取消标记），OrderPaid 迟到 → 跳过，不建单。
     * 设计：时序图 C"orderId 已存在且是 CANCELLED，跳过，不建单"。
     * 【4b 审查 #2 改过期望】以前断言"只有一条 Cancelled"。现在 OrderPaid 撞上 CANCELLED 行时，delivery 要再发一条
     *   Cancelled（见下面 B13）：store 那边如果订单已经取消，会按"状态不倒退"跳过它，所以正常情况下多一条无害。
     */
    @Test
    void orderPaid_afterCancelMarker_isSkipped() throws Exception {
        String orderId = newOrderId();
        mvc().perform(post("/internal/deliveries/cancel-by-order/" + orderId)); // 先取消（这时配送单还不存在）

        RecordMetadata late = publishOrderEvent(orderPaid(orderId));
        awaitConsumedByDelivery(late);

        assertThat(rowsOf(orderId)).isEqualTo(1);
        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        Thread.sleep(1_000);
        assertThat(awaitEventTypes(orderId, 2)).as("取消标记的 Cancelled + 撞上标记后再发的 Cancelled，没有 DeliveryCreated")
                .containsExactly("Cancelled", "Cancelled");
    }

    /**
     * B13（4b 审查 #2，钱）：取消标记在订单付款之前就被插进来了（/internal/... 没有鉴权，订单号是自增的，可以猜；
     * 或者标记发出的 Cancelled 在 store 那边因为订单还不存在进了 DLT）。之后这单真的付了款 → OrderPaid 撞上标记 → 不建单。
     * 应该怎样：delivery 这时【再发一条】Cancelled（deliveryId 为空，和取消标记一样）。
     *   store 收到时订单是 PAID → 按 A11 补完取消 + 退款；订单已经取消 → 状态不倒退，跳过。
     * 旧代码错在哪：只打一行 INFO 就跳过。标记的那条 Cancelled 早就被 store 丢掉了（订单当时不存在或还没付款），
     *   之后再没有任何事件告诉 store → 订单永远停在 PAID：不发货，也不退款。
     */
    @Test
    void orderPaid_hittingAnEarlierCancelMarker_publishesCancelledAgain() throws Exception {
        String orderId = newOrderId();
        mvc().perform(post("/internal/deliveries/cancel-by-order/" + orderId)); // 订单还没付款（甚至还不存在）就被插了标记
        awaitEventTypes(orderId, 1); // 标记的 Cancelled 已经发出去了（store 当时处理不了它）

        RecordMetadata paid = publishOrderEvent(orderPaid(orderId));
        awaitConsumedByDelivery(paid);

        assertThat(rowsOf(orderId)).as("还是不建单").isEqualTo(1);
        awaitEventTypes(orderId, 2); // 红灯时等满 10 秒还是只有 1 条
        List<EventEnvelope> events = kafka.recordsOfOrder(Topics.DELIVERY_EVENTS, orderId).stream().map(r -> parse(r)).toList();
        assertThat(events).extracting(EventEnvelope::eventType).containsExactly("Cancelled", "Cancelled");
        DeliveryEventPayload again = EventCodec.payload(events.get(1), DeliveryEventPayload.class);
        assertThat(again.deliveryId()).as("和取消标记一样不带配送单号：store 不拿它和订单比").isNull();
    }

    /**
     * B8：OrderCancelled（未付款就取消的订单）在配送单的四种情况下怎么处理。
     * 设计：取消接口下方的列表 ——
     *   没有这一行 → 插入取消标记，挡住以后可能出现的 OrderPaid；
     *   CREATED → 改为 CANCELLED（正常不会出现，兜底）；
     *   CANCELLED → 什么都不做；
     *   PICKED_UP 及以后 → 不改，ALERT（两边状态不一致）。
     */
    @Test
    void orderCancelled_handlesEachDeliveryState(CapturedOutput output) throws Exception {
        // 1. 没有这一行
        String none = newOrderId();
        awaitConsumedByDelivery(publishOrderEvent(cancelled(none)));
        assertThat(deliveryOf(none).getDeliveryStatus()).as("插入了取消标记").isEqualTo(DeliveryStatus.CANCELLED);
        awaitConsumedByDelivery(publishOrderEvent(orderPaid(none)));
        assertThat(rowsOf(none)).as("标记挡住了之后的 OrderPaid").isEqualTo(1);
        assertThat(deliveryOf(none).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);

        // 2. CREATED
        String created = newOrderId();
        createDelivery(created);
        awaitConsumedByDelivery(publishOrderEvent(cancelled(created)));
        assertThat(deliveryOf(created).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);

        // 3. 已经 CANCELLED：不再写第二条 Cancelled
        awaitConsumedByDelivery(publishOrderEvent(cancelled(created)));
        Thread.sleep(1_000);
        assertThat(eventTypesOf(created)).containsExactly("DeliveryCreated", "Cancelled");

        // 4. PICKED_UP：不改，告警
        String pickedUp = newOrderId();
        Long id = createDelivery(pickedUp);
        worker.processDelivery(id);
        assertThat(deliveryOf(pickedUp).getDeliveryStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        awaitConsumedByDelivery(publishOrderEvent(cancelled(pickedUp)));
        assertThat(deliveryOf(pickedUp).getDeliveryStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
        assertThat(output.getAll()).contains("ALERT").contains("orderId=" + pickedUp);
    }

    /**
     * B9：delivery 不关心的事件（OrderPlaced、PaymentFailed、OrderRefunded，以及以后新加的类型）照常读过去，
     * 不建单，也不进 DLT。设计：交互表 #5"处理 OrderPaid 和 OrderCancelled，其余忽略"。
     */
    @Test
    void irrelevantOrderEvents_areIgnored() {
        String orderId = newOrderId();
        long id = Long.parseLong(orderId);
        publishOrderEvent(orderEvent(orderId, OrderEventType.ORDER_PLACED,
                new OrderPlacedPayload(1L, 1L, 1, new BigDecimal("6999.00"))));
        publishOrderEvent(orderEvent(orderId, OrderEventType.PAYMENT_FAILED, new PaymentFailedPayload(id, 1, "no money")));
        publishOrderEvent(orderEvent(orderId, OrderEventType.ORDER_REFUNDED,
                new OrderRefundedPayload(id, new BigDecimal("6999.00"), "9101", "ORDER_CANCELLED")));
        RecordMetadata last = kafka.send(Topics.ORDER_EVENTS, orderId, """
                {"eventId":"5b2f9f9e-0000-4000-8000-0000000000b9","eventType":"OrderTeleported","orderId":"%s",
                 "occurredAt":"2026-10-20T00:00:00Z","payload":{}}""".formatted(orderId));
        awaitConsumedByDelivery(last);

        assertThat(rowsOf(orderId)).isZero();
        assertThat(kafka.recordsOfOrder(Topics.deadLetter(Topics.ORDER_EVENTS), orderId)).isEmpty();
    }

    /**
     * D1（delivery 那份）：order-events 里的坏消息进 order-events.DLT（同一分区号），后面同一分区的 OrderPaid 照常建单。
     * 设计：规则 6，"store、delivery、tracking 的消费者都用这条规则"。
     */
    @Test
    void poisonMessage_goesToOrderEventsDlt_andNextMessageStillProcessed() {
        String orderId = newOrderId();
        RecordMetadata poison = kafka.send(Topics.ORDER_EVENTS, orderId, "this is not json");
        publishOrderEvent(orderPaid(orderId));

        awaitDeliveryRow(orderId);
        ConsumerRecord<String, String> dead = kafka.awaitRecord(Topics.deadLetter(Topics.ORDER_EVENTS),
                r -> orderId.equals(r.key()), EVENT_TIMEOUT);
        assertThat(dead.value()).isEqualTo("this is not json");
        assertThat(dead.partition()).isEqualTo(poison.partition());
        assertThat(KafkaProbe.header(dead, "kafka_dlt-original-topic")).isEqualTo(Topics.ORDER_EVENTS);
    }

    private static EventEnvelope cancelled(String orderId) {
        return orderEvent(orderId, OrderEventType.ORDER_CANCELLED, new OrderCancelledPayload("USER_REQUEST"));
    }
}
