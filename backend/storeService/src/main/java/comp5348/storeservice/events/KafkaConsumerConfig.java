package comp5348.storeservice.events;

import com.comp5348.contracts.events.InvalidEventException;
import com.comp5348.contracts.events.Topics;
import jakarta.persistence.LockTimeoutException;
import jakarta.persistence.PessimisticLockException;
import jakarta.persistence.QueryTimeoutException;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.RecoverableDataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

import java.sql.SQLTransientException;
import java.util.List;

/**
 * What a Kafka consumer does when handling a message throws (rule 6 / ADR-08, plus Christy's decision on
 * question 1, option b). Spring Boot hands this error handler to every @KafkaListener container.
 *
 *   bad message (InvalidEventException, UnknownOrderException) → no retry, straight to <topic>.DLT
 *   known transient trouble (database down, lock timeout)       → retry in place forever, backing off up to 2s
 *   anything else (most likely a bug)                           → retry 5 times, then <topic>.DLT
 *
 * While a record is being retried its partition waits, which keeps per-order order. Other partitions keep
 * going because each of the 3 partitions has its own consumer thread (spring.kafka.listener.concurrency=3).
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger logger = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** "The database is having a moment": waiting fixes these, so never give up on them */
    private static final List<Class<? extends Throwable>> TRANSIENT = List.of(
            TransientDataAccessException.class,       // includes lock and query timeouts Spring translated
            RecoverableDataAccessException.class,
            DataAccessResourceFailureException.class,  // cannot connect
            CannotCreateTransactionException.class,    // no connection for a new transaction
            TransactionTimedOutException.class,
            SQLTransientException.class,
            LockTimeoutException.class,
            PessimisticLockException.class,
            QueryTimeoutException.class);

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<?, ?> template) {
        // The contract's DLT name is <topic>.DLT, written to the same partition number (the .DLT topics also
        // have 3 partitions). Set explicitly rather than relying on Spring's default naming.
        DeadLetterPublishingRecoverer toDeadLetter = new DeadLetterPublishingRecoverer(template,
                (record, ex) -> new TopicPartition(Topics.deadLetter(record.topic()), record.partition()));
        ConsumerRecordRecoverer alertThenDeadLetter = (record, ex) -> {
            logger.error("ALERT kafka_dead_letter topic={} partition={} offset={} key={} reason={}",
                    record.topic(), record.partition(), record.offset(), record.key(), rootMessage(ex));
            // If this send fails (Kafka trouble), it throws: the record is not committed and comes back
            toDeadLetter.accept(record, ex);
        };

        DefaultErrorHandler handler = new DefaultErrorHandler(alertThenDeadLetter, new FixedBackOff(200L, 5));
        handler.setBackOffFunction((record, ex) -> isTransient(ex) ? transientBackOff() : null); // null = default
        handler.addNotRetryableExceptions(InvalidEventException.class, UnknownOrderException.class);
        return handler;
    }

    private static BackOff transientBackOff() {
        ExponentialBackOff backOff = new ExponentialBackOff(200L, 2.0);
        backOff.setMaxInterval(2_000L); // max elapsed time is unlimited by default: keep trying
        return backOff;
    }

    static boolean isTransient(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            for (Class<? extends Throwable> type : TRANSIENT) {
                if (type.isInstance(t)) {
                    return true;
                }
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable ex) {
        Throwable t = ex;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }
}
