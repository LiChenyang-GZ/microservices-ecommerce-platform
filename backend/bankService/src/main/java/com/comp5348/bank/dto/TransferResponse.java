package com.comp5348.bank.dto;

import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * outcome follows contracts/bank.proto: SUCCEEDED, REJECTED or KEY_CONFLICT (the key was used for a different request).
 * There is no "unknown" outcome: if the caller gets no answer (timeout, 5xx), the result is unknown.
 * success / message are kept for callers of the REST endpoint.
 */
@Data
@NoArgsConstructor
public class TransferResponse {

    private boolean success;
    private String transactionId;
    private String message;
    // The key was already used for a different request (ADR-29). success is false, but this is not a "no"
    private boolean keyConflict;

    public TransferResponse(boolean success, String transactionId, String message) {
        this.success = success;
        this.transactionId = transactionId;
        this.message = message;
    }

    public static TransferResponse keyConflict(String message) {
        TransferResponse response = new TransferResponse(false, null, message);
        response.setKeyConflict(true);
        return response;
    }

    public String getOutcome() {
        return success ? "SUCCEEDED" : keyConflict ? "KEY_CONFLICT" : "REJECTED";
    }
}
