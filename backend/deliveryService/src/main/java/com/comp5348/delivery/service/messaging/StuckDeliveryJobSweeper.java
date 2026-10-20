package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Sends the simulation job again for a delivery that is not finished and has not moved for a while
 * (wave 2 integration finding I1).
 *
 * The job is sent to RabbitMQ after the database commit, when a delivery is created and after each step.
 * The commit and the send are two steps; if RabbitMQ is down in between, the job is gone and nothing sends it
 * again (a redelivered OrderPaid is skipped as a repeat). This is the fallback that finds those rows.
 *
 * Each row is claimed first: update_time moves to now only if it is still the value we read. So two delivery
 * instances never resend the same row in one round, and a row whose send fails is tried again one stale
 * period later instead of every round. A job that was only slow, not lost, may now run twice; the worker's
 * optimistic lock (@Version) drops the second save if they overlap.
 *
 * delivery.stuck-job.enabled=false turns it off (tests that start a second instance on the same database).
 */
@Component
@ConditionalOnProperty(name = "delivery.stuck-job.enabled", matchIfMissing = true)
public class StuckDeliveryJobSweeper {

    private static final Logger logger = LoggerFactory.getLogger(StuckDeliveryJobSweeper.class);
    private static final int BATCH = 100;

    private final JdbcTemplate jdbc;
    private final RabbitTemplate rabbitTemplate;

    /** Longer than a few steps (each step waits delivery.simulation.step-delay-ms), so a moving delivery is not stuck */
    @Value("${delivery.stuck-job.stale-after-ms:60000}")
    private long staleAfterMs;

    public StuckDeliveryJobSweeper(JdbcTemplate jdbc, RabbitTemplate rabbitTemplate) {
        this.jdbc = jdbc;
        this.rabbitTemplate = rabbitTemplate;
    }

    @Scheduled(fixedDelayString = "${delivery.stuck-job.check-interval-ms:10000}")
    public void resendStuckJobs() {
        LocalDateTime now = LocalDateTime.now();
        List<Map<String, Object>> stuck = jdbc.queryForList("""
                SELECT id, update_time FROM delivery
                WHERE delivery_status NOT IN ('RECEIVED', 'LOST', 'CANCELLED') AND update_time < ?
                ORDER BY update_time LIMIT ?""", now.minusNanos(staleAfterMs * 1_000_000), BATCH);

        for (Map<String, Object> row : stuck) {
            Long id = ((Number) row.get("id")).longValue();
            Timestamp seen = (Timestamp) row.get("update_time");
            int claimed = jdbc.update("UPDATE delivery SET update_time = ? WHERE id = ? AND update_time = ?", now, id, seen);
            if (claimed == 0) {
                continue; // it moved on, or another instance took it
            }
            try {
                rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, id);
                logger.warn("Delivery {} had not moved since {}: simulation job sent again", id, seen);
            } catch (AmqpException e) {
                // Still down. The claim stands, so this row is tried again after another stale period.
                // Stop the round: each failed send can block for the connection timeout.
                logger.error("ALERT delivery_job_resend_failed deliveryId={} error={}", id, e.getMessage());
                return;
            }
        }
    }
}
