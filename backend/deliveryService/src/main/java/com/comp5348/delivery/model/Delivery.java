package com.comp5348.delivery.model;

import com.comp5348.delivery.utils.DeliveryStatus;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.utils.StringListConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Getter
@Setter
@NoArgsConstructor
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private int version;

    private String orderId; // 关联的订单ID

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus deliveryStatus;

    @Column(nullable = false)
    private String userName;

    @Column(nullable = false)
    private String toAddress;

    @Column(nullable = false)
    @Convert(converter = StringListConverter.class)
    private List<String> fromAddress;

    @Column(nullable = false)
    private String productName;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDateTime creationTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    @Column(nullable = true) // Notification URL可以是可选的，所以设为nullable = true更安全
    private String notificationUrl;

    public Delivery(DeliveryRequest requestEntity) {
        this.deliveryStatus = DeliveryStatus.CREATED;
        this.orderId = requestEntity.getOrderId();
        this.userName = requestEntity.getUserName();
        this.quantity = requestEntity.getQuantity();
        this.productName = requestEntity.getProductName();
        this.email = requestEntity.getEmail();
        this.fromAddress = requestEntity.getFromAddress();
        this.toAddress = requestEntity.getToAddress();
        this.creationTime = LocalDateTime.now();
        this.updateTime = LocalDateTime.now();
        this.notificationUrl = requestEntity.getNotificationUrl();
    }

}