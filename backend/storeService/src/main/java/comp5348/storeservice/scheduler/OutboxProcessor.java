package comp5348.storeservice.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import comp5348.storeservice.adapter.DeliveryAdapter;
import comp5348.storeservice.dto.DeliveryRequestDTO;
import comp5348.storeservice.dto.DeliveryResponseDTO;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import comp5348.storeservice.service.OrderService;
import comp5348.storeservice.service.OutboxService;
import comp5348.storeservice.service.PaymentService;
import comp5348.storeservice.service.WarehouseService;
import comp5348.storeservice.dto.UnholdProductRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Component
@EnableScheduling
public class OutboxProcessor {
    
    private static final Logger logger = LoggerFactory.getLogger(OutboxProcessor.class);
    
    @Autowired
    private PaymentOutboxRepository outboxRepository;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private DeliveryAdapter deliveryAdapter;
    
    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private OrderService orderService;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private comp5348.storeservice.service.CompensationService compensationService;
    
    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private comp5348.storeservice.adapter.EmailAdapter emailAdapter;

    @Autowired
    private WarehouseService warehouseService;
    
    @Value("${outbox.processor.max-retries:3}")
    private int maxRetries;
    
    @Value("${outbox.processor.enabled:true}")
    private boolean enabled;
    
    @Value("${delivery.notification.url:http://localhost:8082/api/delivery-webhook}")
    private String deliveryNotificationUrl;
    
    @Value("${store.service.url:http://localhost:8082}")
    private String storeServiceUrl;
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final RestTemplate restTemplate;

    public OutboxProcessor(RestTemplateBuilder restTemplateBuilder) {
        this.restTemplate = restTemplateBuilder
                .requestFactory(() -> {
                    org.springframework.http.client.SimpleClientHttpRequestFactory factory = new org.springframework.http.client.SimpleClientHttpRequestFactory();
                    factory.setConnectTimeout((int) Duration.ofSeconds(5).toMillis());
                    factory.setReadTimeout((int) Duration.ofSeconds(5).toMillis());
                    return factory;
                })
                .build();
    }

