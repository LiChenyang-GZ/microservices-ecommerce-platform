package com.comp5348.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable; // Message queue transfer objects should implement Serializable interface

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    private NotificationRequest payload; // "Message content"
    private String url;             // "Receiver address"
    private int retryCount = 0; // <-- New field, defaults to 0

    // Create a new constructor for initial message creation
    public NotificationMessage(NotificationRequest payload, String url) {
        this.payload = payload;
        this.url = url;
        this.retryCount = 0; // When first created, retry count is 0
    }
}
