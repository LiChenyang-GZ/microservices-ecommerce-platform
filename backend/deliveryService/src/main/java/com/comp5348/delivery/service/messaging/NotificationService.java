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
        logger.info("【Notification发送】准备向URL: {} 发送状态更新: {}", url, message.getPayload().getStatus());

        try {
            // 尝试发送POST请求。我们暂时不关心对方的响应内容。
            restTemplate.postForEntity(url, message.getPayload(), String.class);
            logger.info("【Notification发送成功】已成功通知URL: {}", url);

        } catch (RestClientException e) {
            // 如果发送失败 (比如对方服务不在线，或者URL错误)
            logger.error("【Notification发送失败】向URL: {} 发送通知时出错: {}", url, e.getMessage());

            // 将发送失败的消息放入死信队列，以便后续处理
            rabbitTemplate.convertAndSend(RabbitMQConfig.DEAD_LETTER_QUEUE_NAME, message);
            logger.warn("已将发送失败的Notification消息放入死信队列: {}", RabbitMQConfig.DEAD_LETTER_QUEUE_NAME);
        }
    }
}
