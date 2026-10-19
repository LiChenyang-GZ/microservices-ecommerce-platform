package comp5348.storeservice.model;

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    DELIVERY_FAILED,
    // No answer from bank after 5 fast retries. Not a failure: keep retrying the same key every 10 minutes (rule 7)
    PAY_UNKNOWN,
    // Charged, refund task written, waiting for bank
    REFUND_PENDING,
    // No answer from bank to the refund after 5 fast retries; keep retrying every 10 minutes
    REFUND_UNKNOWN
}


