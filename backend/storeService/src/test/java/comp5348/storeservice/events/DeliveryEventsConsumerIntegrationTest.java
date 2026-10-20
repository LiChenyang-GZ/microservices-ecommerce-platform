package comp5348.storeservice.events;

import com.comp5348.contracts.events.EventEnvelope;
import comp5348.storeservice.model.OrderStatus;
import comp5348.storeservice.model.PaymentStatus;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static com.comp5348.contracts.events.DeliveryEventType.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 失败场景表（17-第2波A）A3–A14、A16：store 读 delivery-events，把订单状态改对。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka + 假 bank（WireMock）+ 假邮件（Mockito）。
 * 【测试扮演谁】delivery：直接往 delivery-events 发事件，然后看 store 的订单、退款任务、库存。
 * 【红灯为什么红】store 现在不读 Kafka（还在等 webhook），没有 "store" 这个消费者组。
 *   等订单状态变化的测试会等满 10 秒失败；"什么都不该发生"的测试会在 awaitConsumedByStore 等满 10 秒失败。
 */
@ExtendWith(OutputCaptureExtension.class)
class DeliveryEventsConsumerIntegrationTest extends DeliveryEventsTestBase {

    /**
     * A3：配送进度经 Kafka 推进订单：揽收 → 运输 → 送达。
     * 设计：推进顺序第 4 步（替换 webhook）；状态机：PickedUp→PICKED_UP、Delivering→IN_TRANSIT、Received→DELIVERED。
     */
    @Test
    void deliveryEvents_moveOrder_pickedUpToDelivered() {
        PlacedOrder order = placePaidOrder();

        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        awaitOrderStatus(order, "PICKED_UP");

        publish(order, DELIVERING);
        awaitOrderStatus(order, "IN_TRANSIT");

        publish(order, RECEIVED);
        awaitOrderStatus(order, "DELIVERED");
    }

    /**
     * A6：DeliveryCreated 到了 → 记下配送单号（之后的邮件要用），订单仍是 PAID（建单不是订单状态的变化）。
     */
    @Test
    void deliveryCreated_savesDeliveryId_orderStaysPaid() {
        PlacedOrder order = placePaidOrder();

        RecordMetadata created = publish(order, DELIVERY_CREATED);
        awaitConsumedByStore(created);

        assertThat(orderOf(order).getDeliveryId()).isEqualTo(deliveryIdOf(order));
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    /**
     * A4 + A16：同一条事件收到两次（同一个 eventId）。第二次跳过，揽收邮件只发一封。
     * 设计：规则 4"store 靠状态不倒退跳过重复的事件"。
     * 邮件在事务提交后才发（A16），所以不会因为消费者重试而多发。
     */
    @Test
    void duplicateEvent_isSkipped_andEmailSentOnce() {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);

        EventEnvelope pickedUp = deliveryEvent(order, PICKED_UP);
        publish(pickedUp);
        RecordMetadata again = publish(pickedUp); // 一模一样的消息再来一次
        awaitConsumedByStore(again);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        // verify(..., times(1))：Mockito 检查这个方法正好被调了 1 次
        verify(emailAdapter, times(1))
                .sendOrderPickedUp(order.buyerEmail(), order.orderId().toString(), deliveryIdOf(order));
    }

