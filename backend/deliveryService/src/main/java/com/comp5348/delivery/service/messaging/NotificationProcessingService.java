package com.comp5348.delivery.service.messaging;


import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.NotificationMessage;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class NotificationProcessingService {

    private final NotificationService notificationService;

    @Autowired
    public NotificationProcessingService(NotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @RabbitListener(queues = RabbitMQConfig.NOTIFICATION_QUEUE_NAME)
    public void processWebhook(NotificationMessage message) {
        notificationService.sendNotification(message);
    }

}
