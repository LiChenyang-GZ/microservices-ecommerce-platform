package comp5348.storeservice.dto;

import comp5348.storeservice.model.Payment;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderWithPaymentDTO {
    
    private OrderDTO order;
    private Payment payment;
}
