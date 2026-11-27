package comp5348.storeservice.events;

import comp5348.storeservice.model.EventOutbox;
import comp5348.storeservice.repository.EventOutboxRepository;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 失败场景表 87 号 S1–S7（store 那份）：event_outbox 里一行发不出去，会挡住后面所有订单的事件（保序，故意的）。
 * 挡住可以，但不能只有每轮一行 WARN：同一行连续失败 10 轮，或者失败时它已经等了 5 分钟以上 →
 * 打一次 ALERT event_outbox_stuck；它终于过去了打一行 event_outbox_unstuck。
 *
 * 【层】单元测试：投递程序是真的，库表、Kafka、事务、咨询锁换成假的（表 = 内存里的一个 List）。
 *   payload 以 "poison" 开头的行，假 Kafka 每次都回"消息太大"（和真 Kafka 对超过上限的消息一样，立刻失败）。
 *   真 Kafka 上的同一件事见 EventOutboxStuckIntegrationTest。
 * 【红灯为什么红】现在只有 WARN：S1、S3、S4 等不到 ALERT / unstuck。
 */
@ExtendWith(OutputCaptureExtension.class)
class EventOutboxStuckTest {

    private static final String STUCK = "ALERT event_outbox_stuck";
    private static final String UNSTUCK = "event_outbox_unstuck";

    private final EventOutboxRepository repository = mock(EventOutboxRepository.class);
    @SuppressWarnings("unchecked")
    private final KafkaTemplate<String, String> kafka = mock(KafkaTemplate.class);
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final TransactionTemplate transactions = mock(TransactionTemplate.class);

    /** 假的 event_outbox 表 */
    private final List<EventOutbox> table = new ArrayList<>();
    private long nextId = 1;
    private EventOutboxPublisher publisher;

