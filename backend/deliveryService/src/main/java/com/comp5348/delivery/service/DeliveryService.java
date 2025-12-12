package com.comp5348.delivery.service;

import com.comp5348.delivery.config.RabbitMQConfig;
import com.comp5348.delivery.dto.DeliveryOrderDTO;
import com.comp5348.delivery.dto.DeliveryRequest;
import com.comp5348.delivery.dto.NotificationMessage;
import com.comp5348.delivery.dto.NotificationRequest;
import com.comp5348.delivery.model.Delivery;
import com.comp5348.delivery.repository.DeliveryRepository;
import com.comp5348.delivery.utils.DeliveryStatus;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
public class DeliveryService {

    private static final Logger logger = LoggerFactory.getLogger(DeliveryService.class);

    // Dependency injection: repository and RabbitMQ template
    private final DeliveryRepository deliveryRepository;
    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public DeliveryService(DeliveryRepository deliveryRepository, RabbitTemplate rabbitTemplate) {
        this.deliveryRepository = deliveryRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * Create a new delivery task
     * @param request Request data received from API
     * @return DTO object for API response
     */
    @Transactional // Ensures atomicity of database operations (all success or all failure)
    public DeliveryOrderDTO createDelivery(DeliveryRequest request) {
        logger.info("Starting to create new delivery task, requester: {}", request.getEmail());

        // 1. Convert request DTO to database entity
        Delivery newDelivery = new Delivery(request);

        // 2. Save entity to database
        Delivery savedDelivery = deliveryRepository.save(newDelivery);
        logger.info("Delivery task successfully saved to database, ID: {}", savedDelivery.getId());

        // 3. Send a message to RabbitMQ queue to notify background service to process this delivery
        //    We only send the ID, background service can fetch complete info from database
        String queueName = "delivery_processing_queue"; // Queue name should be defined in config file
        rabbitTemplate.convertAndSend(queueName, savedDelivery.getId());
        logger.info("Sent new task ID {} to queue {}", savedDelivery.getId(), queueName);

        rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
        logger.info("Sent new task ID {} to queue {}", savedDelivery.getId(), RabbitMQConfig.DELIVERY_QUEUE_NAME);

        // 4. Convert saved entity to response DTO and return to Controller
        return new DeliveryOrderDTO(savedDelivery);
    }

    /**
     * Cancel a delivery task (verify if it belongs to the specified user)
     * @param deliveryId ID of the delivery task to be cancelled
     * @param userEmail User email (used to verify if order belongs to this user)
     * @return true if successfully cancelled; false if cannot be cancelled due to status or other reasons
     */
    @Transactional
    public boolean cancelDelivery(Long deliveryId, String userEmail) {
        logger.info("Received cancellation request for delivery ID: {}, user: {}", deliveryId, userEmail);

        // 1. Find order by ID
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);

        // If order doesn't exist, return failure directly
        if (deliveryOptional.isEmpty()) {
            logger.warn("Cannot cancel: Delivery ID {} not found in database", deliveryId);
            return false;
        }

        Delivery deliveryToCancel = deliveryOptional.get();
        
        // 2. Verify if order belongs to this user
        if (!deliveryToCancel.getEmail().equals(userEmail)) {
            logger.warn("Cannot cancel: User {} attempted to cancel delivery order {} that doesn't belong to them (belongs to {})", 
                    userEmail, deliveryId, deliveryToCancel.getEmail());
            return false;
        }
        
        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();

        // 3. Core business rule: cancellation only allowed in specific statuses
        // Assumption: Once delivery starts (DELIVERING), it cannot be cancelled
        if (currentStatus == DeliveryStatus.CREATED || currentStatus == DeliveryStatus.PICKED_UP) {

            // 4. Execute cancellation
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            logger.info("Delivery ID: {} status successfully updated to -> CANCELLED", savedDelivery.getId());

            // 5. [Critical] Send webhook notification to tell Store service "cancellation succeeded"!
            // This reuses our Phase 3 results, forming a complete closed loop
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());

                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
                logger.info("Created webhook notification for cancellation of delivery ID: {}", savedDelivery.getId());
            }

