package comp5348.storeservice.service;

import comp5348.storeservice.adapter.DeliveryAdapter;
import comp5348.storeservice.adapter.EmailAdapter;
import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.AccountRepository;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.repository.PaymentOutboxRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Transactional
public class OrderService {

    private static final Logger logger = LoggerFactory.getLogger(OrderService.class);

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AccountRepository accountRepository;

    @Autowired
    private PaymentOutboxRepository paymentOutboxRepository; // [Modification Point 1] Inject Repository

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    private EmailAdapter emailAdapter;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private DeliveryAdapter deliveryAdapter;

    @Autowired
    private WarehouseService warehouseService;

    /**
     * Get all orders list
     */
    public List<OrderDTO> getAllOrders() {
        logger.info("Fetching all orders");
        return orderRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get orders list by user ID, sorted by creation date descending (newest first)
     */
    public List<OrderDTO> getOrdersByUserId(Long userId) {
        logger.info("Fetching orders for user: {}", userId);
        return orderRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get order details by order ID
     */
    public Optional<OrderDTO> getOrderById(Long orderId) {
        logger.info("Fetching order by id: {}", orderId);
        // Use our new, correct method
        return orderRepository.findByIdWithProduct(orderId)
                .map(this::convertToDTO);
    }

    /**
     * Get order list by order status
     */
    public List<OrderDTO> getOrdersByStatus(OrderStatus status) {
        logger.info("Fetching orders by status: {}", status);
        return orderRepository.findByStatus(status).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create new order (adapted for single product model)
     */
    @Transactional // Ensure entire method is within one transaction
    public OrderDTO createOrder(CreateOrderRequest request) {
        logger.info("Creating new order for user: {}", request.getUserId());

        // --- Validation logic (unchanged) ---
        if (request.getOrderItems() == null || request.getOrderItems().size() != 1) {
            throw new IllegalArgumentException("Order must contain exactly one item.");
        }
        CreateOrderRequest.OrderItemRequest itemRequest = request.getOrderItems().get(0);
        Long productId = itemRequest.getProductId();
        Integer quantity = itemRequest.getQuantity();
        if (quantity == null || quantity <= 0) {
            throw new IllegalArgumentException("Invalid quantity for product: " + productId);
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        int totalStock = warehouseService.getProductQuantity(productId);
        if (totalStock < quantity) {
            throw new RuntimeException("Insufficient stock for product: " + product.getName());
        }

        // --- Core modification part ---

        // 1. First create a temporary Order object and save it to get the database-generated ID
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setStatus(OrderStatus.PENDING_STOCK_HOLD); // Use a temporary status
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setUnitPrice(product.getPrice());
        order.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        Order savedOrder = orderRepository.saveAndFlush(order); // Use saveAndFlush to ensure immediate ID acquisition
        Long orderId = savedOrder.getId();
        logger.info("[ORDER] Order temporary saved with id: {}, status: PENDING_STOCK_HOLD", orderId);

        // 2. Call inventory reservation service, passing the real orderId
        logger.info("[ORDER] Calling warehouseService.getAndUpdateAvailableWarehouse for orderId: {}", orderId);
        var whDTO = warehouseService.getAndUpdateAvailableWarehouse(productId, quantity, orderId);
        if (whDTO == null || whDTO.getInventoryTransactionIds() == null || whDTO.getInventoryTransactionIds().isEmpty()) {
            // If reservation fails, the entire transaction will rollback, and the temporary order saved above will also be revoked
            logger.error("[ORDER] Failed to hold stock for orderId: {}", orderId);
            throw new RuntimeException("Failed to hold stock, maybe it was taken by another order. Product: " + product.getName());
        }
        logger.info("[ORDER] Stock hold successful for orderId: {}, txIds: {}", orderId, whDTO.getInventoryTransactionIds());

        // 3. Convert transaction ID list to string and update order final status
        String inventoryTxIds = whDTO.getInventoryTransactionIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        savedOrder.setInventoryTransactionIds(inventoryTxIds);
        savedOrder.setStatus(OrderStatus.PLACED); // Update to final success status

        // Save again to update status and transaction IDs
        Order finalOrder = orderRepository.save(savedOrder);
        logger.info("[ORDER] Order finalized with id: {}, status: PLACED, inventoryTxIds: {}", finalOrder.getId(), inventoryTxIds);

        // [New] Create and save Outbox message
        try {
            PaymentOutbox outboxMessage = new PaymentOutbox();
            outboxMessage.setOrderId(finalOrder.getId());
            outboxMessage.setEventType(PaymentStatus.PENDING.name());

            // Create payload, a JSON string containing necessary information
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", finalOrder.getId());
            payload.put("amount", finalOrder.getTotalAmount());
            payload.put("userId", finalOrder.getUserId());
            //... You can add any information needed by the payment service

            outboxMessage.setPayload(objectMapper.writeValueAsString(payload));

            // Save Outbox record
            paymentOutboxRepository.save(outboxMessage);
            logger.info("PAYMENT_PENDING event saved to outbox for orderId: {}", finalOrder.getId());

        } catch (Exception e) {
            // If serializing payload or saving Outbox fails, entire transaction should rollback
            logger.error("Failed to save event to outbox for orderId: {}", finalOrder.getId(), e);
            throw new RuntimeException("Failed to create outbox event, rolling back transaction.", e);
        }

        return convertToDTO(finalOrder);
    }

    /**
     * Update order status
     */
    public OrderDTO updateOrderStatus(Long orderId, OrderStatus status) {
        logger.info("Updating order status: orderId={}, status={}", orderId, status);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);

        logger.info("Order status updated successfully");
        return convertToDTO(savedOrder);
    }

    /**
     * Cancel order (logic mostly unchanged, excellent)
     */
    public OrderDTO cancelOrder(Long orderId) {
        // ... Your cancel order logic hardly needs modification because it already works based on inventoryTransactionIds in the Order entity.
        // I only made minor adjustments
        logger.info("Cancelling order: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        // Cannot cancel if already picked up, in transit, delivered, or cancelled
        if (order.getStatus() == OrderStatus.PICKED_UP ||
            order.getStatus() == OrderStatus.IN_TRANSIT ||
            order.getStatus() == OrderStatus.DELIVERED ||
            order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("Cannot cancel order with status: " + order.getStatus());
        }

        // Only try to cancel delivery if one exists (order has been paid and delivery created)
        if (order.getDeliveryId() != null) {
            try {
                boolean cancelled = deliveryAdapter.cancelByOrderId(String.valueOf(order.getId()));
                if (!cancelled) {
                    throw new RuntimeException("Cannot cancel after pickup - delivery is already in progress");
                }
                logger.info("Delivery cancelled successfully for order: {}", orderId);
            } catch (Exception e) {
                // If delivery exists but can't be cancelled, stop the entire cancellation
                logger.error("Cannot cancel order {}: delivery cancellation failed: {}", orderId, e.getMessage());
                throw new RuntimeException("Cannot cancel order: " + e.getMessage());
            }
        } else {
            logger.info("No delivery exists for order: {}, skipping delivery cancellation", orderId);
        }

        // Rollback inventory
        try {
            if (order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
                List<Long> txIds = Arrays.stream(order.getInventoryTransactionIds().split(","))
                        .filter(s -> s != null && !s.trim().isEmpty())
                        .map(Long::valueOf)
                        .collect(Collectors.toList());
                if (!txIds.isEmpty()) {
                    UnholdProductRequest unhold = new UnholdProductRequest();
                    unhold.setInventoryTransactionIds(txIds);
                    warehouseService.unholdProduct(unhold);
                }
            }
        } catch (Exception e) {
            logger.error("CRITICAL: Unhold inventory failed for order {}: {}", orderId, e.getMessage());
        }

        order.setStatus(OrderStatus.CANCELLED);
        Order savedOrder = orderRepository.save(order);

        // Handle payment cancellation
        try {
            Optional<Payment> paymentOpt = paymentService.getPaymentByOrderId(order.getId());
            if (paymentOpt.isPresent()) {
                Payment payment = paymentOpt.get();
                
                if (payment.getStatus() == PaymentStatus.SUCCESS) {
                    // If payment was successful, process refund
                    paymentService.refundPayment(order.getId(), "Order cancelled by user");
                } else if (payment.getStatus() == PaymentStatus.PENDING) {
                    // If payment is still pending, mark as FAILED (will not be retried)
                    payment.setStatus(PaymentStatus.FAILED);
                    payment.setErrorMessage("Order cancelled by user");
                    paymentService.updatePayment(payment);
                    logger.info("Payment marked as FAILED due to order cancellation: orderId={}", order.getId());
                }
            }
        } catch (Exception e) {
            logger.warn("Payment cancellation skipped or failed for order {}: {}", order.getId(), e.getMessage());
        }

        // Email notification
        try {
            accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                String email = acc.getEmail();
                if (email != null && !email.isEmpty()) {
                    emailAdapter.sendOrderCancelled(email, String.valueOf(order.getId()), "User cancelled");
                }
            });
        } catch (Exception ignore) {}

        logger.info("Order cancelled successfully");
        return convertToDTO(savedOrder);
    }

    @Transactional
    public void handleDeliveryUpdate(DeliveryNotificationDTO notification) {
        logger.info("Handling delivery update for deliveryId={}: new status is {}",
                notification.getDeliveryId(), notification.getStatus());

        if (notification.getDeliveryId() == null) {
            logger.error("Delivery ID is null in notification.");
            return;
        }
//
//        Long orderId;
//        try {
//            orderId = Long.parseLong(notification.getOrderId());
//        } catch (NumberFormatException e) {
//            logger.error("Invalid orderId format in delivery notification: {}", notification.getOrderId());
//            return; // or throw exception
//        }

        Order order = orderRepository.findByDeliveryId(notification.getDeliveryId())
                .orElseThrow(() -> new RuntimeException("Order not found for delivery update with deliveryId: "
                        + notification.getDeliveryId()));

        Long orderId = order.getId();

        // Translate external DeliveryStatus to our internal OrderStatus
        // This is the core logic of the "anti-corruption layer"
        String incomingStatus = notification.getStatus();
        OrderStatus newStatus = translateDeliveryStatus(incomingStatus);

        // If new status is not null and differs from current status, update
        if (newStatus != null && order.getStatus() != newStatus) {
            order.setStatus(newStatus);
            orderRepository.save(order);
            logger.info("Order status updated to {} for orderId={}", newStatus, orderId);

            // Record OUT transaction when order is DELIVERED or LOST
            if (newStatus == OrderStatus.DELIVERED || "LOST".equalsIgnoreCase(incomingStatus)) {
                try {
                    boolean outRecorded = warehouseService.recordOutTransaction(
                            order.getProduct().getId(), 
                            order.getQuantity(), 
                            orderId
                    );
                    if (outRecorded) {
                        logger.info("OUT transaction recorded for orderId={}, status={}", orderId, newStatus);
                    } else {
                        logger.warn("Failed to record OUT transaction for orderId={}", orderId);
                    }
                } catch (Exception e) {
                    logger.error("Error recording OUT transaction for orderId={}: {}", orderId, e.getMessage(), e);
                }
            }

            // If delivery service reports LOST (we map to CANCELLED), trigger refund
            if ("LOST".equalsIgnoreCase(incomingStatus)) {
                try {
                    Optional<Payment> payOpt = paymentService.getPaymentByOrderId(orderId);
                    if (payOpt.isPresent() && payOpt.get().getStatus() != PaymentStatus.REFUNDED) {
                        paymentService.refundPayment(orderId, "Auto refund: package lost during delivery");
                        logger.info("Refund triggered for LOST delivery, orderId={}", orderId);
                    } else {
                        logger.info("Refund already done or payment missing for orderId={}, skip.", orderId);
                    }
                } catch (Exception e) {
                    logger.error("Refund on LOST failed for orderId={}: {}", orderId, e.getMessage(), e);
                }

                // Send order lost email notification (complementary to refund success email)
                try {
                    accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                        String email = acc.getEmail();
                        if (email != null && !email.isEmpty()) {
                            emailAdapter.sendOrderLost(email, String.valueOf(orderId), "Package lost during delivery, system has initiated refund");
                        }
                    });
                } catch (Exception mailEx) {
                    logger.warn("Failed to send order lost email for orderId={}: {}", orderId, mailEx.getMessage());
                }
            }

            // Asynchronously send email notification to user
            sendEmailForStatusUpdate(order, newStatus);
        } else {
            logger.info("No status change needed for orderId={}. Current: {}, New (translated): {}",
                    orderId, order.getStatus(), newStatus);
        }
    }

