package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * outcome follows contracts/bank.proto: SUCCEEDED or REJECTED.
 * There is no "unknown" outcome: if the caller gets no answer (timeout, 5xx), the result is unknown.
 * success / message are kept for existing callers (frontend Add Money).
 */
@Data
@NoArgsConstructor
public class TransferResponse {

    private boolean success;
    private String transactionId;
    private String message;

    public TransferResponse(boolean success, String transactionId, String message) {
        this.success = success;
        this.transactionId = transactionId;
        this.message = message;
    }

    public String getOutcome() {
        return success ? "SUCCEEDED" : "REJECTED";
    }
}
