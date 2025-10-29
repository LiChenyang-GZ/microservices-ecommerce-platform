package com.comp5348.delivery.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // 和组员C约定好的队列名称
    public static final String DELIVERY_QUEUE_NAME = "delivery_processing_queue";
    public static final String NOTIFICATION_QUEUE_NAME = "notification_queue"; // <-- 新增
    public static final String DEAD_LETTER_QUEUE_NAME = "dead_letter_queue";   // <-- 新增

    @Bean
    public Queue deliveryRequestQueue() {
        // durable=true 表示队列是持久化的，即使RabbitMQ重启，队列和里面的消息也不会丢失
        return new Queue(DELIVERY_QUEUE_NAME, true);
    }

    @Bean // 新增Webhook队列的Bean
    public Queue notificationQueue() {
        return new Queue(NOTIFICATION_QUEUE_NAME, true);
    }

    @Bean // 新增死信队列的Bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE_NAME, true);
    }

    /**
     * 定义一个消息转换器，使用 Jackson 将 Java 对象转换为 JSON 格式。
     * @return a MessageConverter bean
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * 自定义 RabbitTemplate，并为其设置我们刚刚定义好的 JSON 消息转换器。
     * 这样，每当你使用 rabbitTemplate.convertAndSend() 发送一个对象时，
     * 它都会被自动转换为 JSON 字符串。
     * @param connectionFactory Spring Boot 自动配置的连接工厂
     * @return a configured RabbitTemplate bean
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }
}