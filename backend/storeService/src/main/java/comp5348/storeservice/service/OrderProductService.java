package comp5348.storeservice.service;

import comp5348.storeservice.adapter.BankAdapter;
import comp5348.storeservice.dto.*;
import comp5348.storeservice.model.*;
import comp5348.storeservice.repository.OrderRepository;
import comp5348.storeservice.repository.ProductRepository;
import comp5348.storeservice.service.WarehouseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * 整合的訂單與商品管理服務
 * 整合了商品管理、訂單管理和支付功能
 */
@Service
@Transactional
public class OrderProductService {
    
    private static final Logger logger = LoggerFactory.getLogger(OrderProductService.class);
    
    @Autowired
    private ProductService productService;
    
    @Autowired
    private OrderService orderService;
    
    @Autowired
    private PaymentService paymentService;
    
    @Autowired
    private ProductRepository productRepository;
    
    @Autowired
    private OrderRepository orderRepository;
    
    @Autowired
    private BankAdapter bankAdapter;

    @Autowired
    private WarehouseService warehouseService;
    
    @org.springframework.beans.factory.annotation.Value("${bank.customer.account:CUSTOMER_ACCOUNT_001}")
    private String customerAccount;

    @Autowired
    private comp5348.storeservice.repository.AccountRepository accountRepository;
    
    /**
     * 創建訂單並處理支付
     * 這是整合的核心方法，處理從商品選擇到支付完成的整個流程
     */
    public OrderDTO createOrderWithPayment(CreateOrderRequest request) {
        logger.info("Creating order with payment for user: {}", request.getUserId());
        
        // 0. 预估总价
        java.math.BigDecimal estimatedTotal = java.math.BigDecimal.ZERO;
        for (CreateOrderRequest.OrderItemRequest item : request.getOrderItems()) {
            comp5348.storeservice.model.Product p = productRepository.findById(item.getProductId())
                    .orElseThrow(() -> new RuntimeException("Product not found: " + item.getProductId()));
            estimatedTotal = estimatedTotal.add(p.getPrice().multiply(java.math.BigDecimal.valueOf(item.getQty())));
        }

        // 0.1 仅允许用户个人账户下单；若无账户或余额不足，则直接拒单
        var userAcc = accountRepository.findById(request.getUserId()).orElse(null);
        if (userAcc == null || userAcc.getBankAccountNumber() == null || userAcc.getBankAccountNumber().isEmpty()) {
            throw new IllegalStateException("User has no linked bank account. Please bind a bank account before ordering");
        }
        String fromAccount = userAcc.getBankAccountNumber();

        java.math.BigDecimal balance = bankAdapter.getBalance(fromAccount);
        if (balance == null) {
            throw new IllegalStateException("Bank service unavailable for account balance check");
        }
        if (balance.compareTo(estimatedTotal) < 0) {
            throw new IllegalArgumentException("Insufficient balance. Available: " + balance + ", Required: " + estimatedTotal);
        }

        // 1. 創建訂單
        OrderDTO order = orderService.createOrder(request);
        
        // 2. 更新訂單狀態為待付款
        orderService.updateOrderStatus(order.getId(), OrderStatus.PENDING_PAYMENT);
        
        // 3. 創建支付記錄
        try {
            PaymentRequest paymentRequest = new PaymentRequest();
            paymentRequest.setOrderId(order.getId());
            paymentRequest.setAmount(order.getTotalAmount());
            
            Payment payment = paymentService.createPayment(paymentRequest.getOrderId(), paymentRequest.getAmount());
            logger.info("Payment created for order: {}, paymentId: {}", order.getId(), payment.getId());
            
        } catch (Exception e) {
            logger.error("Failed to create payment for order {}: {}", order.getId(), e.getMessage());
            // 如果支付創建失敗，取消訂單
            orderService.cancelOrder(order.getId());
            throw new RuntimeException("Failed to create payment: " + e.getMessage());
        }
        
        return order;
    }
    
