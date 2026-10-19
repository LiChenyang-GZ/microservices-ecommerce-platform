package com.comp5348.delivery.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "delivery_dead_letter_alert")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeliveryDeadLetterAlert {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long deliveryId;

    @Column(nullable = false)
    private String severity;

    @Column(nullable = false, length = 1024)
    private String reason;

    @Column(nullable = false)
    private Instant alertTimestamp;

    @Column(nullable = false)
    private boolean resolved;

    private Instant resolvedAt;
}
