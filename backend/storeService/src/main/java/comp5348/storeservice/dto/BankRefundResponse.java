package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BankRefundResponse {
    
    private boolean success;
    private String refundTransactionId;
    private String message;
}


