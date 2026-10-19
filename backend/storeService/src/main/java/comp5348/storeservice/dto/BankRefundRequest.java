package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

/**
 * Field names follow contracts/bank.proto (RefundRequest).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankRefundRequest {

    private String idempotencyKey; // REFUND-{paymentId}
    private String originalTransactionId;
    private String reason;
}
