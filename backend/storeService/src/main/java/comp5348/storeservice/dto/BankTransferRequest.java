package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

/**
 * Field names follow contracts/bank.proto (TransferRequest).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankTransferRequest {

    private String idempotencyKey; // PAY-{paymentId}-{attempt}
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
}
