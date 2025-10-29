package com.comp5348.delivery.dto;

import lombok.Getter;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
public class DeliveryRequest {
    private String orderId;
    private String email;
    private String userName;
    private String toAddress;
    private List<String> fromAddress;
    private String productName;
    private int quantity;
    private String notificationUrl;
}

