package comp5348.storeservice.dto;

import lombok.Data;

@Data
public class DeliveryStatusUpdateDTO {
    private Long orderId;
    private String deliveryId;
    private String status;      // e.g. CREATED, PICKED_UP, LOADED, DELIVERED
    private Long timestamp;     // epoch millis (optional)
}