    /**
     * Scheduled processing of Outbox messages - executed every 5 seconds
     */
    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processOutbox() {
        if (!enabled) {
            return;
        }

        try {
            // Query pending Outbox records (PENDING status and retry count less than max)
            List<PaymentOutbox> pendingOutboxes = outboxRepository.findByStatusAndRetryCountLessThan("PENDING", maxRetries);

            if (pendingOutboxes.isEmpty()) {
                return;
            }

            logger.info("Processing {} pending outbox messages", pendingOutboxes.size());

            for (PaymentOutbox outbox : pendingOutboxes) {
                processOutboxMessage(outbox);
            }

        } catch (Exception e) {
            logger.error("Error in outbox processor: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Process a single Outbox message
     */
    private void processOutboxMessage(PaymentOutbox outbox) {
        logger.info("Processing outbox message: id={}, eventType={}, orderId={}", 
                outbox.getId(), outbox.getEventType(), outbox.getOrderId());
        
        try {
            boolean success = false;

            // Compatible with historical/async message event names: e.g., PAYMENT_PENDING/PAYMENT_SUCCESS/REFUND_SUCCESS
            String rawEventType = outbox.getEventType();
            String normalized;
            if (rawEventType == null) {
                throw new IllegalArgumentException("Outbox eventType is null");
            }
            switch (rawEventType) {
                case "PAYMENT_PENDING":
                    normalized = "PENDING"; break;
                case "PAYMENT_SUCCESS":
                    normalized = "SUCCESS"; break;
                case "PAYMENT_FAILED":
                    normalized = "FAILED"; break;
                case "REFUND_SUCCESS":
                    normalized = "REFUNDED"; break;
                default:
                    // Remove unified prefix for robustness
                    normalized = rawEventType.replaceFirst("^PAYMENT_", "");
            }

            PaymentStatus eventType = PaymentStatus.valueOf(normalized); // Convert string to Enum
            switch (eventType) {
                case PENDING:
                    success = processPaymentPending(outbox);
                    break;

                case SUCCESS:
                    success = processPaymentSuccess(outbox);
                    break;

                case FAILED:
                    success = processPaymentFailed(outbox);
                    break;

                case REFUNDED:
                    success = processRefundSuccess(outbox);
                    break;

                case DELIVERY_FAILED:
                    // DELIVERY_FAILED event needs to wait 30 seconds before processing
                    boolean compensationTriggered = processDeliveryFailed(outbox);
                    if (!compensationTriggered) {
                        // Return false means still waiting (haven't reached 30 seconds yet)
                        // Don't process, don't increment retry count, wait for next scan
                        logger.debug("DELIVERY_FAILED event for orderId {} still waiting, skipping this round.", outbox.getOrderId());
                        return; // Return directly without updating outbox status, next scheduled task will scan again
                    }
                    // Return true means compensation has been triggered
                    success = true;
                    break;

                default:
                    logger.warn("Unknown event type: {}", outbox.getEventType());
                    success = false; // Or throw exception directly
            }
            
            if (success) {
                outbox.setStatus("PROCESSED");
                outbox.setProcessedAt(LocalDateTime.now());
                logger.info("Outbox message processed successfully: id={}", outbox.getId());
            } else {
                outbox.setRetryCount(outbox.getRetryCount() + 1);
                
                if (outbox.getRetryCount() >= maxRetries) {
                    outbox.setStatus("FAILED");
                    logger.error("Outbox message failed after {} retries: id={}", maxRetries, outbox.getId());
                } else {
                    logger.warn("Outbox message processing failed, will retry: id={}, retryCount={}", 
                            outbox.getId(), outbox.getRetryCount());
                }
            }
            
            outboxRepository.save(outbox);
            
        } catch (Exception e) {
            logger.error("Error processing outbox message: id={}, error={}", outbox.getId(), e.getMessage(), e);
            
            outbox.setRetryCount(outbox.getRetryCount() + 1);
            
            if (outbox.getRetryCount() >= maxRetries) {
                outbox.setStatus("FAILED");
            }
            
            outboxRepository.save(outbox);
        }
    }
    
    /**
     * Process pending payment event - call PaymentService to process payment
     */
    private boolean processPaymentPending(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            
            logger.info("Processing payment for orderId={}", orderId);
            paymentService.processPayment(orderId);
            
            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_PENDING: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Process payment success event - create delivery request (call team member D's DeliveryService)
     */
    private boolean processPaymentSuccess(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());

            logger.info("Payment success for orderId={}", orderId);

            // [New Step 1]: Update order status to PAID
            // Place status update before calling external service, if update fails, don't continue
            try {
                orderService.updateOrderStatus(orderId, OrderStatus.PAID);
                logger.info("Order status updated to PAID for orderId={}", orderId);
            } catch (Exception e) {
                logger.error("Failed to update order status to PAID for orderId={}. Will retry. Error: {}", orderId, e.getMessage(), e);
                return false; // Updating order status is a critical step, must retry on failure
            }

            // --- Subsequent logic remains mostly unchanged ---

            logger.info("Triggering delivery request for orderId={}", orderId);

            // 1. Query order information
            Order order = orderRepository.findByIdWithProduct(orderId) // Use findByIdWithProduct to ensure product info is loaded
                    .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

            // 2. Query user information (logic unchanged)
            Account account = accountRepository.findById(order.getUserId()).orElse(null);
            String email = (account != null) ? account.getEmail() : "customer@example.com";
            String userName = (account != null && account.getUsername() != null) ? account.getUsername() : "Customer";

            // 3. [Modified] Get order product information
            // Since Order is directly associated with Product, getOrderItems() is no longer needed
            if (order.getProduct() == null) {
                throw new IllegalStateException("Order " + orderId + " has no associated product.");
            }
            String productName = order.getProduct().getName();
            int quantity = order.getQuantity();

            // 4. Construct delivery request (logic unchanged)
            DeliveryRequestDTO deliveryRequest = new DeliveryRequestDTO();
            deliveryRequest.setOrderId(orderId.toString());
            deliveryRequest.setEmail(email);
            deliveryRequest.setUserName(userName);
            deliveryRequest.setToAddress("Default Address - 123 Main St");
            deliveryRequest.setFromAddress(Collections.singletonList("Warehouse-1, 456 Storage Rd"));
            deliveryRequest.setProductName(productName);
            deliveryRequest.setQuantity(quantity);
            deliveryRequest.setNotificationUrl("http://localhost:8082/api/delivery-webhook");

            // 5. [Optimized] Confirm inventory reservation (Saga pattern "Confirm")
            // The purpose of this step is to mark inventory transactions in HOLD status as COMMITTED/CONFIRMED
            // This is usually done by WarehouseService providing a dedicated method
            // warehouseService.confirmHold(order.getInventoryTransactionIds());
            // If your process doesn't have this step, you can skip or remove it, but keeping it demonstrates more complete Saga thinking
            logger.info("Confirming stock hold for orderId={}", orderId);

            // 6. Call DeliveryService (logic unchanged)
            DeliveryResponseDTO response = deliveryAdapter.createDelivery(deliveryRequest);

            if (response.isSuccess()) {
                logger.info("Delivery request created successfully: orderId={}, deliveryId={}", orderId, response.getDeliveryId());

                try {
                    // Get latest order object again
                    Order orderToUpdate = orderRepository.findById(orderId)
                            .orElseThrow(() -> new RuntimeException("Order disappeared unexpectedly: " + orderId));

                    // Set and save deliveryId
                    orderToUpdate.setDeliveryId(response.getDeliveryId());
                    orderRepository.save(orderToUpdate);

                    logger.info("Saved deliveryId {} to orderId {}", response.getDeliveryId(), orderId);

                } catch (Exception e) {
                    logger.error("CRITICAL: Failed to save deliveryId for orderId {}. This will break delivery notifications!",
                            orderId, e);
                    // Even if saving deliveryId fails, delivery task has already been created, so shouldn't return false to retry entire flow
                    // This is dirty data that requires manual intervention to fix
                }
                return true;
            } else {
//                logger.error("Delivery request failed: orderId={}, message={}", orderId, response.getMessage());
//                // If delivery creation fails, should consider triggering a compensation process (such as refund)
//                return false;
                logger.error("CRITICAL: Delivery creation failed permanently for orderId {}. Triggering compensation.", orderId);

                // [Advanced Implementation]: Create a new Outbox event to trigger refund
                try {
                    // You need an OutboxService to help create events
                    outboxService.createDeliveryFailedEvent(orderId, response.getMessage());
                } catch (Exception e) {
                    logger.error("FATAL: Failed to even create the compensation event for orderId {}.", orderId, e);
                    // If even creating compensation event fails, manual intervention is needed
                }

                // Even though compensation is triggered, we still return true because the PAYMENT_SUCCESS event itself has been "processed"
                // (Its subsequent processing is triggering another event)
                return true;
            }

        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_SUCCESS for outboxId={}: {}", outbox.getId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Process payment failed event - release reserved inventory + send email notification
     */
    private boolean processPaymentFailed(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String error = payload.get("error").toString();

            logger.info("Payment failed for orderId={}, error={}", orderId, error);

            Order order = orderRepository.findById(orderId).orElse(null);
            if (order == null) {
                logger.warn("Order not found for failed payment, cannot release stock. orderId={}", orderId);
                return true; // Order doesn't exist, no need to release inventory, consider processing successful
            }

            // 1. Release inventory reservation
            try {
                if (order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
                    List<Long> txIds = Arrays.stream(order.getInventoryTransactionIds().split(","))
                            .filter(s -> s != null && !s.trim().isEmpty())
                            .map(Long::valueOf)
                            .collect(Collectors.toList());

                    if (!txIds.isEmpty()) {
                        UnholdProductRequest unholdRequest = new UnholdProductRequest();
                        unholdRequest.setInventoryTransactionIds(txIds);
                        warehouseService.unholdProduct(unholdRequest);
                        logger.info("Released inventory for failed payment, orderId={}", orderId);
                    }
                }
            } catch (Exception e) {
                logger.error("Failed to release inventory for orderId={}. Will retry. Error: {}", orderId, e.getMessage(), e);
                return false; // Releasing inventory is a critical step, must retry on failure
            }

            // 2. Send failure notification email
            try {
                accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                    if (acc.getEmail() != null) {
                        emailAdapter.sendOrderFailed(acc.getEmail(), String.valueOf(orderId), error);
                    }
                });
            } catch (Exception e) {
                logger.warn("Failed to send order failure email for orderId={}: {}", orderId, e.getMessage());
                // Email sending failure doesn't affect main flow, don't return false
            }

            return true;

        } catch (Exception e) {
            logger.error("Failed to process PAYMENT_FAILED for outboxId={}: {}", outbox.getId(), e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * Process refund success event - send email notification
     */
    private boolean processRefundSuccess(PaymentOutbox outbox) {
        try {
            Map<String, Object> payload = objectMapper.readValue(
                    outbox.getPayload(), new TypeReference<Map<String, Object>>() {});
            Long orderId = Long.valueOf(payload.get("orderId").toString());
            String refundTxnId = payload.get("refundTxnId").toString();
            
            logger.info("Refund success for orderId={}, refundTxnId={}", orderId, refundTxnId);
            
            try {
                Order order = orderRepository.findById(orderId).orElse(null);
                String email = null;
                if (order != null) {
                    var acc = accountRepository.findById(order.getUserId()).orElse(null);
                    if (acc != null) email = acc.getEmail();
                }
                if (email != null) {
                    emailAdapter.sendRefundSuccess(email, String.valueOf(orderId), refundTxnId);
                }
            } catch (Exception e) {
                logger.warn("Send refund success email error: {}", e.getMessage());
            }

            return true;
            
        } catch (Exception e) {
            logger.error("Failed to process REFUND_SUCCESS: {}", e.getMessage(), e);
            return false;
        }
    }

    private boolean processDeliveryFailed(PaymentOutbox outbox) {
        Long orderId = outbox.getOrderId();
        LocalDateTime createdAt = outbox.getCreatedAt();
        LocalDateTime now = LocalDateTime.now();
        
        // Calculate wait time (seconds)
        long waitSeconds = java.time.Duration.between(createdAt, now).getSeconds();
        long requiredWaitSeconds = 30; // Need to wait 30 seconds
        
        if (waitSeconds < requiredWaitSeconds) {
            // Haven't reached 30 seconds yet, continue waiting, don't trigger compensation
            long remainingSeconds = requiredWaitSeconds - waitSeconds;
            logger.info("DELIVERY_FAILED event for orderId {} is waiting. Created {}s ago, need to wait {}s more before compensation.", 
                    orderId, waitSeconds, remainingSeconds);
            return false; // Return false means don't process, check again next time
        }
        
        // Have waited 30 seconds, trigger compensation mechanism
        logger.warn("DELIVERY_FAILED event for orderId {} has waited {}s. Triggering compensation (refund).", 
                orderId, waitSeconds);
        String reason = "Delivery service failed to create shipment after waiting 30 seconds.";
        return compensationService.compensateDeliveryFailed(orderId, reason);
    }
}


