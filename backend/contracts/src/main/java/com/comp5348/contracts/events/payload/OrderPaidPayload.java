package com.comp5348.contracts.events.payload;

import com.fasterxml.jackson.annotation.JsonFormat;

import java.math.BigDecimal;
import java.util.List;

/**
 * Carries everything delivery needs to create a delivery, so delivery never calls store back.
 * Fields mirror today's DeliveryRequestDTO, minus notificationUrl, which Kafka replaces.
 */
public record OrderPaidPayload(
        long paymentId,
        @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount,
        String bankTxnId,
        String customerEmail,
        String customerName,
        String toAddress,
        List<String> fromAddresses,
        String productName,
        int quantity) {
}
