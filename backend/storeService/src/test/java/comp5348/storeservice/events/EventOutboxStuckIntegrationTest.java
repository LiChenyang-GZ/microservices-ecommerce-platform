package comp5348.storeservice.events;

import com.comp5348.contracts.events.Topics;
import comp5348.storeservice.support.DeliveryEventsTestBase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * 失败场景表 87 号 S1、S4（store 那份，真 Kafka）：一行超过 Kafka 消息上限（生产者默认 1 MB）的事件永远发不出去，
 * 挡住后面所有订单的事件。要有一次 ALERT event_outbox_stuck；那一行没了以后，后面的事件照常发出去、打 unstuck。
 *
 * 【层】集成测试：真 Postgres + 真 Kafka + 真的投递程序（它在自己的线程里每 200 毫秒跑一轮，测试只等结果）。
 *   S2、S3、S5–S7 的细节在单元测试 EventOutboxStuckTest 里。
 * 【红灯为什么红】现在只有每轮一行 WARN，等不到 ALERT。
 * 【一定要清掉毒消息】它会挡住这个测试进程里所有之后的事件，所以放在 finally 里删。
 */
@ExtendWith(OutputCaptureExtension.class)
class EventOutboxStuckIntegrationTest extends DeliveryEventsTestBase {

    @Test
    void oversizedEvent_blocksLaterOrders_alertsOnce_andRecoversWhenRemoved(CapturedOutput output) {
        Long poisonId = jdbc.queryForObject(
                "INSERT INTO event_outbox (event_id, topic, order_id, event_type, payload, created_at) "
                        + "VALUES (gen_random_uuid(), ?, ?, 'OrderPlaced', ?, now()) RETURNING id",
                Long.class, Topics.ORDER_EVENTS, 990_000_001L, "x".repeat(1_200_000));
        try {
            PlacedOrder order = placeOrder(); // 它的 OrderPlaced 排在毒消息后面

            await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
                    assertThat(output.getOut()).contains("ALERT event_outbox_stuck id=" + poisonId
                            + " eventType=OrderPlaced orderId=990000001 failures="));
            assertThat(orderEventTypesOf(order)).as("被挡住了：后面订单的事件一条都没发").isEmpty();
            assertThat(occurrences(output.getOut(), "ALERT event_outbox_stuck id=" + poisonId)).isEqualTo(1);

            jdbc.update("DELETE FROM event_outbox WHERE id = ?", poisonId); // 人来处理了

            assertThat(awaitOrderEventTypes(order, 1)).containsExactly("OrderPlaced");
            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(output.getOut()).contains("event_outbox_unstuck id=" + poisonId));
        } finally {
            jdbc.update("DELETE FROM event_outbox WHERE id = ?", poisonId);
        }
    }
}
