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
    private String template; // Email template name, e.g. "order_picked_up_notice"

    @Getter
    @Setter
    private String payload; // Email content (can be JSON)

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

    /**
     * No longer counted (Obsidian 70): a wrong guess does not kill a code any more, store limits guesses per
     * IP + email instead. A11 (V13) counted them here. The column stays so tables that already have it keep
     * working; its default lets ddl-auto=update add it to a table that already has rows.
     */
    @Getter
    @Setter
    @Column(name = "failed_attempts", columnDefinition = "integer default 0 not null")
    private int failedAttempts = 0;

    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expireAt);
    }

    // Constructors
    public EmailOutbox() {}

    public EmailOutbox(String email, String code, String verificationType) {
        this.email = email;
        this.code = code;
        this.verificationType = verificationType;
        this.status = "PENDING";
        this.expireAt = LocalDateTime.now().plusMinutes(5); // Expires in 5 minutes
        this.retryCount = 0;
    }
}