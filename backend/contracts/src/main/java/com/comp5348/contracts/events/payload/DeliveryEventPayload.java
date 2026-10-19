package com.comp5348.contracts.events.payload;

/**
 * Payload of every delivery-events event.
 *
 * @param deliveryId null only for a Cancelled event written as a cancel marker,
 *                   i.e. the order was cancelled before delivery saw OrderPaid (ADR-09)
 * @param status     the DeliveryStatus name the delivery just entered, e.g. "PICKED_UP"
 */
public record DeliveryEventPayload(Long deliveryId, String status) {
}
