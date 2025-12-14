package com.comp5348.delivery.service.messaging;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.NotificationMessage;
import com.comp5348.delivery.dto.NotificationRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.support.DefaultTransactionDefinition;

import java.time.LocalDateTime;
import java.util.Random;

@Service
public class DeliveryStatusUpdateService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryStatusUpdateService.class);

    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;
    private final PlatformTransactionManager transactionManager;
    private final Random random = new Random(); // Used to generate random numbers

    // Constants for simulating delivery
    private static final int BASE_WAIT_TIME_MS = 10000; // Base wait time: 10 seconds
    private static final double PACKAGE_LOSS_RATE = 0.00; // 5% package loss rate
    private static final int MAX_DELIVERY_RETRIES = 5; // Maximum retry attempts before DLQ

    @Autowired
    public DeliveryStatusUpdateService(DeliveryRepository deliveryRepository,
                                       RabbitTemplate rabbitTemplate,
                                       PlatformTransactionManager transactionManager) {
        this.deliveryRepository = deliveryRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.transactionManager = transactionManager;
    }

    // @RabbitListener tells Spring to keep listening to this queue, and call this method when a message arrives!
    @RabbitListener(queues = RabbitMQConfig.DELIVERY_QUEUE_NAME)
//    @Transactional // Ensures atomicity of database operations for each message processing
    public void processDelivery(Long deliveryId) {
        TransactionStatus status = transactionManager.getTransaction(new DefaultTransactionDefinition());
        try{
            logger.info("[Message Consumer] Received new task, processing delivery ID: {}", deliveryId);

            // 1. Get complete delivery information from database by ID
            // orElse(null) means return null if not found
            Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);

            if (delivery == null) {
                logger.warn("[Warning] Delivery task with ID {} not found in database, message will be discarded.", deliveryId);
                transactionManager.commit(status); // Commit transaction and return
                return; // End processing
            }

            if (!shouldContinueProcessing(delivery)) {
                logger.info("Delivery task {} is already in final state ({}), no need to process.", deliveryId, delivery.getDeliveryStatus());
                transactionManager.commit(status); // Commit transaction and return
                return;
            }

            // 2. Simulate real-world shipping delay
            try {
                // Fixed interval of 10 seconds for each state change
                Thread.sleep(BASE_WAIT_TIME_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt(); // Reset interrupt status
                logger.error("Thread interrupted while waiting", e);
            }

            // 2.5 During the delay, order may be cancelled. Re-read latest state from database, stop if already in final state
            delivery = deliveryRepository.findById(deliveryId).orElse(null);
            if (delivery == null) {
                transactionManager.commit(status);
                return;
            }
            if (!shouldContinueProcessing(delivery)) {
                transactionManager.commit(status);
                logger.info("Delivery task {} changed to final state ({}) during wait, stopping progression.", deliveryId, delivery.getDeliveryStatus());
                return;
            }

            // 3. Simulate possibility of package loss
            if (random.nextDouble() < PACKAGE_LOSS_RATE) {
                logger.warn("[Simulated Package Loss] Package for delivery ID {} was unfortunately lost!", deliveryId);
                delivery.setDeliveryStatus(DeliveryStatus.LOST);
            } else {
                // 4. If not lost, advance to next state
                updateDeliveryStatus(delivery);
            }

            // 5. Save updated state back to database
            delivery.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.saveAndFlush(delivery); // saveAndFlush writes to database immediately
            transactionManager.commit(status); // !! Manually commit transaction here !!
            logger.info("Delivery ID: {} status updated to -> {}, and saved to database.", deliveryId, delivery.getDeliveryStatus());

            // Email notifications are now handled by Store Service via webhook notifications
            // No need to send emails directly from Delivery Service

            // 6. Check if notificationUrl exists, if exists send Webhook notification
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());

                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
                logger.info("Created Webhook notification task for delivery ID: {}, sent to queue: {}", savedDelivery.getId(), RabbitMQConfig.NOTIFICATION_QUEUE_NAME);
            }

            // 7. Critical step: Determine if processing should continue
            // If status is not yet final (delivered or lost), put task ID back into queue for next processing
            if (shouldContinueProcessing(savedDelivery)) {
                rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
                logger.info("Delivery ID: {} not completed, re-queued.", savedDelivery.getId());
            } else {
                logger.info("[Processing Complete] Delivery ID: {} has reached final state.", savedDelivery.getId());
            }
        } catch (ObjectOptimisticLockingFailureException e) { // <-- Add catch at end of method
            // This is our elegant handling!
            logger.warn("[Optimistic Lock Conflict] Conflict occurred while processing delivery ID: {}. This is likely because it has been updated by another operation (e.g., cancellation). " +
                    "This message will be safely discarded.", deliveryId);
            if (!status.isCompleted()) {
                transactionManager.rollback(status); // Rollback transaction
            }
        } catch (Exception e) {
            // Handle all other exceptions with retry logic
            logger.error("[Delivery Processing Error] Failed to process delivery ID: {}. Error: {}", deliveryId, e.getMessage());
            
            if (!status.isCompleted()) {
                transactionManager.rollback(status);
            }
            
            // Try to retry before giving up and entering DLQ
            try {
                Delivery delivery = deliveryRepository.findById(deliveryId).orElse(null);
                if (delivery != null) {
                    int currentRetries = delivery.getRetryCount();
                    
                    if (currentRetries < MAX_DELIVERY_RETRIES) {
                        // Increment retry count and requeue
                        delivery.setRetryCount(currentRetries + 1);
                        delivery.setUpdateTime(LocalDateTime.now());
                        deliveryRepository.saveAndFlush(delivery);
                        
                        logger.warn("[Delivery Retry] Requeuing delivery ID: {} for retry {}/{}", 
                                deliveryId, currentRetries + 1, MAX_DELIVERY_RETRIES);
                        
                        // Put message back into queue for retry
                        rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, deliveryId);
                        return; // Don't rethrow, message will be retried
                    } else {
                        // Max retries exceeded, allow message to enter DLQ
                        logger.error("[Delivery Max Retries Exceeded] Delivery ID: {} has exceeded maximum retry attempts ({}). Entering DLQ.",
                                deliveryId, MAX_DELIVERY_RETRIES);
                    }
                }
            } catch (Exception retryException) {
                logger.error("[Retry Logic Error] Failed to handle retry for delivery ID: {}", deliveryId, retryException);
            }
            
            // If retries exceeded or retry logic failed, rethrow to enter DLQ
            throw e;
        }

    }

    /**
     * Private helper method to update from current state to next state
     */
    private void updateDeliveryStatus(Delivery delivery) {
        DeliveryStatus currentStatus = delivery.getDeliveryStatus();
        switch (currentStatus) {
            case CREATED:
                delivery.setDeliveryStatus(DeliveryStatus.PICKED_UP);
                break;
            case PICKED_UP:
                delivery.setDeliveryStatus(DeliveryStatus.DELIVERING);
                break;
            case DELIVERING:
                delivery.setDeliveryStatus(DeliveryStatus.RECEIVED);
                break;
            default:
                logger.info("Delivery ID: {} status is {}, no update needed.", delivery.getId(), currentStatus);
                break;
        }
    }

    /**
     * Determine if this delivery task should continue processing
     */
    private boolean shouldContinueProcessing(Delivery delivery) {
        DeliveryStatus status = delivery.getDeliveryStatus();
        return status != DeliveryStatus.RECEIVED
                && status != DeliveryStatus.LOST
                && status != DeliveryStatus.CANCELLED;
    }
}