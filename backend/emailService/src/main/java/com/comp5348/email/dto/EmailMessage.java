package com.comp5348.email.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class EmailMessage implements Serializable {
    
    private String emailType; // ORDER_CANCELLED, ORDER_FAILED, REFUND_SUCCESS, ORDER_PICKED_UP, ORDER_LOST
    private String email;
    private String orderId;
    private String payload; // JSON string containing additional data (reason, refundTxnId, deliveryId, etc.)
    
    public EmailMessage(String emailType, String email, String orderId) {
        this.emailType = emailType;
        this.email = email;
        this.orderId = orderId;
    }
}

