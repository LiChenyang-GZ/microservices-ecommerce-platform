package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.model.DeliveryDeadLetterAlert;
import com.comp5348.delivery.repository.DeliveryDeadLetterAlertRepository;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Sort;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class DeliveryDeadLetterAlertService {

    private final DeliveryDeadLetterAlertRepository repository;
    private final RabbitTemplate rabbitTemplate;

    public DeliveryDeadLetterAlert createAlert(Long deliveryId, String severity, String reason) {
        DeliveryDeadLetterAlert alert = DeliveryDeadLetterAlert.builder()
                .deliveryId(deliveryId)
                .severity(severity)
                .reason(reason)
                .alertTimestamp(Instant.now())
                .resolved(false)
                .build();
        return repository.save(alert);
    }

    public List<DeliveryDeadLetterAlert> getAllAlerts() {
        return repository.findAll(Sort.by(Sort.Direction.DESC, "alertTimestamp"));
    }

    public DeliveryDeadLetterAlert resolveAlert(Long alertId) {
        DeliveryDeadLetterAlert alert = findById(alertId);
        if (!alert.isResolved()) {
            alert.setResolved(true);
            alert.setResolvedAt(Instant.now());
            repository.save(alert);
        }
        return alert;
    }

    public DeliveryDeadLetterAlert requeueAlert(Long alertId) {
        DeliveryDeadLetterAlert alert = findById(alertId);
        rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, alert.getDeliveryId());
        alert.setResolved(true);
        alert.setResolvedAt(Instant.now());
        alert.setReason(alert.getReason() + " | Requeued via admin");
        return repository.save(alert);
    }

    private DeliveryDeadLetterAlert findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Delivery DLQ alert not found: " + id));
    }
}
