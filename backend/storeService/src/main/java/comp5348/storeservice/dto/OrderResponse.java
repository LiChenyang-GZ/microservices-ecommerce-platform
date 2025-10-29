package comp5348.storeservice.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {
    
    private boolean success;
    private String message;
    private OrderDTO order;
    private List<OrderDTO> orders;
    
    public static OrderResponse success(OrderDTO order, String message) {
        return new OrderResponse(true, message, order, null);
    }
    
    public static OrderResponse success(List<OrderDTO> orders, String message) {
        return new OrderResponse(true, message, null, orders);
    }
    
    public static OrderResponse error(String message) {
        return new OrderResponse(false, message, null, null);
    }
}
