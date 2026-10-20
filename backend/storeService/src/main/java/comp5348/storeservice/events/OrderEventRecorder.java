package comp5348.storeservice.events;

import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.OrderEventType;
import com.comp5348.contracts.events.Topics;
import comp5348.storeservice.model.EventOutbox;
import comp5348.storeservice.repository.EventOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes an order-events event into event_outbox (rule 2). store's five: OrderPlaced, OrderPaid, PaymentFailed,
 * OrderCancelled, OrderRefunded (contract OrderEventType).
 * MANDATORY: it must join the transaction that changes the order or payment, so the change and its event
 * commit together or not at all. Called without a transaction, it fails instead of silently writing alone.
 */
@Component
public class OrderEventRecorder {

    private final EventOutboxRepository outboxRepository;

    public OrderEventRecorder(EventOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** @param payload the contract's payload record for this type (OrderPaidPayload for ORDER_PAID, ...) */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(long orderId, OrderEventType type, Object payload) {
        if (!type.payloadType().isInstance(payload)) {
            throw new IllegalArgumentException(type.wireName() + " needs a " + type.payloadType().getSimpleName());
        }
        Instant now = Instant.now();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type.wireName(), String.valueOf(orderId), now,
                EventCodec.toTree(payload));

        EventOutbox row = new EventOutbox();
        row.setEventId(envelope.eventId());
        row.setTopic(Topics.ORDER_EVENTS);
        row.setOrderId(orderId);
        row.setEventType(envelope.eventType());
        row.setPayload(EventCodec.write(envelope));
        row.setCreatedAt(now);
        outboxRepository.save(row);
    }
}
