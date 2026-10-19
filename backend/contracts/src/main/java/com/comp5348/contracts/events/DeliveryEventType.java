package com.comp5348.contracts.events;

/**
 * Events delivery publishes to {@link Topics#DELIVERY_EVENTS}, one per value of
 * delivery's DeliveryStatus enum. All of them carry a
 * {@link com.comp5348.contracts.events.payload.DeliveryEventPayload}.
 */
public enum DeliveryEventType {
    // Names differ from DeliveryStatus (CREATED -> DeliveryCreated), so the mapping is explicit.
    DELIVERY_CREATED("DeliveryCreated", "CREATED"),
    PICKED_UP("PickedUp", "PICKED_UP"),
    DELIVERING("Delivering", "DELIVERING"),
    RECEIVED("Received", "RECEIVED"),
    LOST("Lost", "LOST"),
    CANCELLED("Cancelled", "CANCELLED");

    private final String wireName;
    private final String deliveryStatus;

    DeliveryEventType(String wireName, String deliveryStatus) {
        this.wireName = wireName;
        this.deliveryStatus = deliveryStatus;
    }

    public String wireName() {
        return wireName;
    }

    /** The DeliveryStatus name this event announces; equals the payload's status field. */
    public String deliveryStatus() {
        return deliveryStatus;
    }

    /** Event to publish when a delivery enters the given DeliveryStatus (by enum name). */
    public static DeliveryEventType fromStatus(String deliveryStatus) {
        for (DeliveryEventType type : values()) {
            if (type.deliveryStatus.equals(deliveryStatus)) {
                return type;
            }
        }
        throw new IllegalArgumentException("No delivery event for status " + deliveryStatus);
    }
}
