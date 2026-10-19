package comp5348.storeservice.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Data
@NoArgsConstructor
@AllArgsConstructor
@Table(name = "payment_outbox")
public class PaymentOutbox {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(nullable = false)
    private Long orderId;
    
    @Column(nullable = false)
    private String eventType; // Money tasks: PAY, REFUND. Other events: SUCCESS, REFUNDED, DELIVERY_FAILED, FAILED
    
    @Column(columnDefinition = "TEXT")
    private String payload; // JSON formatted data
    
    @Column(nullable = false)
    private String status; // PENDING, PROCESSED, FAILED
    
    @Column(nullable = false)
    private Integer retryCount;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime processedAt;

    // PAY / REFUND only: the key bank deduplicates on. Fixed when the task is written; every retry reuses it.
    private String idempotencyKey;

    // PAY / REFUND only: do not try before this time (set after 5 fast retries got no answer: retry every 10 minutes)
    private LocalDateTime nextAttemptAt;
    
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        if (status == null) {
            status = "PENDING";
        }
        if (retryCount == null) {
            retryCount = 0;
        }
    }
}


