package com.comp5348.contracts.events;

import com.comp5348.contracts.events.payload.OrderCancelledPayload;
import com.comp5348.contracts.events.payload.OrderPaidPayload;
import com.comp5348.contracts.events.payload.OrderPlacedPayload;
import com.comp5348.contracts.events.payload.OrderRefundedPayload;
import com.comp5348.contracts.events.payload.PaymentFailedPayload;

/** Events store publishes to {@link Topics#ORDER_EVENTS}. */
public enum OrderEventType {
    ORDER_PLACED("OrderPlaced", OrderPlacedPayload.class),
    ORDER_PAID("OrderPaid", OrderPaidPayload.class),
    PAYMENT_FAILED("PaymentFailed", PaymentFailedPayload.class),
    ORDER_CANCELLED("OrderCancelled", OrderCancelledPayload.class),
    ORDER_REFUNDED("OrderRefunded", OrderRefundedPayload.class);

    private final String wireName;
    private final Class<?> payloadType;

    OrderEventType(String wireName, Class<?> payloadType) {
        this.wireName = wireName;
        this.payloadType = payloadType;
    }

    public String wireName() {
        return wireName;
    }

    public Class<?> payloadType() {
        return payloadType;
    }
}
