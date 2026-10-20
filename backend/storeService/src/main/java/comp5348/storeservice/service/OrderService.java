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

        // No PAY task here: PaymentService.createPayment writes the one and only PAY task for this order.
        // (This used to write a second one, and each ran with a fresh random reference: a double charge.)

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
     * Auto-cancel for the 15-minute payment timeout (review finding 3).
     * The caller read its list of unpaid orders earlier; a slow retry may have charged this order since then.
     * So lock the row and look again: only an order that is still PENDING_PAYMENT is cancelled.
     * (A user cancelling a PAID order on purpose goes through cancelOrder and gets a refund.)
     *
     * @return true if the order was cancelled, false if it was skipped
     */
    public boolean cancelIfStillUnpaid(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PENDING_PAYMENT) {
            logger.info("Auto-cancel skipped for order {}: status is now {}", orderId,
                    order == null ? "missing" : order.getStatus());
            return false;
        }
        cancelOrder(orderId); // same transaction; the row is already locked by us
        return true;
    }

    /**
     * Cancel order (logic mostly unchanged, excellent)
     */
    public OrderDTO cancelOrder(Long orderId) {
        // ... Your cancel order logic hardly needs modification because it already works based on inventoryTransactionIds in the Order entity.
        // I only made minor adjustments
        logger.info("Cancelling order: {}", orderId);

        // Lock the order row first (then the payment row inside requestRefund): a charge result arriving
        // at the same moment waits for us, then sees CANCELLED and writes the refund itself (rule 1).
        Order order = orderRepository.findByIdForUpdate(orderId)
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

        Order savedOrder = finishCancel(order, "User cancelled");
        logger.info("Order cancelled successfully");
        return convertToDTO(savedOrder);
    }

    /**
     * The part of a cancel that happens in store once delivery has agreed (or there is no delivery yet):
     * give the stock back, mark the order CANCELLED, write a REFUND task if the payment went through.
     * The caller has locked the order row and runs the transaction. Used by cancelOrder (user) and by
     * DeliveryEventService when a Cancelled event arrives for an order store still thinks is paid.
     */
    public Order finishCancel(Order order, String reason) {
        Long orderId = order.getId();
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

        // Money: only write a REFUND task (same transaction as the cancel). OutboxProcessor calls bank.
        // A payment that is still PENDING / PAY_UNKNOWN is left alone: the money may be moving right now.
        // If that charge succeeds later, its result transaction sees CANCELLED and writes the refund.
        if (paymentService.getPaymentByOrderId(order.getId()).isPresent()) {
            paymentService.requestRefund(order.getId(), "Order cancelled: " + reason);
        }

        // Email notification
        try {
            accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                String email = acc.getEmail();
                if (email != null && !email.isEmpty()) {
                    emailAdapter.sendOrderCancelled(email, String.valueOf(order.getId()), reason);
                }
            });
        } catch (Exception ignore) {}

        return savedOrder;
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

        dto.setDeliveryId(order.getDeliveryId());

        return dto;
    }
}