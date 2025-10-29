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
    private static final int MAX_RETRIES = 5; // 定义最大重试次数

    private final RabbitTemplate rabbitTemplate; // <-- 我们需要它来重新发送消息

    @Autowired
    public DeadLetterQueueService(RabbitTemplate rabbitTemplate) { // <-- 修改构造函数
        this.rabbitTemplate = rabbitTemplate;
    }

    @RabbitListener(queues = RabbitMQConfig.DEAD_LETTER_QUEUE_NAME)
    public void processDeadLetterMessage(NotificationMessage failedMessage) {
        int currentRetries = failedMessage.getRetryCount();

        if (currentRetries < MAX_RETRIES) {
            // 1. 如果还没到最大次数，就进行重试
            failedMessage.setRetryCount(currentRetries + 1); // 增加重试次数

            logger.warn("【死信队列】收到失败消息，进行第 {}/{} 次重试. URL: {}",
                    failedMessage.getRetryCount(), MAX_RETRIES, failedMessage.getUrl());

            // 关键：不是直接调用WebhookService，而是把消息重新放回【原始的】通知队列
            // 这样可以让它重新走一遍完整的流程，更加解耦
            rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, failedMessage);

        } else {
            // 2. 如果已经达到最大次数，就放弃
            logger.error("【最终失败】消息已达到最大重试次数 ({})，将被丢弃. URL: {}",
                    MAX_RETRIES, failedMessage.getUrl());
        }
    }
}
