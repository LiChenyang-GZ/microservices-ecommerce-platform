package com.comp5348.delivery.service.events;

import com.comp5348.contracts.events.DeliveryEventType;
import com.comp5348.contracts.events.EventCodec;
import com.comp5348.contracts.events.EventEnvelope;
import com.comp5348.contracts.events.Topics;
import com.comp5348.contracts.events.payload.DeliveryEventPayload;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.model.EventOutbox;
import com.comp5348.delivery.repository.EventOutboxRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Writes the delivery-events event for a status change into event_outbox (rule 2).
 * MANDATORY: it must join the transaction that changes the status, so the change and its event
 * commit together or not at all. Called without a transaction, it fails instead of silently writing alone.
 */
@Component
public class DeliveryEventRecorder {

    private final EventOutboxRepository outboxRepository;

    public DeliveryEventRecorder(EventOutboxRepository outboxRepository) {
        this.outboxRepository = outboxRepository;
    }

    /** The delivery has just entered its current status: record the matching event */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordStatus(Delivery delivery) {
        record(delivery, delivery.getId());
    }

    /**
     * A cancel arrived before OrderPaid, so a cancel marker row was inserted instead of a delivery.
     * Its Cancelled event carries deliveryId null (the contract allows it): there is no delivery to point at.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordCancelMarker(Delivery marker) {
        record(marker, null);
    }

    private void record(Delivery delivery, Long deliveryId) {
        DeliveryEventType type = DeliveryEventType.fromStatus(delivery.getDeliveryStatus().name());
        Instant now = Instant.now();
        EventEnvelope envelope = new EventEnvelope(UUID.randomUUID(), type.wireName(), delivery.getOrderId(), now,
                EventCodec.toTree(new DeliveryEventPayload(deliveryId, type.deliveryStatus())));

        EventOutbox row = new EventOutbox();
        row.setEventId(envelope.eventId());
        row.setTopic(Topics.DELIVERY_EVENTS);
        row.setOrderId(envelope.orderId());
        row.setEventType(envelope.eventType());
        row.setPayload(EventCodec.write(envelope));
        row.setCreatedAt(now);
        outboxRepository.save(row);
    }
}
