package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * outcome follows contracts/bank.proto: SUCCEEDED, REJECTED or KEY_CONFLICT. See TransferResponse.
 */
@Data
@NoArgsConstructor
public class RefundResponse {

    private boolean success;
    private String refundTransactionId;
    private String message;
    // The key was already used to refund a different transaction (ADR-29)
    private boolean keyConflict;

    public RefundResponse(boolean success, String refundTransactionId, String message) {
        this.success = success;
        this.refundTransactionId = refundTransactionId;
        this.message = message;
    }

    public static RefundResponse keyConflict(String message) {
        RefundResponse response = new RefundResponse(false, null, message);
        response.setKeyConflict(true);
        return response;
    }

    public String getOutcome() {
        return success ? "SUCCEEDED" : keyConflict ? "KEY_CONFLICT" : "REJECTED";
    }
}
