package com.comp5348.contracts.events;

/**
 * Events delivery publishes to {@link Topics#DELIVERY_EVENTS}, one per value of
 * delivery's DeliveryStatus enum. All of them carry a
 * {@link com.comp5348.contracts.events.payload.DeliveryEventPayload}.
 */
public enum DeliveryEventType {
    DELIVERY_CREATED("DeliveryCreated"),
    PICKED_UP("PickedUp"),
    DELIVERING("Delivering"),
    RECEIVED("Received"),
    LOST("Lost"),
    CANCELLED("Cancelled");

    private final String wireName;

    DeliveryEventType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Event to publish when a delivery enters the given DeliveryStatus (by enum name). */
    public static DeliveryEventType fromStatus(String deliveryStatus) {
        return valueOf(deliveryStatus);
    }
}
