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
    private PaymentOutboxRepository paymentOutboxRepository; // 【修改点 1】注入 Repository

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
     * 獲取所有訂單列表
     */
    public List<OrderDTO> getAllOrders() {
        logger.info("Fetching all orders");
        return orderRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 根据用户ID获获取订单列表
     */
    public List<OrderDTO> getOrdersByUserId(Long userId) {
        logger.info("Fetching orders for user: {}", userId);
        return orderRepository.findByUserId(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 根据订单ID获获取订单详情
     */
    public Optional<OrderDTO> getOrderById(Long orderId) {
        logger.info("Fetching order by id: {}", orderId);
        // 使用我们新的、正确的方法
        return orderRepository.findByIdWithProduct(orderId)
                .map(this::convertToDTO);
    }

    /**
     * 根根据订单状态获取订单列表
     */
    public List<OrderDTO> getOrdersByStatus(OrderStatus status) {
        logger.info("Fetching orders by status: {}", status);
        return orderRepository.findByStatus(status).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * 创建新订单 (适配单商品模型)
     */
    @Transactional // 确保整个方法在一个事务内
    public OrderDTO createOrder(CreateOrderRequest request) {
        logger.info("Creating new order for user: {}", request.getUserId());

        // --- 验证逻辑 (保持不变) ---
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

        // --- 核心修改部分 ---

        // 1. 先创建一个临时的 Order 对象并保存，以获取数据库生成的 ID
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setStatus(OrderStatus.PENDING_STOCK_HOLD); // 使用一个临时状态
        order.setProduct(product);
        order.setQuantity(quantity);
        order.setUnitPrice(product.getPrice());
        order.setTotalAmount(product.getPrice().multiply(BigDecimal.valueOf(quantity)));
        Order savedOrder = orderRepository.saveAndFlush(order); // 使用 saveAndFlush 确保立即获得 ID
        Long orderId = savedOrder.getId();

        // 2. 调用库存预留服务，并传入真实的 orderId
        var whDTO = warehouseService.getAndUpdateAvailableWarehouse(productId, quantity, orderId);
        if (whDTO == null || whDTO.getInventoryTransactionIds() == null || whDTO.getInventoryTransactionIds().isEmpty()) {
            // 如果预留失败，整个事务会回滚，上面保存的临时订单也会被撤销
            throw new RuntimeException("Failed to hold stock, maybe it was taken by another order. Product: " + product.getName());
        }

        // 3. 将事务ID列表转换为字符串，并更新订单最终状态
        String inventoryTxIds = whDTO.getInventoryTransactionIds().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(","));

        savedOrder.setInventoryTransactionIds(inventoryTxIds);
        savedOrder.setStatus(OrderStatus.PLACED); // 更新为最终的成功状态

        // 再次保存以更新状态和事务ID
        Order finalOrder = orderRepository.save(savedOrder);
        logger.info("Order created successfully with id: {}", finalOrder.getId());

        // 【新增】创建并保存 Outbox 消息
        try {
            PaymentOutbox outboxMessage = new PaymentOutbox();
            outboxMessage.setOrderId(finalOrder.getId());
            outboxMessage.setEventType(PaymentStatus.PENDING.name());

            // 创建 payload，一个包含必要信息的 JSON 字符串
            Map<String, Object> payload = new HashMap<>();
            payload.put("orderId", finalOrder.getId());
            payload.put("amount", finalOrder.getTotalAmount());
            payload.put("userId", finalOrder.getUserId());
            //... 你可以加入任何支付服务需要的信息

            outboxMessage.setPayload(objectMapper.writeValueAsString(payload));

            // 保存 Outbox 记录
            paymentOutboxRepository.save(outboxMessage);
            logger.info("PAYMENT_PENDING event saved to outbox for orderId: {}", finalOrder.getId());

        } catch (Exception e) {
            // 如果序列化 payload 或保存 Outbox 失败，整个事务应该回滚
            logger.error("Failed to save event to outbox for orderId: {}", finalOrder.getId(), e);
            throw new RuntimeException("Failed to create outbox event, rolling back transaction.", e);
        }

        return convertToDTO(finalOrder);
    }

    /**
     * 更新订单状态
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
     * 取消订单 (逻辑大部分保持不变，非常棒)
     */
    public OrderDTO cancelOrder(Long orderId) {
        // ... 你的取消订单逻辑几乎不需要修改，因为它已经是基于 Order 实体中的 inventoryTransactionIds 工作的。
        // 我只做了微小调整
        logger.info("Cancelling order: {}", orderId);

        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));

        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("Cannot cancel order with status: " + order.getStatus());
        }

        // 尝试取消配送
        try {
            boolean cancelled = deliveryAdapter.cancelByOrderId(String.valueOf(order.getId()));
            if (!cancelled) {
                throw new RuntimeException("Cannot cancel after pickup or delivery not found");
            }
        } catch (Exception e) {
            logger.warn("Cancel delivery failed, proceeding with order cancellation. Reason: {}", e.getMessage());
        }

        // 回滚库存
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

        // 退款
        try {
            paymentService.refundPayment(order.getId(), "Order cancelled by user");
        } catch (Exception e) {
            logger.warn("Refund skipped or failed for order {}: {}", order.getId(), e.getMessage());
        }

        // 邮件通知
        try {
            accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                String email = acc.getEmail();
                if (email != null && !email.isEmpty()) {
                    emailAdapter.sendOrderCancelled(email, String.valueOf(order.getId()), "用户主动取消");
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
//            return; // 或者抛出异常
//        }

        Order order = orderRepository.findByDeliveryId(notification.getDeliveryId())
                .orElseThrow(() -> new RuntimeException("Order not found for delivery update with deliveryId: "
                        + notification.getDeliveryId()));

        Long orderId = order.getId();

        // 根据外部的 DeliveryStatus，“翻译”成我们内部的 OrderStatus
        // 这是“防腐层”的核心逻辑
        String incomingStatus = notification.getStatus();
        OrderStatus newStatus = translateDeliveryStatus(incomingStatus);

        // 如果新状态不为空，并且与当前状态不同，则更新
        if (newStatus != null && order.getStatus() != newStatus) {
            order.setStatus(newStatus);
            orderRepository.save(order);
            logger.info("Order status updated to {} for orderId={}", newStatus, orderId);

            // 如果是配送方上报的 LOST（我们映射为 CANCELLED），则触发退款
            if ("LOST".equalsIgnoreCase(incomingStatus)) {
                try {
                    Optional<Payment> payOpt = paymentService.getPaymentByOrderId(orderId);
                    if (payOpt.isPresent() && payOpt.get().getStatus() != PaymentStatus.REFUNDED) {
                        paymentService.refundPayment(orderId, "自动退款：配送丢失");
                        logger.info("Refund triggered for LOST delivery, orderId={}", orderId);
                    } else {
                        logger.info("Refund already done or payment missing for orderId={}, skip.", orderId);
                    }
                } catch (Exception e) {
                    logger.error("Refund on LOST failed for orderId={}: {}", orderId, e.getMessage(), e);
                }

                // 发送取消订单（因丢失）通知邮件（与退款成功邮件互补）
                try {
                    accountRepository.findById(order.getUserId()).ifPresent(acc -> {
                        String email = acc.getEmail();
                        if (email != null && !email.isEmpty()) {
                            emailAdapter.sendOrderCancelled(email, String.valueOf(orderId), "包裹在配送过程中丢失，系统已为您发起退款");
                        }
                    });
                } catch (Exception mailEx) {
                    logger.warn("Send order-cancelled email failed for orderId={}: {}", orderId, mailEx.getMessage());
                }
            }

            // 异步发送邮件通知用户
            sendEmailForStatusUpdate(order, newStatus);
        } else {
            logger.info("No status change needed for orderId={}. Current: {}, New (translated): {}",
                    orderId, order.getStatus(), newStatus);
        }
    }

    /**
     * 【新增】将外部的 DeliveryStatus 字符串翻译为内部的 OrderStatus 枚举
     */
    private OrderStatus translateDeliveryStatus(String deliveryStatusStr) {
        if (deliveryStatusStr == null) {
            return null;
        }
        // 注意：这里的字符串需要和你的 DeliveryService 团队约定好
        return switch (deliveryStatusStr.toUpperCase()) {
            case "PICKED_UP" -> OrderStatus.SHIPPED;
            case "DELIVERING" -> OrderStatus.IN_TRANSIT;
            case "RECEIVED", "DELIVERED" -> OrderStatus.DELIVERED;
            // 将 LOST 与 CANCELLED 都映射到订单的 CANCELLED
            case "CANCELLED", "LOST" -> OrderStatus.CANCELLED;
            default -> null; // 对于不关心的状态（如CREATED），我们不进行更新
        };
    }

    /**
     * 【新增】根据新的订单状态发送相应的邮件
     */
    private void sendEmailForStatusUpdate(Order order, OrderStatus newStatus) {
        try {
            accountRepository.findById(order.getUserId()).ifPresent(account -> {
                String email = account.getEmail();
                if (email != null && !email.isEmpty()) {
                    switch (newStatus) {
                        case SHIPPED:
                            // emailAdapter.sendOrderShipped(email, String.valueOf(order.getId()));
                            logger.info("Pretending to send SHIPPED email to {}", email);
                            break;
                        case DELIVERED:
                            // emailAdapter.sendOrderDelivered(email, String.valueOf(order.getId()));
                            logger.info("Pretending to send DELIVERED email to {}", email);
                            break;
                        // ... 可以为其他状态添加邮件通知
                    }
                }
            });
        } catch (Exception e) {
            logger.warn("Failed to send email notification for orderId={}: {}", order.getId(), e.getMessage());
        }
    }

    /**
     * 转换实体为 DTO (适配单商品模型)
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setStatus(order.getStatus().name());
        dto.setTotalAmount(order.getTotalAmount());

        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());

        // --- 填充新增的商品信息 ---
        dto.setQuantity(order.getQuantity());
        dto.setUnitPrice(order.getUnitPrice());

        // 安全地从关联的 Product 实体中获取 ID 和 Name
        if (order.getProduct() != null) {
            dto.setProductId(order.getProduct().getId());
            dto.setProductName(order.getProduct().getName());
        }

        return dto;
    }
}