    @BeforeEach
    void setUp() {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), ArgumentMatchers.<Object>any())).thenReturn(true);
        doAnswer(inv -> {
            inv.<Consumer<TransactionStatus>>getArgument(0).accept(null);
            return null;
        }).when(transactions).executeWithoutResult(any());
        when(repository.findTop100BySentAtIsNullOrderByIdAsc())
                .thenAnswer(inv -> table.stream().filter(r -> r.getSentAt() == null).toList());
        when(kafka.send(anyString(), anyString(), anyString())).thenAnswer(inv -> {
            String payload = inv.getArgument(2);
            return payload.startsWith("poison")
                    ? CompletableFuture.failedFuture(new RecordTooLargeException("The message is 1200000 bytes"))
                    : CompletableFuture.completedFuture(null);
        });
        publisher = new EventOutboxPublisher(repository, kafka, jdbc, transactions);
        ReflectionTestUtils.setField(publisher, "sendTimeoutMs", 1000L);
    }

    private EventOutbox row(long orderId, String payload) {
        return row(orderId, payload, Instant.now());
    }

    private EventOutbox row(long orderId, String payload, Instant createdAt) {
        EventOutbox row = new EventOutbox();
        row.setId(nextId++);
        row.setEventId(UUID.randomUUID());
        row.setTopic("order-events");
        row.setOrderId(orderId);
        row.setEventType("OrderPaid");
        row.setPayload(payload);
        row.setCreatedAt(createdAt);
        table.add(row);
        return row;
    }

    private void rounds(int n) {
        for (int i = 0; i < n; i++) {
            publisher.publishPending();
        }
    }

    private static int occurrences(CapturedOutput output, String text) {
        String log = output.getOut();
        int count = 0;
        for (int i = log.indexOf(text); i >= 0; i = log.indexOf(text, i + text.length())) {
            count++;
        }
        return count;
    }

    /** S1：一行永远发不出去 → 后面的行一直等着（保序）；第 10 轮打一次 ALERT，带 id、类型、订单、轮数、等了多久 */
    @Test
    void poisonRow_blocksLaterRows_andAlertsAfterTenFailedRounds(CapturedOutput output) {
        EventOutbox poison = row(501, "poison");
        EventOutbox behind = row(502, "{}");

        rounds(9);
        assertThat(output.getOut()).as("才 9 轮").doesNotContain(STUCK);

        rounds(1);
        assertThat(output.getOut()).contains(STUCK + " id=" + poison.getId() + " eventType=OrderPaid orderId=501 failures=10 waitingSeconds=")
                .contains("The message is 1200000 bytes");
        assertThat(behind.getSentAt()).as("后面的行还在等").isNull();
        verify(kafka, never()).send(anyString(), eq("502"), anyString());
    }

    /** S2：ALERT 之后继续失败，不再重复打 */
    @Test
    void stillStuck_alertsOnlyOnce(CapturedOutput output) {
        row(501, "poison");

        rounds(30);

        assertThat(occurrences(output, STUCK)).isEqualTo(1);
    }

    /** S3：失败时这一行已经等了 5 分钟以上（还没到 10 轮）→ 第一次失败就 ALERT */
    @Test
    void rowWaitingLongerThanFiveMinutes_alertsOnFirstFailure(CapturedOutput output) {
        EventOutbox old = row(501, "poison", Instant.now().minus(Duration.ofMinutes(6)));

        rounds(1);

        assertThat(output.getOut()).contains(STUCK + " id=" + old.getId() + " eventType=OrderPaid orderId=501 failures=1 ");
    }

    /** S4：卡住的行后来发出去了 → 打 unstuck，后面的行跟着发出去；之后另一行再卡住会再 ALERT */
    @Test
    void stuckRowGoesThrough_logsUnstuck_andTheNextStuckRowAlertsAgain(CapturedOutput output) {
        EventOutbox poison = row(501, "poison");
        EventOutbox behind = row(502, "{}");
        rounds(10);
        assertThat(occurrences(output, STUCK)).isEqualTo(1);

        poison.setPayload("{}"); // 有人把它修好了
        rounds(1);

        assertThat(poison.getSentAt()).isNotNull();
        assertThat(behind.getSentAt()).isNotNull();
        assertThat(output.getOut()).contains(UNSTUCK + " id=" + poison.getId());

        EventOutbox next = row(503, "poison");
        rounds(10);
        assertThat(output.getOut()).contains(STUCK + " id=" + next.getId());
        assertThat(occurrences(output, STUCK)).isEqualTo(2);
    }

    /** S5：偶尔失败几轮就好了（不到 10 轮、没等到 5 分钟）→ 没有 ALERT，也没有 unstuck */
    @Test
    void failsAFewRoundsThenGoesThrough_noAlert(CapturedOutput output) {
        EventOutbox flaky = row(501, "poison");
        rounds(3);
        flaky.setPayload("{}");
        rounds(1);

        assertThat(flaky.getSentAt()).isNotNull();
        assertThat(output.getOut()).doesNotContain(STUCK).doesNotContain(UNSTUCK);
    }

    /** S6：连续失败的不是同一行 → 按行重新数，不累加 */
    @Test
    void differentRowsFailing_countSeparately(CapturedOutput output) {
        EventOutbox first = row(501, "poison");
        rounds(6);
        first.setPayload("{}");
        row(502, "poison");
        rounds(6);

        assertThat(output.getOut()).doesNotContain(STUCK);
    }

    /** S7：这一轮没拿到咨询锁（别的实例在发）→ 什么都不发、不算失败、不 ALERT */
    @Test
    void lockHeldByAnotherInstance_noSend_noAlert(CapturedOutput output) {
        when(jdbc.queryForObject(anyString(), eq(Boolean.class), ArgumentMatchers.<Object>any())).thenReturn(false);
        row(501, "poison", Instant.now().minus(Duration.ofMinutes(10)));

        rounds(20);

        verify(kafka, never()).send(anyString(), anyString(), anyString());
        assertThat(output.getOut()).doesNotContain(STUCK);
    }
}
