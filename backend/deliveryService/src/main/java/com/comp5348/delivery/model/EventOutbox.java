package com.comp5348.delivery.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An event waiting to be published to Kafka (transactional outbox, rule 2 / ADR-03).
 * Written in the same transaction as the status change it announces; EventOutboxPublisher sends it later.
 */
@Entity
@Table(name = "event_outbox", indexes = @Index(name = "idx_event_outbox_unsent", columnList = "sent_at, id"))
@Getter
@Setter
@NoArgsConstructor
public class EventOutbox {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Same as the envelope's eventId; consumers de-duplicate on it
    @Column(name = "event_id", nullable = false, unique = true)
    private UUID eventId;

    @Column(nullable = false)
    private String topic;

    // Kafka key (rule 5: same order, same partition)
    @Column(name = "order_id", nullable = false)
    private String orderId;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    // The whole envelope as JSON, written by EventCodec when the row is created
    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    // null = not sent yet
    @Column(name = "sent_at")
    private Instant sentAt;
}
