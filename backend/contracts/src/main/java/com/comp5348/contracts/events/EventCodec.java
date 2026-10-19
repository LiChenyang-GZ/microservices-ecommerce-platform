package com.comp5348.contracts.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Reads and writes {@link EventEnvelope} JSON. Producers and consumers must both go through here. */
public final class EventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private EventCodec() {
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
