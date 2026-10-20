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
@Table(name = "delivery")
@Getter
@Setter
@NoArgsConstructor
public class Delivery {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Version
    private int version;

    // Associated order ID. Unique (DeliverySchemaUpgrade adds the index): one order, one delivery row, and the
    // row is also the cancel marker when a cancel arrives before OrderPaid (sequence C).
    private String orderId;

    // userName, toAddress, fromAddress, productName and email may be null: a cancel marker has none of them

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private DeliveryStatus deliveryStatus;

    private String userName;

    private String toAddress;

    @Convert(converter = StringListConverter.class)
    private List<String> fromAddress;

    private String productName;

    private String email;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private LocalDateTime creationTime;

    @Column(nullable = false)
    private LocalDateTime updateTime;

    @Column(nullable = true) // Notification URL can be optional, so nullable = true is safer
    private String notificationUrl;

    @Column(nullable = false)
    private int retryCount = 0; // Track failed processing attempts

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
        this.retryCount = 0; // Initialize retry count to 0
    }

}