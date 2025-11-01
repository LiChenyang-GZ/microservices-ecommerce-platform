package com.comp5348.email.service;

import com.comp5348.email.config.RabbitMQConfig;
import com.comp5348.email.dto.EmailMessage;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.rabbitmq.client.Channel;

import java.io.IOException;
import java.util.Map;

@Service
public class EmailQueueConsumer {

    private static final Logger logger = LoggerFactory.getLogger(EmailQueueConsumer.class);

    @Autowired
    private EmailService emailService;
    
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Listen to email queue and process email messages
     * This method will be called automatically when a message arrives in the queue
     * Uses manual acknowledgment mode to ensure messages are not lost
     */
    @RabbitListener(queues = RabbitMQConfig.EMAIL_QUEUE_NAME)
    public void processEmailMessage(EmailMessage message, Channel channel, Message amqpMessage) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        logger.info("Received email message from queue: emailType={}, orderId={}, email={}, deliveryTag={}", 
                message.getEmailType(), message.getOrderId(), message.getEmail(), deliveryTag);
        
        try {
            // Parse payload if exists
            Map<String, Object> payload = null;
            if (message.getPayload() != null && !message.getPayload().isEmpty()) {
                payload = objectMapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {});
            }
            
            // Process email based on type
            boolean emailSent = false;
            switch (message.getEmailType()) {
                case "ORDER_CANCELLED":
                    String reason = payload != null && payload.containsKey("reason") ? 
                            payload.get("reason").toString() : "Order cancelled";
                    emailService.sendOrderCancelledEmail(message.getEmail(), message.getOrderId(), reason);
                    emailSent = true;
                    logger.info("Order cancelled email sent successfully for orderId={}", message.getOrderId());
                    break;
                    
                case "ORDER_FAILED":
                    String errorReason = payload != null && payload.containsKey("reason") ? 
                            payload.get("reason").toString() : "Order failed";
                    emailService.sendOrderFailedEmail(message.getEmail(), message.getOrderId(), errorReason);
                    emailSent = true;
                    logger.info("Order failed email sent successfully for orderId={}", message.getOrderId());
                    break;
                    
                case "REFUND_SUCCESS":
                    String refundTxnId = payload != null && payload.containsKey("refundTxnId") ? 
                            payload.get("refundTxnId").toString() : "";
                    emailService.sendRefundSuccessEmail(message.getEmail(), message.getOrderId(), refundTxnId);
                    emailSent = true;
                    logger.info("Refund success email sent successfully for orderId={}", message.getOrderId());
                    break;
                    
                case "ORDER_PICKED_UP":
                    // Send delivery update email for PICKED_UP status
                    Long deliveryId = null;
                    if (payload != null && payload.containsKey("deliveryId")) {
                        deliveryId = Long.valueOf(payload.get("deliveryId").toString());
                    }
                    if (deliveryId != null) {
                        emailService.sendDeliveryStatusEmail(message.getEmail(), message.getOrderId(), deliveryId, "PICKED_UP");
                        emailSent = true;
                        logger.info("Order picked up email sent successfully for orderId={}, deliveryId={}", 
                                message.getOrderId(), deliveryId);
                    } else {
                        logger.warn("ORDER_PICKED_UP email missing deliveryId for orderId={}", message.getOrderId());
                        // Don't acknowledge, let message be requeued
                        channel.basicNack(deliveryTag, false, true);
                        return;
                    }
                    break;
                    
                case "ORDER_DELIVERING":
                    // Send delivery update email for DELIVERING status
                    Long deliveringDeliveryId = null;
                    if (payload != null && payload.containsKey("deliveryId")) {
                        deliveringDeliveryId = Long.valueOf(payload.get("deliveryId").toString());
                    }
                    if (deliveringDeliveryId != null) {
                        emailService.sendDeliveryStatusEmail(message.getEmail(), message.getOrderId(), deliveringDeliveryId, "DELIVERING");
                        emailSent = true;
                        logger.info("Order delivering email sent successfully for orderId={}, deliveryId={}", 
                                message.getOrderId(), deliveringDeliveryId);
                    } else {
                        logger.warn("ORDER_DELIVERING email missing deliveryId for orderId={}", message.getOrderId());
                        // Don't acknowledge, let message be requeued
                        channel.basicNack(deliveryTag, false, true);
                        return;
                    }
                    break;
                    
                case "ORDER_DELIVERED":
                    // Send delivery update email for DELIVERED status
                    Long deliveredDeliveryId = null;
                    if (payload != null && payload.containsKey("deliveryId")) {
                        deliveredDeliveryId = Long.valueOf(payload.get("deliveryId").toString());
                    }
                    if (deliveredDeliveryId != null) {
                        emailService.sendDeliveryStatusEmail(message.getEmail(), message.getOrderId(), deliveredDeliveryId, "DELIVERED");
                        emailSent = true;
                        logger.info("Order delivered email sent successfully for orderId={}, deliveryId={}", 
                                message.getOrderId(), deliveredDeliveryId);
                    } else {
                        logger.warn("ORDER_DELIVERED email missing deliveryId for orderId={}", message.getOrderId());
                        // Don't acknowledge, let message be requeued
                        channel.basicNack(deliveryTag, false, true);
                        return;
                    }
                    break;
                    
                case "ORDER_LOST":
                    String lostReason = payload != null && payload.containsKey("reason") ? 
                            payload.get("reason").toString() : "Package lost";
                    emailService.sendOrderCancelledEmail(message.getEmail(), message.getOrderId(), lostReason);
                    emailSent = true;
                    logger.info("Order lost email sent successfully for orderId={}", message.getOrderId());
                    break;
                    
                default:
                    logger.warn("Unknown email type: {} for orderId={}", message.getEmailType(), message.getOrderId());
                    // Unknown type, reject and don't requeue
                    channel.basicNack(deliveryTag, false, false);
                    return;
            }
            
            // Only acknowledge if email was sent successfully
            if (emailSent) {
                channel.basicAck(deliveryTag, false);
                logger.info("Email message acknowledged successfully for orderId={}, emailType={}", 
                        message.getOrderId(), message.getEmailType());
            } else {
                // If email wasn't sent, reject and requeue
                channel.basicNack(deliveryTag, false, true);
                logger.warn("Email not sent for orderId={}, message requeued", message.getOrderId());
            }
            
        } catch (Exception e) {
            logger.error("Error processing email message for orderId={}, emailType={}: {}", 
                    message.getOrderId(), message.getEmailType(), e.getMessage(), e);
            // Reject and requeue on error - this ensures retry when Email Service restarts
            try {
                channel.basicNack(deliveryTag, false, true);
                logger.info("Email message rejected and requeued for orderId={}, emailType={}", 
                        message.getOrderId(), message.getEmailType());
            } catch (IOException ioException) {
                logger.error("Failed to nack message for orderId={}: {}", message.getOrderId(), ioException.getMessage());
            }
        }
    }
}

