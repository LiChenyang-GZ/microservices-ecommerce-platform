package com.comp5348.delivery.dto;

import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.utils.DeliveryStatus;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.List;

@Getter
@Setter
public class DeliveryOrderDTO {
    private Long id;
    private String orderId;
    private DeliveryStatus deliveryStatus;
    private String userName;
    private String toAddress;
    private List<String> fromAddress;
    private String productName;
    private int quantity;
    private LocalDateTime creationTime;

    // 一个方便的构造函数，用于从Entity转换到DTO
    public DeliveryOrderDTO(Delivery delivery) {
        this.id = delivery.getId();
        this.orderId = delivery.getOrderId();
        this.deliveryStatus = delivery.getDeliveryStatus();
        this.userName = delivery.getUserName();
        this.toAddress = delivery.getToAddress();
        this.fromAddress = delivery.getFromAddress();
        this.productName = delivery.getProductName();
        this.quantity = delivery.getQuantity();
        this.creationTime = delivery.getCreationTime();
    }
}
