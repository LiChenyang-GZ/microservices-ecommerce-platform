package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankTransferRequest {
    
    private String fromAccount;
    private String toAccount;
    private BigDecimal amount;
    private String transactionRef;
}


