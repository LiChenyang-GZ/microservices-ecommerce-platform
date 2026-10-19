package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.service.messaging.DeliveryDeadLetterAlertService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@Service
public class DeliveryDeadLetterService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryDeadLetterService.class);

    private final DeliveryDeadLetterAlertService alertService;
    private final RestTemplate restTemplate;
    private final String alertWebhookUrl;

    public DeliveryDeadLetterService(DeliveryDeadLetterAlertService alertService,
                                     RestTemplate restTemplate,
                                     @Value("${delivery.alert.webhook-url:}") String alertWebhookUrl) {
        this.alertService = alertService;
        this.restTemplate = restTemplate;
        this.alertWebhookUrl = alertWebhookUrl;
    }

    @RabbitListener(queues = RabbitMQConfig.DELIVERY_DEAD_LETTER_QUEUE_NAME)
    public void handleDeadLetter(Long deliveryId) {
        logger.error("[Delivery DLQ] deliveryId {} reached dead letter queue after repeated failures. Manual review required.", deliveryId);
        alertService.createAlert(deliveryId, "critical", "Delivery task repeatedly failed and hit DLQ");
        sendAlert(deliveryId);
    }

    private void sendAlert(Long deliveryId) {
        if (!StringUtils.hasText(alertWebhookUrl)) {
            logger.warn("Alert webhook not configured; skipping delivery DLQ alert.");
            return;
        }

        Map<String, Object> alertPayload = new HashMap<>();
        alertPayload.put("deliveryId", deliveryId);
        alertPayload.put("severity", "critical");
        alertPayload.put("reason", "Delivery task repeatedly failed and hit DLQ");
        alertPayload.put("timestamp", Instant.now().toString());

        try {
            restTemplate.postForEntity(alertWebhookUrl, alertPayload, Void.class);
            logger.info("Delivery DLQ alert sent for deliveryId {}", deliveryId);
        } catch (RestClientException ex) {
            logger.error("Failed to push DLQ alert for deliveryId {}: {}", deliveryId, ex.getMessage(), ex);
        }
    }
}
