package com.comp5348.delivery.controller;

import com.comp5348.delivery.service.messaging.DeliveryDeadLetterAlertService;
import com.comp5348.delivery.model.DeliveryDeadLetterAlert;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import com.comp5348.delivery.config.RabbitMQConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@RestController
@RequestMapping("/api/admin/dlq")
@RequiredArgsConstructor
public class DeliveryDeadLetterController {

    private final DeliveryDeadLetterAlertService alertService;

    @GetMapping("/delivery")
    public ResponseEntity<List<DeliveryDeadLetterAlertResponse>> list() {
        List<DeliveryDeadLetterAlertResponse> payload = alertService.getAllAlerts()
                .stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/delivery/{id}/resolve")
    public ResponseEntity<DeliveryDeadLetterAlertResponse> resolve(@PathVariable Long id) {
        try {
            DeliveryDeadLetterAlert updated = alertService.resolveAlert(id);
            return ResponseEntity.ok(toResponse(updated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    @PostMapping("/delivery/{id}/requeue")
    public ResponseEntity<DeliveryDeadLetterAlertResponse> requeue(@PathVariable Long id) {
        try {
            DeliveryDeadLetterAlert updated = alertService.requeueAlert(id);
            return ResponseEntity.ok(toResponse(updated));
        } catch (IllegalArgumentException ex) {
            return ResponseEntity.notFound().build();
        }
    }

    private DeliveryDeadLetterAlertResponse toResponse(DeliveryDeadLetterAlert alert) {
        return DeliveryDeadLetterAlertResponse.builder()
                .id(alert.getId())
                .deliveryId(alert.getDeliveryId())
                .severity(alert.getSeverity())
                .reason(alert.getReason())
                .alertTimestamp(alert.getAlertTimestamp())
                .resolved(alert.isResolved())
                .resolvedAt(alert.getResolvedAt())
                .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class DeliveryDeadLetterAlertResponse {
        private Long id;
        private Long deliveryId;
        private String severity;
        private String reason;
        private Instant alertTimestamp;
        private boolean resolved;
        private Instant resolvedAt;
    }
}