            return true; // Return success

        } else {
            // 6. If status doesn't meet conditions, reject cancellation
            logger.warn("Cannot cancel delivery ID: {}, current status is {}, cannot be revoked.",
                    deliveryId, currentStatus);
            return false; // Return failure
        }
    }
    
    /**
     * Cancel a delivery task (no user verification, for internal calls)
     * @deprecated Please use cancelDelivery(Long, String) method
     */
    @Deprecated
    @Transactional
    public boolean cancelDelivery(Long deliveryId) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);
        if (deliveryOptional.isEmpty()) {
            return false;
        }
        Delivery deliveryToCancel = deliveryOptional.get();
        DeliveryStatus currentStatus = deliveryToCancel.getDeliveryStatus();
        
        if (currentStatus == DeliveryStatus.CREATED || currentStatus == DeliveryStatus.PICKED_UP) {
            deliveryToCancel.setDeliveryStatus(DeliveryStatus.CANCELLED);
            deliveryToCancel.setUpdateTime(LocalDateTime.now());
            Delivery savedDelivery = deliveryRepository.save(deliveryToCancel);
            
            if (savedDelivery.getNotificationUrl() != null && !savedDelivery.getNotificationUrl().isEmpty()) {
                NotificationRequest payload = new NotificationRequest(savedDelivery.getId(), savedDelivery.getDeliveryStatus());
                NotificationMessage message = new NotificationMessage(payload, savedDelivery.getNotificationUrl());
                rabbitTemplate.convertAndSend(RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
            }
            return true;
        }
        return false;
    }

    /**
     * Create delivery tasks in batch
     * @param requests List of delivery requests
     * @return List of created delivery task DTOs
     */
    @Transactional
    public List<DeliveryOrderDTO> createDeliveryBatch(List<DeliveryRequest> requests) {
        List<Delivery> deliveryOrders = new ArrayList<>();

        for (DeliveryRequest request : requests) {
            deliveryOrders.add(new Delivery(request));
        }

        // Save all entities at once, more efficient than saving in loop
        List<Delivery> savedDeliveries = deliveryRepository.saveAll(deliveryOrders);

        // Send message for each saved entity and convert to DTO
        for (Delivery savedDelivery : savedDeliveries) {
            rabbitTemplate.convertAndSend(RabbitMQConfig.DELIVERY_QUEUE_NAME, savedDelivery.getId());
            logger.info("Sent batch-created new task ID {} to queue {}", savedDelivery.getId(), RabbitMQConfig.DELIVERY_QUEUE_NAME);
        }

        return savedDeliveries.stream()
                .map(DeliveryOrderDTO::new)
                .collect(Collectors.toList());
    }

    /**
     * Get detailed information of a single delivery task by ID (verify if it belongs to specified user)
     * @param deliveryId Delivery task ID
     * @param userEmail User email (used to verify if order belongs to this user)
     * @return Delivery task DTO, returns null if not found or doesn't belong to user
     */
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId, String userEmail) {
        Optional<Delivery> deliveryOptional = deliveryRepository.findById(deliveryId);
        
        if (deliveryOptional.isEmpty()) {
            return null;
        }
        
        Delivery delivery = deliveryOptional.get();
        
        // Verify if order belongs to this user
        if (!delivery.getEmail().equals(userEmail)) {
            logger.warn("User {} attempted to access delivery {} which belongs to {}", 
                    userEmail, deliveryId, delivery.getEmail());
            return null;
        }
        
        return new DeliveryOrderDTO(delivery);
    }
    
    /**
     * Get detailed information of a single delivery task by ID (no user verification, for internal calls)
     * @deprecated Please use getDeliveryOrder(Long, String) method
     */
    @Deprecated
    @Transactional(readOnly = true)
    public DeliveryOrderDTO getDeliveryOrder(Long deliveryId) {
        return deliveryRepository.findById(deliveryId)
                .map(DeliveryOrderDTO::new)
                .orElse(null);
    }

    /**
     * Get all delivery tasks for a customer by email
     * @param email Customer email
     * @return List of delivery task DTOs
     */
    @Transactional(readOnly = true)
    public List<DeliveryOrderDTO> getAllDeliveryOrders(String email) {
        // Fetch deliveries ordered by ID descending so newest deliveries appear first
        List<Delivery> deliveries = deliveryRepository.findByEmailOrderByIdDesc(email);

        // Convert entity list to DTO list
        return deliveries.stream()
            .map(DeliveryOrderDTO::new)
            .collect(Collectors.toList());
    }

    /**
     * Cancel delivery by order ID: only allowed when status is CREATED
     */
    @Transactional
    public boolean cancelByOrderId(String orderId) {
        java.util.Optional<Delivery> opt = deliveryRepository.findByOrderId(orderId);
        if (opt.isEmpty()) return false;
        Delivery d = opt.get();
        if (d.getDeliveryStatus() != com.comp5348.delivery.utils.DeliveryStatus.CREATED) {
            return false;
        }
        d.setDeliveryStatus(com.comp5348.delivery.utils.DeliveryStatus.CANCELLED);
        d.setUpdateTime(java.time.LocalDateTime.now());
        Delivery saved = deliveryRepository.save(d);

        // Email notifications are now handled by Store Service via webhook notifications
        // No need to send emails directly from Delivery Service

        // If notification URL exists, send webhook notification to StoreService for refund/rollback inventory/email
        try {
            if (saved.getNotificationUrl() != null && !saved.getNotificationUrl().isEmpty()) {
                com.comp5348.delivery.dto.NotificationRequest payload = new com.comp5348.delivery.dto.NotificationRequest(saved.getId(), saved.getDeliveryStatus());
                com.comp5348.delivery.dto.NotificationMessage message = new com.comp5348.delivery.dto.NotificationMessage(payload, saved.getNotificationUrl());
                rabbitTemplate.convertAndSend(com.comp5348.delivery.config.RabbitMQConfig.NOTIFICATION_QUEUE_NAME, message);
            }
        } catch (Exception ignore) {}
        return true;
    }
}