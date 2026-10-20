package comp5348.storeservice.events;

import comp5348.storeservice.model.EventOutbox;
import comp5348.storeservice.repository.EventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Sends event_outbox rows to Kafka (rule 3 / ADR-06).
 *
 * Each round is one transaction that first takes pg_try_advisory_xact_lock(LOCK_KEY). Only the instance holding
 * the lock sends; the others skip the round, so two pods during a rolling update never send the same rows.
 * The lock is transaction-scoped: commit, rollback or a dropped connection releases it.
 *
 * Rows go out one by one in id order, each waiting for Kafka's ack, and are marked sent in the same transaction.
 * If one send fails, the round stops there and commits what was already acked; the next round starts from the
 * failed row, so order is kept. A crash after the send but before the commit sends the row again (at least once;
 * consumers de-duplicate, rule 4).
 */
@Component
public class EventOutboxPublisher implements SmartLifecycle {

    private static final Logger logger = LoggerFactory.getLogger(EventOutboxPublisher.class);

    /** The "fixed key" of rule 3. store has its own database, so the number only has to be fixed here (delivery happens to use the same one). */
    static final long LOCK_KEY = 5348L;

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    @Value("${event-outbox.publisher.send-timeout-ms:15000}")
    private long sendTimeoutMs;

    @Value("${event-outbox.publisher.interval-ms:1000}")
    private long intervalMs;

    private volatile ScheduledExecutorService executor;

    public EventOutboxPublisher(EventOutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                                JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Runs on this class's own thread, not through @Scheduled: Spring's shared taskScheduler has one thread,
     * and an OutboxProcessor round waiting on bank (fast retries) would hold events back. (Tests freeze the
     * shared scheduler so they can run OutboxProcessor by hand; this keeps running, as in production.)
     */
    @Override
    public void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "event-publisher"));
        executor.scheduleWithFixedDelay(this::publishPending, intervalMs, intervalMs, TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        executor.shutdown();
        try {
            executor.awaitTermination(sendTimeoutMs, TimeUnit.MILLISECONDS); // let a round in progress finish
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        executor = null;
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }

    public void publishPending() {
        try {
            transactionTemplate.executeWithoutResult(status -> publishBatch());
        } catch (Exception e) {
            // Database trouble: nothing was marked, the next round tries again
            logger.warn("Event publishing round failed: {}", e.getMessage());
        }
    }

    private void publishBatch() {
        Boolean locked = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(locked)) {
            return; // another instance is publishing this round
        }
        List<EventOutbox> batch = outboxRepository.findTop100BySentAtIsNullOrderByIdAsc();
        for (EventOutbox row : batch) {
            try {
                kafkaTemplate.send(row.getTopic(), String.valueOf(row.getOrderId()), row.getPayload())
                        .get(sendTimeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (Exception e) {
                logger.warn("Kafka send failed for event_outbox id={} ({} {}), stopping this round: {}",
                        row.getId(), row.getEventType(), row.getOrderId(), e.getMessage());
                return; // rows before this one stay marked; this one is first in the next round
            }
            row.setSentAt(Instant.now());
        }
    }
}
