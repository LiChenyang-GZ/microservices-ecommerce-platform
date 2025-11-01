package com.comp5348.delivery.service.messaging;


import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

@Service
public class NotificationService {

    private static final Logger logger = LoggerFactory.getLogger(NotificationService.class);

    private final RestTemplate restTemplate;
    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public NotificationService(RestTemplate restTemplate, RabbitTemplate rabbitTemplate) {
        this.restTemplate = restTemplate;
        this.rabbitTemplate = rabbitTemplate;
    }

    public void sendNotification(NotificationMessage message) {
        String url = message.getUrl();
        logger.info("[Notification Send] Preparing to send status update to URL: {} with status: {}", url, message.getPayload().getStatus());

        try {
            // Attempt to send POST request. We temporarily do not care about the response content.
            restTemplate.postForEntity(url, message.getPayload(), String.class);
            logger.info("[Notification Send Success] Successfully notified URL: {}", url);

        } catch (RestClientException e) {
            // If sending fails (e.g., target service is offline, or URL is incorrect)
            logger.error("[Notification Send Failed] Error sending notification to URL: {}: {}", url, e.getMessage());

            // Put the failed message into the dead letter queue for later processing
            rabbitTemplate.convertAndSend(RabbitMQConfig.DEAD_LETTER_QUEUE_NAME, message);
            logger.warn("Failed Notification message sent to dead letter queue: {}", RabbitMQConfig.DEAD_LETTER_QUEUE_NAME);
        }
    }
}
