package com.comp5348.delivery.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
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
    public static final String DELIVERY_DEAD_LETTER_QUEUE_NAME = "delivery_dead_letter_queue";
    public static final String DEAD_LETTER_EXCHANGE_NAME = "dead_letter_exchange";
    public static final String DELIVERY_DEAD_LETTER_ROUTING_KEY = "delivery.deadletter";
    public static final String NOTIFICATION_DEAD_LETTER_ROUTING_KEY = "notification.deadletter";

    @Bean
    public Queue deliveryRequestQueue() {
        return QueueBuilder.durable(DELIVERY_QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", DELIVERY_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean // New Webhook queue bean
    public Queue notificationQueue() {
        return QueueBuilder.durable(NOTIFICATION_QUEUE_NAME)
                .withArgument("x-dead-letter-exchange", DEAD_LETTER_EXCHANGE_NAME)
                .withArgument("x-dead-letter-routing-key", NOTIFICATION_DEAD_LETTER_ROUTING_KEY)
                .build();
    }

    @Bean // New dead letter queue bean
    public Queue deadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE_NAME).build();
    }

    @Bean
    public Queue deliveryDeadLetterQueue() {
        return QueueBuilder.durable(DELIVERY_DEAD_LETTER_QUEUE_NAME).build();
    }

    @Bean
    public DirectExchange deadLetterExchange() {
        return new DirectExchange(DEAD_LETTER_EXCHANGE_NAME, true, false);
    }

    @Bean
    public Binding notificationDeadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue)
                .to(deadLetterExchange)
                .with(NOTIFICATION_DEAD_LETTER_ROUTING_KEY);
    }

    @Bean
    public Binding deliveryDeadLetterBinding(Queue deliveryDeadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deliveryDeadLetterQueue)
                .to(deadLetterExchange)
                .with(DELIVERY_DEAD_LETTER_ROUTING_KEY);
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