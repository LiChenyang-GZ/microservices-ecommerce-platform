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

    // Queue names agreed with team member C
    public static final String DELIVERY_QUEUE_NAME = "delivery_processing_queue";
    public static final String NOTIFICATION_QUEUE_NAME = "notification_queue"; // <-- New
    public static final String DEAD_LETTER_QUEUE_NAME = "dead_letter_queue";   // <-- New

    @Bean
    public Queue deliveryRequestQueue() {
        // durable=true means the queue is persistent, even if RabbitMQ restarts, the queue and messages won't be lost
        return new Queue(DELIVERY_QUEUE_NAME, true);
    }

    @Bean // New Webhook queue bean
    public Queue notificationQueue() {
        return new Queue(NOTIFICATION_QUEUE_NAME, true);
    }

    @Bean // New dead letter queue bean
    public Queue deadLetterQueue() {
        return new Queue(DEAD_LETTER_QUEUE_NAME, true);
    }

    /**
     * Define a message converter that uses Jackson to convert Java objects to JSON format.
     * @return a MessageConverter bean
     */
    @Bean
    public MessageConverter messageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    /**
     * Customize RabbitTemplate and set the JSON message converter we just defined.
     * This way, whenever you use rabbitTemplate.convertAndSend() to send an object,
     * it will be automatically converted to a JSON string.
     * @param connectionFactory Spring Boot auto-configured connection factory
     * @return a configured RabbitTemplate bean
     */
    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate rabbitTemplate = new RabbitTemplate(connectionFactory);
        rabbitTemplate.setMessageConverter(messageConverter());
        return rabbitTemplate;
    }
}