    /**
     * [New] Translate external DeliveryStatus string to internal OrderStatus enum
     */
    private OrderStatus translateDeliveryStatus(String deliveryStatusStr) {
        if (deliveryStatusStr == null) {
            return null;
        }
        // Note: The strings here need to be agreed upon with your DeliveryService team
        return switch (deliveryStatusStr.toUpperCase()) {
            case "PICKED_UP" -> OrderStatus.PICKED_UP;
            case "DELIVERING" -> OrderStatus.IN_TRANSIT;
            case "RECEIVED", "DELIVERED" -> OrderStatus.DELIVERED;
            // Map both LOST and CANCELLED to order's CANCELLED
            case "CANCELLED", "LOST" -> OrderStatus.CANCELLED;
            default -> null; // For states we don't care about (e.g., CREATED), we don't update
        };
    }

    /**
     * [New] Send corresponding email based on new order status
     */
    private void sendEmailForStatusUpdate(Order order, OrderStatus newStatus) {
        try {
            accountRepository.findById(order.getUserId()).ifPresent(account -> {
                String email = account.getEmail();
                if (email != null && !email.isEmpty()) {
                    switch (newStatus) {
                        case PICKED_UP:
                            // When status changes to PICKED_UP, it means package was picked up by delivery partner
                            if (order.getDeliveryId() != null) {
                                emailAdapter.sendOrderPickedUp(email, String.valueOf(order.getId()), order.getDeliveryId());
                            }
                            break;
                        case IN_TRANSIT:
                            // When status changes to IN_TRANSIT, it means package is delivering
                            if (order.getDeliveryId() != null) {
                                emailAdapter.sendOrderDelivering(email, String.valueOf(order.getId()), order.getDeliveryId());
                            }
                            break;
                        case DELIVERED:
                            // When status changes to DELIVERED, it means package was delivered
                            if (order.getDeliveryId() != null) {
                                emailAdapter.sendOrderDelivered(email, String.valueOf(order.getId()), order.getDeliveryId());
                            }
                            break;
                        // ... Can add email notifications for other statuses
                    }
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to send email notification for orderId={}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * Convert entity to DTO (adapted for single product model)
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setStatus(order.getStatus().name());
        dto.setTotalAmount(order.getTotalAmount());

        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());

        // --- Populate new product information ---
        dto.setQuantity(order.getQuantity());
        dto.setUnitPrice(order.getUnitPrice());

        // Safely get ID and Name from associated Product entity
        if (order.getProduct() != null) {
            dto.setProductId(order.getProduct().getId());
            dto.setProductName(order.getProduct().getName());
        }

        return dto;
    }
}