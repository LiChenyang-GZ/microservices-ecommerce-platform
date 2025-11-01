package comp5348.storeservice.adapter;

import com.fasterxml.jackson.databind.ObjectMapper;
import comp5348.storeservice.config.RabbitMQConfig;
import comp5348.storeservice.dto.EmailMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

@Component
public class EmailAdapter {

    private static final Logger logger = LoggerFactory.getLogger(EmailAdapter.class);

    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public EmailAdapter(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Send order cancelled email via RabbitMQ
     */
    public void sendOrderCancelled(String email, String orderId, String reason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_CANCELLED", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order cancelled email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order cancelled email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }

    /**
     * Send order failed email via RabbitMQ
     */
    public void sendOrderFailed(String email, String orderId, String reason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_FAILED", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order failed email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order failed email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }

    /**
     * Send refund success email via RabbitMQ
     */
    public void sendRefundSuccess(String email, String orderId, String refundTxnId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("refundTxnId", refundTxnId);
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("REFUND_SUCCESS", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Refund success email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send refund success email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }
    
    /**
     * Send order picked up email via RabbitMQ
     */
    public void sendOrderPickedUp(String email, String orderId, Long deliveryId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deliveryId", deliveryId);
            payload.put("status", "PICKED_UP");
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_PICKED_UP", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order picked up email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order picked up email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }
    
    /**
     * Send order lost email via RabbitMQ
     */
    public void sendOrderLost(String email, String orderId, String reason) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("reason", reason);
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_LOST", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order lost email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order lost email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }
    
    /**
     * Send order delivering email via RabbitMQ
     */
    public void sendOrderDelivering(String email, String orderId, Long deliveryId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deliveryId", deliveryId);
            payload.put("status", "DELIVERING");
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_DELIVERING", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order delivering email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order delivering email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }
    
    /**
     * Send order delivered email via RabbitMQ
     */
    public void sendOrderDelivered(String email, String orderId, Long deliveryId) {
        try {
            Map<String, Object> payload = new HashMap<>();
            payload.put("deliveryId", deliveryId);
            payload.put("status", "DELIVERED");
            String payloadJson = objectMapper.writeValueAsString(payload);
            
            EmailMessage message = new EmailMessage("ORDER_DELIVERED", email, orderId, payloadJson);
            rabbitTemplate.convertAndSend(RabbitMQConfig.EMAIL_QUEUE_NAME, message);
            logger.info("Order delivered email message sent to queue for orderId={}, email={}", orderId, email);
        } catch (Exception e) {
            logger.error("Failed to send order delivered email message to queue for orderId={}: {}", orderId, e.getMessage(), e);
        }
    }
}


