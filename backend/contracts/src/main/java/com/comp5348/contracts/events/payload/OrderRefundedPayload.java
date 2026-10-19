package com.comp5348.contracts.events.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;

/** @param reason ORDER_CANCELLED | DELIVERY_LOST | PAID_AFTER_CANCEL */
public record OrderRefundedPayload(
        long paymentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String refundTxnId,
        String reason) {
}
