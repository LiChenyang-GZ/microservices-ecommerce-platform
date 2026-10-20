package com.comp5348.delivery.service.events;

import com.comp5348.delivery.model.EventOutbox;
import com.comp5348.delivery.repository.EventOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;
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
public class EventOutboxPublisher {

    private static final Logger logger = LoggerFactory.getLogger(EventOutboxPublisher.class);

    /** The "fixed key" of rule 3. delivery has its own database, so the number only has to be fixed here. */
    static final long LOCK_KEY = 5348L;

    private final EventOutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactionTemplate;

    @Value("${event-outbox.publisher.send-timeout-ms:15000}")
    private long sendTimeoutMs;

    public EventOutboxPublisher(EventOutboxRepository outboxRepository, KafkaTemplate<String, String> kafkaTemplate,
                                JdbcTemplate jdbc, TransactionTemplate transactionTemplate) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    @Scheduled(fixedDelayString = "${event-outbox.publisher.interval-ms:1000}")
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
                kafkaTemplate.send(row.getTopic(), row.getOrderId(), row.getPayload())
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
