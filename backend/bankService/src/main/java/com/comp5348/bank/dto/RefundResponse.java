package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * outcome follows contracts/bank.proto: SUCCEEDED or REJECTED. See TransferResponse.
 */
@Data
@NoArgsConstructor
public class RefundResponse {

    private boolean success;
    private String refundTransactionId;
    private String message;

    public RefundResponse(boolean success, String refundTransactionId, String message) {
        this.success = success;
        this.refundTransactionId = refundTransactionId;
        this.message = message;
    }

    public String getOutcome() {
        return success ? "SUCCEEDED" : "REJECTED";
    }
}
