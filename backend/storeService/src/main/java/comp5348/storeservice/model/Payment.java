package comp5348.storeservice.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import com.fasterxml.jackson.annotation.JsonIgnore;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "payments")
public class Payment {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true, nullable = false)
    private Long orderId;
    
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;
    
    private String bankTxnId;
    
    @Column(nullable = false)
    private BigDecimal amount;
    
    private String errorMessage;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime updatedAt;
    
    private LocalDateTime paymentDate;  // 记录支付成功的时间

    // Part of the charge key PAY-{paymentId}-{attempt}-{random}. Only Pay Now after a definite failure (FAILED) adds 1.
    // Nullable so ddl-auto=update can add the column to an existing table; read it through currentAttempt().
    private Integer attempt = 1;

    public int currentAttempt() {
        return attempt == null ? 1 : attempt;
    }

    // Random part of the keys, so nobody can compute them from the paymentId shown in the UI (review finding 1, 2).
    // A new one for every charge attempt; retries of the same attempt reuse the key stored on the task.
    // @JsonIgnore: Payment is returned by the payments / order APIs, and the keys must never leave store.
    @JsonIgnore
    private String payKeyNonce;

    @JsonIgnore
    private String refundKeyNonce;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (status == null) {
            status = PaymentStatus.PENDING;
        }
    }
    
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}


