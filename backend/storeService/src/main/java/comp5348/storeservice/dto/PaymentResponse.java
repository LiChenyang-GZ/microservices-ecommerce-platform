package comp5348.storeservice.dto;

import comp5348.storeservice.model.Payment;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class PaymentResponse {
    
    private boolean success;
    private Payment data;
    private String message;
}


