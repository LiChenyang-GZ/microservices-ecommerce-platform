package comp5348.storeservice.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    // Email queue name
    public static final String EMAIL_QUEUE_NAME = "email_queue";

    @Bean
    public Queue emailQueue() {
        // durable=true means the queue is persistent, even if RabbitMQ restarts, the queue and messages won't be lost
        return new Queue(EMAIL_QUEUE_NAME, true);
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
        
        // Set message persistence: ensure messages are not lost even if RabbitMQ restarts
        rabbitTemplate.setMandatory(true);
        rabbitTemplate.setReceiveTimeout(60000);
        rabbitTemplate.setReplyTimeout(60000);
        
        // Set message persistence via PostProcessor
        rabbitTemplate.setBeforePublishPostProcessors(message -> {
            message.getMessageProperties().setDeliveryMode(org.springframework.amqp.core.MessageDeliveryMode.PERSISTENT);
            return message;
        });
        
        return rabbitTemplate;
    }
}

