package com.comp5348.contracts.events.payload;

/** Only for a definite failure (bank said REJECTED). A timeout is never a PaymentFailed (rule 7). */
public record PaymentFailedPayload(
        long paymentId,
        int attempt,
        String reason) {
}
