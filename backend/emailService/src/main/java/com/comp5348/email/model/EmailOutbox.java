package com.comp5348.email.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Data
public class EmailOutbox {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Getter
    @Setter
    private String orderId;



    @Getter
    @Setter
    private String template; // 邮件模板名，如 "order_picked_up_notice"

    @Getter
    @Setter
    private String payload; // 邮件内容（可以是JSON）

    @Getter
    @Setter
    private String status; // PENDING, SENT, FAILED

    @Getter
    @Setter
    private int retryCount = 0;

    @Getter
    @Setter
    private String email;
    
    @Getter
    @Setter
    private String code;
    
    @Getter
    @Setter
    private LocalDateTime expireAt;
    
    @Getter
    @Setter
    private String verificationType; // REGISTRATION, PASSWORD_RESET, etc.

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireAt);
    }

    // 构造函数
    public EmailOutbox() {}

    public EmailOutbox(String email, String code, String verificationType) {
        this.email = email;
        this.code = code;
        this.verificationType = verificationType;
        this.status = "PENDING";
        this.expireAt = LocalDateTime.now().plusMinutes(5); // 5分钟过期
        this.retryCount = 0;
    }
}