package comp5348.storeservice.dto;

import lombok.Data;

@Data
public class DeliveryNotificationDTO {

    private Long deliveryId;
    private String status;
}