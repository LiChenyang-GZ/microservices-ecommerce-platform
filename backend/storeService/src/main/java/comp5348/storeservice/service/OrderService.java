package comp5348.storeservice.service;

import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
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
    private ProductService productService;

    @Autowired
    private comp5348.storeservice.repository.AccountRepository accountRepository;

    @Autowired
    private comp5348.storeservice.adapter.EmailAdapter emailAdapter;

    @Autowired
    private PaymentService paymentService;

    @Autowired
    private comp5348.storeservice.adapter.DeliveryAdapter deliveryAdapter;

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
     * 根據用戶ID獲取訂單列表
     */
    public List<OrderDTO> getOrdersByUserId(Long userId) {
        logger.info("Fetching orders for user: {}", userId);
        return orderRepository.findByUserId(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * 根據訂單ID獲取訂單詳情
     */
    public Optional<OrderDTO> getOrderById(Long orderId) {
        logger.info("Fetching order by id: {}", orderId);
        return orderRepository.findByIdWithOrderItems(orderId)
                .map(this::convertToDTO);
    }
    
    /**
     * 根據訂單狀態獲取訂單列表
     */
    public List<OrderDTO> getOrdersByStatus(OrderStatus status) {
        logger.info("Fetching orders by status: {}", status);
        return orderRepository.findByStatus(status).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }
    
    /**
     * 創建新訂單
     */
    public OrderDTO createOrder(CreateOrderRequest request) {
        logger.info("Creating new order for user: {}", request.getUserId());
        
        // 驗證商品是否存在且有庫存（聚合多仓库存）
        validateOrderItems(request.getOrderItems());
        
        // 創建訂單
        Order order = new Order();
        order.setUserId(request.getUserId());
        order.setStatus(OrderStatus.PLACED);
        
        // 計算總金額
        BigDecimal totalAmount = BigDecimal.ZERO;
        // 多仓预占：逐项调用仓储服务，收集事务ID；任一失败则回滚已预占
        java.util.List<Long> allInventoryTxIds = new java.util.ArrayList<>();
        for (CreateOrderRequest.OrderItemRequest itemRequest : request.getOrderItems()) {
            Product product = productRepository.findById(itemRequest.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + itemRequest.getProductId()));

            if (itemRequest.getQty() == null || itemRequest.getQty() <= 0) {
                throw new RuntimeException("Invalid quantity for product: " + product.getId());
            }

            var whDTO = warehouseService.getAndUpdateAvailableWarehouse(product.getId(), itemRequest.getQty());
            if (whDTO == null || whDTO.getInventoryTransactionIds() == null || whDTO.getInventoryTransactionIds().isEmpty()) {
                // 回滚已预占
                if (!allInventoryTxIds.isEmpty()) {
                    comp5348.storeservice.dto.UnholdProductRequest unhold = new comp5348.storeservice.dto.UnholdProductRequest();
                    unhold.setInventoryTransactionIds(allInventoryTxIds);
                    try { warehouseService.unholdProduct(unhold); } catch (Exception ignore) {}
                }
                throw new RuntimeException("Insufficient stock across warehouses for product: " + product.getId());
            }
            allInventoryTxIds.addAll(whDTO.getInventoryTransactionIds());

            OrderItem orderItem = new OrderItem();
            orderItem.setOrder(order);
            orderItem.setProduct(product);
            orderItem.setQty(itemRequest.getQty());
            orderItem.setUnitPrice(product.getPrice());
            orderItem.setTotalPrice(product.getPrice().multiply(BigDecimal.valueOf(itemRequest.getQty())));
            
            order.getOrderItems().add(orderItem);
            totalAmount = totalAmount.add(orderItem.getTotalPrice());
        }
        
        order.setTotalAmount(totalAmount);
        
        // 保存訂單
        if (!allInventoryTxIds.isEmpty()) {
            order.setInventoryTransactionIds(allInventoryTxIds.stream().map(String::valueOf).collect(java.util.stream.Collectors.joining(",")));
        }
        Order savedOrder = orderRepository.save(order);
        logger.info("Order created successfully with id: {}", savedOrder.getId());
        
        return convertToDTO(savedOrder);
    }
    
    /**
     * 更新訂單狀態
     */
    public OrderDTO updateOrderStatus(Long orderId, OrderStatus status) {
        logger.info("Updating order status: orderId={}, status={}", orderId, status);
        
        Order order = orderRepository.findByIdWithOrderItems(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        order.setStatus(status);
        Order savedOrder = orderRepository.save(order);
        
        logger.info("Order status updated successfully");
        return convertToDTO(savedOrder);
    }
    
    /**
     * 取消訂單
     */
    public OrderDTO cancelOrder(Long orderId) {
        logger.info("Cancelling order: {}", orderId);
        
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        if (order.getStatus() == OrderStatus.DELIVERED || order.getStatus() == OrderStatus.CANCELLED) {
            throw new RuntimeException("Cannot cancel order with status: " + order.getStatus());
        }
        
        // 尝试取消配送：必须在未取件前成功取消，否则拒绝订单取消
        try {
            boolean cancelled = deliveryAdapter.cancelByOrderId(String.valueOf(order.getId()));
            if (!cancelled) {
                throw new RuntimeException("Cannot cancel after pickup or delivery not found");
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Cancel delivery failed: " + e.getMessage());
        }

        // 回滚库存（基于订单保存的 HOLD 事务ID）
        try {
            if (order.getInventoryTransactionIds() != null && !order.getInventoryTransactionIds().isEmpty()) {
                java.util.List<Long> txIds = java.util.Arrays.stream(order.getInventoryTransactionIds().split(","))
                        .filter(s -> s != null && !s.isEmpty())
                        .map(Long::valueOf)
                        .collect(java.util.stream.Collectors.toList());
                if (!txIds.isEmpty()) {
                    comp5348.storeservice.dto.UnholdProductRequest unhold = new comp5348.storeservice.dto.UnholdProductRequest();
                    unhold.setInventoryTransactionIds(txIds);
                    warehouseService.unholdProduct(unhold);
                }
            }
        } catch (Exception e) {
            logger.warn("Unhold inventory failed for order {}: {}", orderId, e.getMessage());
        }

        order.setStatus(OrderStatus.CANCELLED);
        Order savedOrder = orderRepository.save(order);

        // 退款（若已支付成功）
        try {
            paymentService.refundPayment(order.getId(), "Order cancelled by user");
        } catch (Exception e) {
            logger.info("Refund skipped or failed for order {}: {}", order.getId(), e.getMessage());
        }

        // 邮件通知
        try {
            var acc = accountRepository.findById(order.getUserId()).orElse(null);
            String email = acc != null ? acc.getEmail() : null;
            if (email != null && !email.isEmpty()) {
                emailAdapter.sendOrderCancelled(email, String.valueOf(order.getId()), "用户主动取消");
            }
        } catch (Exception ignore) {}

        logger.info("Order cancelled successfully");
        return convertToDTO(savedOrder);
    }
    
    /**
     * 驗證訂單項目
     */
    private void validateOrderItems(List<CreateOrderRequest.OrderItemRequest> orderItems) {
        for (CreateOrderRequest.OrderItemRequest itemRequest : orderItems) {
            Long pid = itemRequest.getProductId();
            Integer need = itemRequest.getQty();
            int total = warehouseService.getProductQuantity(pid);
            if (need == null || need <= 0 || total < need) {
                Product product = productRepository.findById(pid).orElse(null);
                String productName = product != null ? product.getName() : "Unknown";
                throw new RuntimeException("Insufficient stock for product: " + productName);
            }
        }
    }
    
    /**
     * 轉換實體為DTO
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setUserId(order.getUserId());
        dto.setStatus(order.getStatus().name());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setCreatedAt(order.getCreatedAt());
        dto.setUpdatedAt(order.getUpdatedAt());
        
        // 轉換訂單項目
        List<OrderItemDTO> orderItemDTOs = order.getOrderItems().stream()
                .map(this::convertOrderItemToDTO)
                .collect(Collectors.toList());
        dto.setOrderItems(orderItemDTOs);
        
        return dto;
    }
    
    /**
     * 轉換訂單項目實體為DTO
     */
    private OrderItemDTO convertOrderItemToDTO(OrderItem orderItem) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setId(orderItem.getId());
        dto.setOrderId(orderItem.getOrder().getId());
        dto.setProductId(orderItem.getProduct().getId());
        dto.setProductName(orderItem.getProduct().getName());
        dto.setQty(orderItem.getQty());
        dto.setUnitPrice(orderItem.getUnitPrice());
        dto.setTotalPrice(orderItem.getTotalPrice());
        return dto;
    }
}
