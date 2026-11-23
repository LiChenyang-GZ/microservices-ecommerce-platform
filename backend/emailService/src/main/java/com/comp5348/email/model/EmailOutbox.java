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
     * Wrong guesses against this code (A11, V13). At MAX_FAILED_ATTEMPTS the code is marked EXPIRED.
     * The column definition carries a default so ddl-auto=update can add it to a table that already has rows.
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