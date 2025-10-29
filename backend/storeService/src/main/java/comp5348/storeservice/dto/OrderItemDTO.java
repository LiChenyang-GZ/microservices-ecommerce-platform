package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemDTO {
    
    private Long id;
    private Long orderId;
    private Long productId;
    private String productName;
    private Integer qty;
    private BigDecimal unitPrice;
    private BigDecimal totalPrice;
}
