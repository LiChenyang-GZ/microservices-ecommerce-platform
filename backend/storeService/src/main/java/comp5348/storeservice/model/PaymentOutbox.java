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
    private String eventType; // PAYMENT_PENDING, PAYMENT_SUCCESS, PAYMENT_FAILED
    
    @Column(columnDefinition = "TEXT")
    private String payload; // JSON格式的数据
    
    @Column(nullable = false)
    private String status; // PENDING, PROCESSED, FAILED
    
    @Column(nullable = false)
    private Integer retryCount;
    
    @Column(nullable = false)
    private LocalDateTime createdAt;
    
    private LocalDateTime processedAt;
    
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


