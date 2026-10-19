package com.comp5348.contracts.events;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The shell every Kafka event travels in.
 *
 * @param eventId    unique per event; consumers de-duplicate on it (rule 4)
 * @param eventType  wire name, e.g. "OrderPaid" (see {@link OrderEventType}, {@link DeliveryEventType})
 * @param orderId    store's order id as a decimal string; also the Kafka key
 * @param occurredAt when the state change committed, UTC
 * @param payload    event-specific fields (see the payload package)
 */
public record EventEnvelope(
        UUID eventId,
        String eventType,
        String orderId,
        Instant occurredAt,
        JsonNode payload) {
}