    /**
     * 處理支付成功後的訂單狀態更新
     */
    public OrderDTO processPaymentSuccess(Long orderId) {
        logger.info("Processing payment success for order: {}", orderId);
        
        // 1. 更新訂單狀態為已付款
        OrderDTO order = orderService.updateOrderStatus(orderId, OrderStatus.PAID);
        
        // 2. 更新訂單狀態為處理中
        order = orderService.updateOrderStatus(orderId, OrderStatus.PROCESSING);
        
        logger.info("Order {} status updated to PROCESSING after successful payment", orderId);
        
        return order;
    }
    
    /**
     * 處理支付失敗後的訂單狀態更新
     */
    public OrderDTO processPaymentFailure(Long orderId) {
        logger.info("Processing payment failure for order: {}", orderId);
        
        // 取消訂單
        OrderDTO order = orderService.cancelOrder(orderId);
        
        logger.info("Order {} cancelled due to payment failure", orderId);
        
        return order;
    }
    
    /**
     * 獲取訂單的完整資訊（包含支付狀態）
     */
    public OrderWithPaymentDTO getOrderWithPaymentInfo(Long orderId) {
        logger.info("Getting order with payment info for order: {}", orderId);
        
        OrderDTO order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        // 獲取支付資訊
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        
        OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
        orderWithPayment.setOrder(order);
        orderWithPayment.setPayment(payment.orElse(null));
        
        return orderWithPayment;
    }
    
    /**
     * 獲取用戶的訂單列表（包含支付狀態）
     */
    public List<OrderWithPaymentDTO> getUserOrdersWithPaymentInfo(Long userId) {
        logger.info("Getting user orders with payment info for user: {}", userId);
        
        List<OrderDTO> orders = orderService.getOrdersByUserId(userId);
        
        return orders.stream()
                .map(order -> {
                    Optional<Payment> payment = paymentService.getPaymentByOrderId(order.getId());
                    OrderWithPaymentDTO orderWithPayment = new OrderWithPaymentDTO();
                    orderWithPayment.setOrder(order);
                    orderWithPayment.setPayment(payment.orElse(null));
                    return orderWithPayment;
                })
                .collect(Collectors.toList());
    }
    
    /**
     * 取消訂單並處理退款
     */
    public OrderDTO cancelOrderWithRefund(Long orderId, String reason) {
        logger.info("Cancelling order with refund for order: {}, reason: {}", orderId, reason);
        
        // 1. 檢查訂單狀態
        OrderDTO order = orderService.getOrderById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found: " + orderId));
        
        if (order.getStatus().equals(OrderStatus.CANCELLED.name())) {
            throw new RuntimeException("Order is already cancelled");
        }
        
        // 2. 取消訂單
        order = orderService.cancelOrder(orderId);
        
        // 3. 如果已付款，處理退款
        Optional<Payment> payment = paymentService.getPaymentByOrderId(orderId);
        if (payment.isPresent() && payment.get().getStatus() == PaymentStatus.SUCCESS) {
            try {
                paymentService.refundPayment(orderId, reason);
                logger.info("Refund processed for order: {}", orderId);
            } catch (Exception e) {
                logger.error("Failed to process refund for order {}: {}", orderId, e.getMessage());
                throw new RuntimeException("Failed to process refund: " + e.getMessage());
            }
        }
        
        return order;
    }
    
    /**
     * 獲取商品庫存資訊（使用多仓聚合库存）
     */
    public ProductStockDTO getProductStockInfo(Long productId) {
        logger.info("Getting product stock info for product: {}", productId);
        
        ProductDTO product = productService.getProductById(productId)
                .orElseThrow(() -> new RuntimeException("Product not found: " + productId));
        
        // 使用多仓聚合库存
        int totalQuantity = warehouseService.getProductQuantity(productId);
        
        ProductStockDTO stockInfo = new ProductStockDTO();
        stockInfo.setProductId(productId);
        stockInfo.setProductName(product.getName());
        stockInfo.setCurrentStock(totalQuantity);
        stockInfo.setAvailable(totalQuantity > 0);
        
        return stockInfo;
    }
    
    /**
     * 更新商品庫存（用於庫存管理）
     */
    public ProductDTO updateProductStock(Long productId, Integer newStock) {
        logger.warn("OrderProductService.updateProductStock is deprecated. Use warehouse APIs instead. pid={}, newStock={}", productId, newStock);
        throw new UnsupportedOperationException("Deprecated API: update stock via warehouse endpoints");
    }
}
