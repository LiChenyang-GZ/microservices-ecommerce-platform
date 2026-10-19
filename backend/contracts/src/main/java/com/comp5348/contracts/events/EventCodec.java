package com.comp5348.contracts.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Reads and writes {@link EventEnvelope} JSON. Producers and consumers must both go through here. */
public final class EventCodec {

    private static final ObjectMapper MAPPER = newMapper();

    private EventCodec() {
    }

    private static ObjectMapper newMapper() {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                // Instant as "2026-10-19T02:00:00Z", not as a number of seconds.
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // By default Jackson turns the number 42 into the String "42". Go would reject
        // that event, so reject it here too: a String field must receive a JSON string.
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
        return mapper;
    }

    public static EventEnvelope parse(String json) throws InvalidEventException {
        try {
            return MAPPER.readValue(json, EventEnvelope.class);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Invalid event: " + e.getOriginalMessage(), e);
        }
    }

    public static String write(EventEnvelope envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialise event " + envelope.eventId(), e);
        }
    }

    /** Converts the envelope's payload into its typed record. */
    public static <T> T payload(EventEnvelope envelope, Class<T> type) throws InvalidEventException {
        try {
            return MAPPER.treeToValue(envelope.payload(), type);
        } catch (JsonProcessingException e) {
            throw new InvalidEventException("Invalid " + envelope.eventType() + " payload: " + e.getOriginalMessage(), e);
        }
    }

    /** Turns a typed payload record into the JSON tree a new envelope carries. */
    public static JsonNode toTree(Object payload) {
        return MAPPER.valueToTree(payload);
    }
}
