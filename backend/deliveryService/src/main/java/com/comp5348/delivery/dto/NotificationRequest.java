package com.comp5348.delivery.dto;

import com.comp5348.delivery.utils.DeliveryStatus;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationRequest {

    private Long deliveryId;
    private DeliveryStatus status;

}
