package com.comp5348.contracts.events;

/** Kafka topic names. Every record uses the event's orderId as its key (rule 5). */
public final class Topics {

    public static final String ORDER_EVENTS = "order-events";
    public static final String DELIVERY_EVENTS = "delivery-events";

    private Topics() {
    }

    /** Dead-letter topic for messages that can never succeed (rule 6). */
    public static String deadLetter(String topic) {
        return topic + ".DLT";
    }
}
