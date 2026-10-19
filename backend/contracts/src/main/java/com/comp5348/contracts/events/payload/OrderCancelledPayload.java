package com.comp5348.contracts.events.payload;

/** @param reason USER_REQUEST | PAYMENT_TIMEOUT */
public record OrderCancelledPayload(String reason) {
}
