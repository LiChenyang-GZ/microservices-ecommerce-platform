package com.comp5348.delivery.dto;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable; // 消息队列传输对象建议实现序列化接口

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage {

    private NotificationRequest payload; // "信的内容"
    private String url;             // "收信地址"
    private int retryCount = 0; // <-- 新增字段，并默认为0

    // 创建一个新的构造函数，用于首次创建消息
    public NotificationMessage(NotificationRequest payload, String url) {
        this.payload = payload;
        this.url = url;
        this.retryCount = 0; // 首次创建时，重试次数为0
    }
}
