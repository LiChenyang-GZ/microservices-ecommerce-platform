package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Field names follow contracts/bank.proto (RefundRequest). Refunds are always full refunds, so there is no amount.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RefundRequest {

    // Same key = same refund. Store sends REFUND-{paymentId}-{random}. Bank treats keys as opaque strings.
    private String idempotencyKey;
    private String originalTransactionId;
    private String reason;
}
