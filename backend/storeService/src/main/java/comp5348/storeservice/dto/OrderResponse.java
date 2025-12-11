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
    private String responseCode;
    private OrderDTO order;
    private List<OrderDTO> orders;
    private Object data; // generic payload for non-order responses (e.g., inventory audit logs)
    
    public static OrderResponse success(OrderDTO order, String message) {
        return new OrderResponse(true, message, "200", order, null, null);
    }
    
    public static OrderResponse success(List<OrderDTO> orders, String message) {
        return new OrderResponse(true, message, "200", null, orders, null);
    }

    /**
     * Generic success factory for arbitrary data payloads.
     */
    public static OrderResponse success(Object data, String message) {
        return new OrderResponse(true, message, "200", null, null, data);
    }
    
    public static OrderResponse error(String message) {
        return new OrderResponse(false, message, "500", null, null, null);
    }
}
