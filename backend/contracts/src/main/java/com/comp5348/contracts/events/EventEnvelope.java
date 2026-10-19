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

    /**
     * Runs for every envelope: Jackson calls it when parsing, producers call it when building.
     * Jackson passes null for a missing field, so the checks here also catch missing fields.
     */
    public EventEnvelope {
        requirePresent(eventId, "eventId");
        requireText(eventType, "eventType");
        requireText(orderId, "orderId");
        requirePresent(occurredAt, "occurredAt");
        // A JSON null payload arrives as NullNode, not Java null, hence the isObject check.
        if (payload == null || !payload.isObject()) {
            throw new IllegalArgumentException("payload must be a JSON object");
        }
    }

    private static void requirePresent(Object value, String field) {
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
