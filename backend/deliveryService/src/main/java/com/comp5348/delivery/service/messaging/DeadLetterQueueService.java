package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.NotificationMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterQueueService {

    private static final Logger logger = LoggerFactory.getLogger(DeadLetterQueueService.class);
    private static final int MAX_RETRIES = 5; // Define maximum retry count

    private final RabbitTemplate rabbitTemplate; // <-- We need it to resend messages

    @Autowired
    public DeadLetterQueueService(RabbitTemplate rabbitTemplate) { // <-- Modified constructor
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE_NAME)
    public void processDeadLetterMessage(NotificationMessage failedMessage) {
        int currentRetries = failedMessage.getRetryCount();

        if (currentRetries < MAX_RETRIES) {
            // 1. If not reached maximum count, retry
            failedMessage.setRetryCount(currentRetries + 1); // Increment retry count

            logger.warn("[Dead Letter Queue] Received failed message, performing retry {}/{}: URL: {}",
                    failedMessage.getRetryCount(), MAX_RETRIES, failedMessage.getUrl());

            // Key: Instead of directly calling WebhookService, put message back into [original] notification queue
            // This allows it to go through the complete process again, more decoupled
            rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, failedMessage);

        } else {
            // 2. If maximum count reached, give up
            logger.error("[Final Failure] Message has reached maximum retry count ({}), will be discarded: URL: {}",
                    MAX_RETRIES, failedMessage.getUrl());
        }
    }
}
