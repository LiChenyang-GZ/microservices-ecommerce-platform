package com.comp5348.delivery.events;

import com.comp5348.delivery.support.DeliveryIntegrationTest;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 失败场景表 C1–C4、B7：新的取消接口 POST /internal/deliveries/cancel-by-order/{orderId}。
 *
 * 设计"取消接口"一节的表：
 *   配送单不存在     → 插入取消标记 + 写 Cancelled → 200 {"result":"CANCELLED_BEFORE_CREATED"}
 *   CREATED          → 改 CANCELLED + 写 Cancelled → 200 {"result":"CANCELLED"}
 *   已经 CANCELLED   → 什么都不做                  → 200 {"result":"ALREADY_CANCELLED"}
 *   PICKED_UP 及以后 → 不改                        → 409 {"result":"TOO_LATE","status":"PICKED_UP"}
 * 回答的字段名（result、status）在这里定下来，store 按它解析。
 *
 * 【层】接口用 MockMvc 调（不经过网络），但背后是真 Postgres + 真 Kafka 的完整上下文，所以也看事件。
 * 【红灯为什么红】这个接口还不存在（只有旧的 /api/deliveries/cancel-by-order，配送单不存在时回 400），所以是 404。
 */
class CancelByOrderApiIntegrationTest extends DeliveryIntegrationTest {

    private static final String CANCEL = "/internal/deliveries/cancel-by-order/";

    /** C1：配送单还不存在（delivery 还没读到 OrderPaid）→ 取消标记 + Cancelled 事件（deliveryId 为 null） */
    @Test
    void cancel_beforeDeliveryExists_insertsMarker() throws Exception {
        String orderId = newOrderId();

        mvc().perform(post(CANCEL + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CANCELLED_BEFORE_CREATED"));

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        assertThat(awaitEventTypes(orderId, 1)).containsExactly("Cancelled");
    }

    /** C2 + C3：CREATED → 取消；再调一次 → ALREADY_CANCELLED，不写第二条事件 */
    @Test
    void cancel_createdThenAgain_isCancelledThenAlreadyCancelled() throws Exception {
        String orderId = newOrderId();
        createDelivery(orderId);

        mvc().perform(post(CANCEL + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("CANCELLED"));
        mvc().perform(post(CANCEL + orderId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result").value("ALREADY_CANCELLED"));

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.CANCELLED);
        awaitEventTypes(orderId, 2);
        Thread.sleep(1_000);
        assertThat(eventTypesOf(orderId)).containsExactly("DeliveryCreated", "Cancelled");
    }

    /** C4：已经揽收 → 409 TOO_LATE + 当前状态，配送单不变 */
    @Test
    void cancel_afterPickup_isTooLate() throws Exception {
        String orderId = newOrderId();
        Long id = createDelivery(orderId);
        worker.processDelivery(id); // → PICKED_UP

        mvc().perform(post(CANCEL + orderId))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.result").value("TOO_LATE"))
                .andExpect(jsonPath("$.status").value("PICKED_UP"));

        assertThat(deliveryOf(orderId).getDeliveryStatus()).isEqualTo(DeliveryStatus.PICKED_UP);
    }

    /**
     * B7：取消请求和 OrderPaid 同时到。不管谁先，结局都一样：只有一行，状态 CANCELLED。
     * 设计：取消接口说明"先 INSERT … ON CONFLICT (order_id) DO NOTHING，再 SELECT … FOR UPDATE，总有一个先、一个后"。
     * 反例：没有唯一约束时，两边都"看不到这一行"、各插一行 → 一个订单两张配送单，其中一张还会被发货。
     * 跑 10 个订单，每个订单的两件事用 CountDownLatch 尽量同时放出去。
     */
    @Test
    void cancelAndOrderPaid_atTheSameTime_oneRowEndsCancelled() throws Exception {
        List<String> orders = new ArrayList<>();
        List<RecordMetadata> paidRecords = new ArrayList<>();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                String orderId = newOrderId();
                orders.add(orderId);
                CountDownLatch go = new CountDownLatch(1);
                Future<RecordMetadata> paid = pool.submit(() -> { go.await(); return publishOrderEvent(orderPaid(orderId)); });
                Future<?> cancel = pool.submit(() -> { go.await(); return mvc().perform(post(CANCEL + orderId)).andReturn(); });
                go.countDown(); // 两件事一起放
                paidRecords.add(paid.get());
                cancel.get();
            }
        } finally {
            pool.shutdown();
        }

        // OrderPaid 可能还在路上：等到每一条都被 delivery 处理过（消费者组越过它），再看结局
        for (RecordMetadata record : paidRecords) {
            awaitConsumedByDelivery(record);
        }
        for (String orderId : orders) {
            assertThat(rowsOf(orderId)).as("order %s", orderId).isEqualTo(1);
            assertThat(deliveryOf(orderId).getDeliveryStatus()).as("order %s", orderId).isEqualTo(DeliveryStatus.CANCELLED);
        }
    }
}