    /**
     * A5：旧状态迟到。订单已经送达，又收到一条"运输中"。不能倒退回 IN_TRANSIT。
     * 设计：规则 5"配送状态等级 CREATED < PICKED_UP < DELIVERING < 终态，收到的状态不高于当前状态就跳过"。
     */
    @Test
    void olderStatus_afterNewer_isIgnored() {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        publish(order, DELIVERING);
        publish(order, RECEIVED);
        awaitOrderStatus(order, "DELIVERED");

        RecordMetadata late = publish(order, DELIVERING);
        awaitConsumedByStore(late);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.DELIVERED);
        // "运输中"的邮件只在第一次进入 IN_TRANSIT 时发过一次
        verify(emailAdapter, times(1)).sendOrderDelivering(anyString(), anyString(), anyLong());
    }

    /**
     * A7：丢件 → 一个事务里：订单 LOST + 写一条 REFUND 任务。消费者自己不调 bank。
     * 设计：时序图 B；规则 1"全系统只有 OutboxProcessor 调 bank"（架构测试 BankAccessArchitectureTest 也管着新消费者）。
     */
    @Test
    void lost_marksOrderLost_andWritesOneRefundTask() {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        publish(order, LOST);

        awaitOrderStatus(order, "LOST");
        // 断言 1：正好一条待处理的 REFUND 任务，Payment 进入"等退款"
        assertThat(openTasksOf(order, "REFUND")).hasSize(1);
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        // 断言 2：消费者没有直接调 bank（OutboxProcessor 在测试里不会自己跑，所以 bank 一次退款请求都不该收到）
        assertThat(refundRequests()).isEmpty();
    }

    /**
     * A8：Lost 收到两次（一次是同一条消息重复，一次是 delivery 重发了一条新的 Lost）。只能有一条退款任务。
     * 设计：时序图 B 图注"订单已经是 LOST，不满足状态前进，直接跳过，不会写第二条退款任务"。
     */
    @Test
    void duplicateLost_writesNoSecondRefund() {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        EventEnvelope lost = deliveryEvent(order, LOST);
        publish(lost);
        publish(lost);
        RecordMetadata last = publish(order, LOST);
        awaitConsumedByStore(last);

        assertThat(orderOf(order).getStatus().name()).isEqualTo("LOST");
        assertThat(refundTasksOf(order)).hasSize(1);
    }

    /**
     * A9（丢件那半）：丢件后退款成功 → Payment REFUNDED，订单 LOST → REFUNDED。
     * 设计：时序图 B 最后一步"事务：Payment REFUNDED，订单 REFUNDED，写 OrderRefunded"（OrderRefunded 是 4b 的事）。
     */
    @Test
    void refundAfterLost_orderBecomesRefunded() {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        publish(order, LOST);
        awaitOrderStatus(order, "LOST");

        bankRefundSucceeds();
        runOutboxRounds(2); // OutboxProcessor 执行 REFUND 任务

        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(orderOf(order).getStatus().name()).isEqualTo("REFUNDED");
    }

    /**
     * A9（取消那半）：取消后退款成功，订单保持 CANCELLED（状态机里 REFUNDED 只从 LOST 来）。
     * 【注意】这个测试红灯时就是绿的：它防的是绿灯实现"一退款就把订单改成 REFUNDED"，把取消的订单也改掉。
     */
    @Test
    void refundAfterCancel_orderStaysCancelled() {
        PlacedOrder order = placePaidOrder();
        orderService.cancelOrder(order.orderId());

        bankRefundSucceeds();
        runOutboxRounds(2);

        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUNDED);
        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
    }

    /**
     * A10：丢件不还库存（货已经发出去了）。下单时锁了 1 件（100 → 99），丢件后还是 99。
     * 设计：时序图 B 图注"丢件不回滚库存"。
     */
    @Test
    void lost_doesNotReleaseStock() {
        PlacedOrder order = placePaidOrder();
        int stockBefore = stockOf(order);
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        publish(order, LOST);
        awaitOrderStatus(order, "LOST");

        assertThat(stockOf(order)).isEqualTo(stockBefore);
    }

    /**
     * A11：订单还是 PAID，却收到了 Cancelled（store 调完取消接口、提交前崩了；或调取消超时、delivery 其实已经取消了；
     * 或用户在配送详情页直接点了取消）。store 要把取消补完：订单 CANCELLED、还库存、写 REFUND 任务。
     * 设计：时序图 C 图注"之后 store 会从 delivery-events 读到 Cancelled，再把取消补完（同一套事务）"。
     * 旧代码对照：webhook 收到 CANCELLED 只改订单状态，不退款、不还库存 —— 钱和货都没回来。
     */
    @Test
    void cancelledEvent_onPaidOrder_completesTheCancel() {
        PlacedOrder order = placePaidOrder();
        int stockBefore = stockOf(order);
        publish(order, DELIVERY_CREATED);
        publish(order, CANCELLED);

        awaitOrderStatus(order, "CANCELLED");
        assertThat(openTasksOf(order, "REFUND")).as("要退款").hasSize(1);
        assertThat(paymentOf(order).getStatus()).isEqualTo(PaymentStatus.REFUND_PENDING);
        assertThat(stockOf(order)).as("锁住的 1 件要还回去").isEqualTo(stockBefore + 1);
    }

    /**
     * A11 的另一种：Cancelled 来自取消标记（delivery 还没建单，deliveryId 为 null，契约允许）。结果一样要补完取消。
     */
    @Test
    void cancelMarkerEvent_onPaidOrder_completesTheCancel() {
        PlacedOrder order = placePaidOrder();
        publish(deliveryEvent(order.orderId().toString(), CANCELLED, null));

        awaitOrderStatus(order, "CANCELLED");
        assertThat(openTasksOf(order, "REFUND")).hasSize(1);
    }

    /**
     * A12：订单已经被 store 自己取消了（正常路径），随后收到 delivery 的 Cancelled。什么都不做：
     * 不写第二条退款任务，库存也不能再还一次。
     */
    @Test
    void cancelledEvent_onCancelledOrder_doesNothing() {
        PlacedOrder order = placePaidOrder();
        orderService.cancelOrder(order.orderId()); // 已取消、已还库存、已写一条 REFUND
        int stockAfterCancel = stockOf(order);

        RecordMetadata cancelled = publish(order, CANCELLED);
        awaitConsumedByStore(cancelled);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.CANCELLED);
        assertThat(refundTasksOf(order)).hasSize(1);
        assertThat(stockOf(order)).isEqualTo(stockAfterCancel);
    }

    /**
     * A17（4a 审查 #1，钱）：货已经揽收了，却收到 Cancelled。不能补完取消：
     * 订单留在 PICKED_UP，不写退款、不还库存，打 ALERT（要人去看），不进 DLT（消息本身没坏）。
     * 设计：A11 只写了"订单【还是 PAID】时补完取消"；揽收之后来 Cancelled 是不可能的跳转（同 A14 的处理）。
     * 旧代码错在哪：Cancelled 的等级是终态 3，比 PICKED_UP 的 1 高，"状态不倒退"就放行了 → 货在路上，钱退了。
     */
    @Test
    void cancelledEvent_afterPickedUp_isSkippedWithAlert(CapturedOutput output) {
        PlacedOrder order = placePaidOrder();
        publish(order, DELIVERY_CREATED);
        publish(order, PICKED_UP);
        awaitOrderStatus(order, "PICKED_UP");
        int stockBefore = stockOf(order);

        RecordMetadata cancelled = publish(order, CANCELLED);
        awaitConsumedByStore(cancelled);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(refundTasksOf(order)).as("货在路上，不能退款").isEmpty();
        assertThat(stockOf(order)).as("货已经出库，不能还库存").isEqualTo(stockBefore);
        // 同一行里既有 ALERT 又有这一单的号（\b：后面不能紧跟数字，避免 orderId=12 匹配到 orderId=123）
        assertThat(output.getAll()).containsPattern("ALERT.*orderId=" + order.orderId() + "\\b");
        assertThat(kafka.recordsOfOrder("delivery-events.DLT", order.orderId().toString())).isEmpty();
    }

    /**
     * A17 的另一种（4a 审查 #1）：订单还是 PAID，但 Cancelled 里的配送单号和订单记下的不是同一个。
     * 这不是"这一单的配送被取消了"，而是别的配送单（4b 以后 delivery 一单只能有一个配送单，正常不会出现）。
     * 处理：不补完取消，ALERT + 跳过。取消标记的 deliveryId 是 null，那种照 A11 补完（见上面 cancelMarkerEvent_*）。
     */
    @Test
    void cancelledEvent_forAnotherDeliveryId_isSkippedWithAlert(CapturedOutput output) {
        PlacedOrder order = placePaidOrder();
        RecordMetadata created = publish(order, DELIVERY_CREATED);
        awaitConsumedByStore(created); // 订单记下了 deliveryIdOf(order)
        int stockBefore = stockOf(order);

        RecordMetadata cancelled = publish(deliveryEvent(order.orderId().toString(), CANCELLED, deliveryIdOf(order) + 1));
        awaitConsumedByStore(cancelled);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(refundTasksOf(order)).isEmpty();
        assertThat(stockOf(order)).isEqualTo(stockBefore);
        assertThat(output.getAll()).containsPattern("ALERT.*orderId=" + order.orderId() + "\\b");
    }

    /**
     * A13：事件里的订单在 store 根本不存在 → 消息本身有问题，进 delivery-events.DLT。
     * 设计：规则 6"消息本身有问题，例如……引用的订单不存在：直接进 .DLT 并告警"。
     */
    @Test
    void eventForUnknownOrder_goesToDlt() {
        String unknownOrderId = "987654321987";
        RecordMetadata sent = publish(deliveryEvent(unknownOrderId, PICKED_UP, 1L));

        ConsumerRecord<String, String> dead = awaitDeadLetter(unknownOrderId, CONSUME_TIMEOUT);
        // Spring 发 DLT 时默认写到同一个分区号（契约说明：所以 .DLT 也是 3 分区）
        assertThat(dead.partition()).isEqualTo(sent.partition());
    }

    /**
     * A14（🟠 设计没写，Christy 同意按默认）：不可能的跳转。订单还没付款，却收到"已揽收"。
     * 处理：跳过 + ALERT 日志，不进 DLT（消息没坏，是两边状态对不上，要人去看）。
     */
    @Test
    void impossibleTransition_isSkippedWithAlert(CapturedOutput output) {
        PlacedOrder order = placeOrder(); // 只下单，不付款：PENDING_PAYMENT

        RecordMetadata sent = publish(order, PICKED_UP);
        awaitConsumedByStore(sent);

        assertThat(orderOf(order).getStatus()).isEqualTo(OrderStatus.PENDING_PAYMENT);
        assertThat(output.getAll()).contains("ALERT").contains("orderId=" + order.orderId());
        assertThat(kafka.recordsOfOrder("delivery-events.DLT", order.orderId().toString())).isEmpty();
    }
}
