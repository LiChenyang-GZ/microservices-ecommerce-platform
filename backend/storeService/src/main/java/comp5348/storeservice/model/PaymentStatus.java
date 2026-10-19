package comp5348.storeservice.model;

public enum PaymentStatus {
    PENDING,
    SUCCESS,
    FAILED,
    REFUNDED,
    DELIVERY_FAILED,
    // Step 2 shells (test(red)): declared so the tests compile; no code sets them yet
    PAY_UNKNOWN,
    REFUND_PENDING,
    REFUND_UNKNOWN
}


