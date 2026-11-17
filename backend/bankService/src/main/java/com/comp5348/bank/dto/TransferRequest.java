package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * Field names follow contracts/bank.proto (TransferRequest), so step 6 (gRPC) only swaps the protocol.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TransferRequest {

    // Same key = same transfer. Store sends PAY-{paymentId}-{attempt}-{random}. Bank treats keys as opaque strings.
    private String idempotencyKey;
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
}
